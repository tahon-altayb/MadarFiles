package com.madar.files;
import android.content.*;
import android.os.SystemClock;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
/** One exclusive GPU lease, shared by export and viewing; never persists OAuth. */
final class MusicGpuSession {
 static final long IDLE_MS=10*60*1000L,MAX_AGE_MS=40*60*1000L;
 private static volatile ColabMusic.Session idle;private static volatile boolean busy;private static volatile long idleAt,generation;
 static boolean available(Context c){ColabMusic.Session s=idle;return !busy&&s!=null&&s.runtime.grant.project.equals(c.getSharedPreferences("colab_connection",0).getString("project","madarfiles"))&&SystemClock.elapsedRealtime()-idleAt<IDLE_MS&&SystemClock.elapsedRealtime()-s.createdAt<MAX_AGE_MS;}
 static synchronized ColabMusic.Session acquire(Context c,ColabCredentials.Grant grant,AtomicBoolean stop,MusicSeparator.Progress progress)throws Exception{
  if(busy)throw new IOException("توجد عملية GPU أخرى؛ أوقفها أو انتظر اكتمالها");
  ColabMusic.Session s=idle;idle=null;generation++;if(s!=null&&(SystemClock.elapsedRealtime()-idleAt>=IDLE_MS||SystemClock.elapsedRealtime()-s.createdAt>=MAX_AGE_MS||grant!=null&&!s.runtime.grant.project.equals(grant.project))){s.dispose();s=null;}
  if(s==null)s=new ColabMusic.Session(c.getApplicationContext(),grant,stop,progress);else{s.bind(stop,progress);progress.update(12,"إعادة استخدام جلسة GPU والنموذج المحمّل");}
  busy=true;c.stopService(new Intent(c,MusicGpuService.class));return s;
 }
 static synchronized void release(ColabMusic.Session s){
  if(!s.leased)return;s.leased=false;s.stopWatcher();if(s.channel!=null){s.channel.close();s.channel=null;}
  try{if(s.healthy&&!s.cancel.get()&&!s.callerCancel.get()&&SystemClock.elapsedRealtime()-s.createdAt<MAX_AGE_MS){idle=s;idleAt=SystemClock.elapsedRealtime();s.status=(p,m)->{};s.context.startForegroundService(new Intent(s.context,MusicGpuService.class).putExtra("generation",++generation));}else s.dispose();}
  catch(RuntimeException denied){idle=null;s.dispose();}finally{busy=false;}
 }
 static synchronized void stopIdle(){if(busy)throw new IllegalStateException("أوقف معالجة GPU الحالية أولًا");expire(generation);}
 static synchronized void expire(long expected){if(expected!=generation)return;ColabMusic.Session s=idle;idle=null;if(s!=null)s.dispose();}
 private MusicGpuSession(){}
}
