package com.madar.files;
import android.content.*;
import android.os.SystemClock;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
/** One exclusive GPU lease shared by export/viewing. OAuth remains memory-only. */
final class MusicGpuSession {
 static final long IDLE_MS=20*60*1000L,MAX_AGE_MS=40*60*1000L;
 private static volatile ColabMusic.Session idle,active;private static volatile boolean busy;private static volatile long idleAt,generation;
 static boolean available(Context c){ColabMusic.Session s=idle;return !busy&&s!=null&&s.runtime.grant.project.equals(c.getSharedPreferences("colab_connection",0).getString("project","madarfiles"))&&remainingIdle()>0;}
 static boolean busy(){return busy;}
 static boolean held(){return idle!=null||busy;}
 static long generation(){return generation;}
 static long remainingIdle(){ColabMusic.Session s=idle;return s==null?0:Math.max(0,Math.min(IDLE_MS-(SystemClock.elapsedRealtime()-idleAt),MAX_AGE_MS-(SystemClock.elapsedRealtime()-s.createdAt)));}
 private static void notifyState(Context c){c.startForegroundService(new Intent(c,MusicGpuService.class));}
 static synchronized ColabMusic.Session acquire(Context c,ColabCredentials.Grant grant,AtomicBoolean stop,MusicSeparator.Progress progress)throws Exception{
  if(busy)throw new IOException("توجد عملية GPU أخرى؛ أوقفها أو انتظر اكتمالها");
  ColabMusic.Session s=idle;long remaining=remainingIdle();idle=null;generation++;
  if(s!=null&&(remaining<=0||grant!=null&&!s.runtime.grant.project.equals(grant.project))){s.dispose();s=null;}
  if(s==null)s=new ColabMusic.Session(c.getApplicationContext(),grant,stop,progress);else{s.bind(stop,progress);progress.update(12,"إعادة استخدام جلسة GPU والنموذج المحمّل");}
  active=s;busy=true;
  try{notifyState(c);}catch(RuntimeException denied){active=null;busy=false;s.leased=false;s.dispose();throw new IOException("تعذر بدء خدمة GPU في الخلفية؛ افتح مدار وأعد المحاولة",denied);}return s;
 }
 static synchronized void release(ColabMusic.Session s){
  if(!s.leased)return;s.leased=false;s.stopWatcher();if(s.channel!=null){s.channel.close();s.channel=null;}
  try{if(s.healthy&&!s.cancel.get()&&!s.callerCancel.get()&&SystemClock.elapsedRealtime()-s.createdAt<MAX_AGE_MS){idle=s;idleAt=SystemClock.elapsedRealtime();generation++;s.status=(p,m)->{};}else s.dispose();}
  finally{if(active==s)active=null;busy=false;}
  try{notifyState(s.context);}catch(RuntimeException denied){if(idle==s){idle=null;s.dispose();}}
 }
 static synchronized void beforeRuntime(ColabRuntime runtime)throws IOException{
  if(active!=null&&active.runtime!=runtime)throw new IOException("أوقف معالجة GPU الحالية قبل بدء جلسة Colab أخرى");
  if(idle!=null&&idle.runtime!=runtime){ColabMusic.Session old=idle;idle=null;generation++;old.dispose();try{notifyState(old.context);}catch(RuntimeException ignored){}}
 }
 static synchronized void stopIdle(){if(busy)throw new IllegalStateException("أوقف معالجة GPU الحالية أولًا");expire(generation);}
 static synchronized void stopGeneration(long expected){if(expected!=generation)return;if(active!=null){active.callerCancel.set(true);active.cancel.set(true);}else expire(expected);}
 static synchronized void expire(long expected){if(expected!=generation||idle==null)return;ColabMusic.Session s=idle;idle=null;generation++;s.dispose();try{notifyState(s.context);}catch(RuntimeException ignored){}}
 private MusicGpuSession(){}
}
