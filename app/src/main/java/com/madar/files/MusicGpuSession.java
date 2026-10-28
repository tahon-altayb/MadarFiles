package com.madar.files;
import android.content.*;
import android.os.SystemClock;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
/** Short state locks only: never wait for runtime creation/cleanup while holding the pool monitor. */
final class MusicGpuSession {
 static final long IDLE_MS=20*60*1000L,MAX_AGE_MS=40*60*1000L;
 private static volatile ColabMusic.Session idle,active,retiring;private static volatile boolean busy;private static volatile long idleAt,generation;
 static boolean available(Context c){ColabMusic.Session s=idle;return !busy()&&s!=null&&s.runtime.grant.project.equals(c.getSharedPreferences("colab_connection",0).getString("project","madarfiles"))&&remainingIdle()>0;}
 static boolean busy(){return busy||retiring!=null;}
 static boolean closing(){return retiring!=null;}
 static boolean held(){return idle!=null||busy();}
 static long generation(){return generation;}
 static long remainingIdle(){ColabMusic.Session s=idle;return s==null?0:Math.max(0,Math.min(IDLE_MS-(SystemClock.elapsedRealtime()-idleAt),MAX_AGE_MS-(SystemClock.elapsedRealtime()-s.createdAt)));}
 private static void notifyState(Context c){c.startForegroundService(new Intent(c,MusicGpuService.class));}
 static ColabMusic.Session acquire(Context c,ColabCredentials.Grant grant,AtomicBoolean stop,MusicSeparator.Progress progress)throws Exception{
  ColabMusic.Session s;
  synchronized(MusicGpuSession.class){
   if(busy())throw new IOException(closing()?"جارٍ إيقاف جلسة GPU السابقة بأمان؛ تابع حالتها من إشعار مدار":"توجد عملية GPU أخرى؛ أوقفها أو انتظر اكتمالها");
   s=idle;
   if(s!=null&&(remainingIdle()<=0||grant!=null&&!s.runtime.grant.project.equals(grant.project))){idle=null;retireLocked(s);s=null;throw new IOException("انتهت صلاحية جلسة GPU؛ جارٍ إغلاقها قبل اتصال جديد");}
   if(s==null)s=new ColabMusic.Session(c.getApplicationContext(),grant,stop,progress);else s.bind(stop,progress);
   idle=null;active=s;busy=true;generation++;
  }
  try{if(s.initialized)progress.update(12,"إعادة استخدام جلسة GPU والنموذج المحمّل");notifyState(c);return s;}
  catch(RuntimeException denied){s.healthy=false;release(s);throw new IOException("تعذر بدء خدمة GPU في الخلفية؛ افتح مدار وأعد المحاولة",denied);}
 }
 static void release(ColabMusic.Session s){
  synchronized(MusicGpuSession.class){
   if(active!=s||!s.leased)return;s.leased=false;s.stopWatcher();if(s.channel!=null){s.channel.close();s.channel=null;}active=null;busy=false;
   if(s.healthy&&!s.cancel.get()&&!s.callerCancel.get()&&SystemClock.elapsedRealtime()-s.createdAt<MAX_AGE_MS){idle=s;idleAt=SystemClock.elapsedRealtime();generation++;s.status=(p,m)->{};}
   else retireLocked(s);
  }
  try{notifyState(s.context);}catch(RuntimeException denied){synchronized(MusicGpuSession.class){if(idle==s){idle=null;retireLocked(s);}}}
 }
 /** Caller holds the state monitor; all blocking disposal runs on a separate thread after release. */
 private static void retireLocked(ColabMusic.Session s){
  retiring=s;generation++;s.cancel.set(true);s.stopWatcher();s.warning="إيقاف جلسة GPU السابقة جارٍ في الخلفية؛ تابع إشعار مدار";s.status=(p,m)->{};
  Thread cleanup=new Thread(()->{try{s.dispose();}finally{synchronized(MusicGpuSession.class){if(retiring==s){retiring=null;generation++;}}try{notifyState(s.context);}catch(RuntimeException ignored){}}},"madar-gpu-retire");cleanup.setDaemon(true);cleanup.start();
 }
 static void beforeRuntime(ColabRuntime runtime)throws IOException{
  ColabMusic.Session old=null;
  synchronized(MusicGpuSession.class){
   if(retiring!=null&&retiring.runtime!=runtime)throw new IOException("جارٍ إيقاف جلسة GPU السابقة؛ انتظر إشعار الإغلاق");
   if(active!=null&&active.runtime!=runtime)throw new IOException("أوقف معالجة GPU الحالية قبل بدء جلسة Colab أخرى");
   if(idle!=null&&idle.runtime!=runtime){old=idle;idle=null;retiring=old;generation++;}
  }
  // This path belongs to another Colab tool; finish old cleanup before it can change the journal.
  if(old!=null){old.cancel.set(true);try{old.dispose();}finally{synchronized(MusicGpuSession.class){if(retiring==old){retiring=null;generation++;}}try{notifyState(old.context);}catch(RuntimeException ignored){}}}
 }
 static synchronized void stopIdle(){if(busy)throw new IllegalStateException("أوقف معالجة GPU الحالية أولًا");if(retiring==null)expire(generation);}
 static synchronized void stopGeneration(long expected){if(expected!=generation)return;if(active!=null){active.callerCancel.set(true);active.cancel.set(true);}else expire(expected);}
 static synchronized void expire(long expected){if(expected!=generation||idle==null)return;ColabMusic.Session s=idle;idle=null;retireLocked(s);try{notifyState(s.context);}catch(RuntimeException ignored){}}
 private MusicGpuSession(){}
}
