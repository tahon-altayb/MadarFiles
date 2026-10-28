package com.madar.files;
import android.app.*;import android.content.*;import android.os.*;
/** Visible, cancellable bounded idle hold, avoiding a cold runtime for each clip. */
public final class MusicGpuService extends Service {
 private final Handler timer=new Handler(Looper.getMainLooper());private PowerManager.WakeLock lock;private boolean stopping;private long generation;private int lastStart;
 @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("gpu_warm","جلسة GPU الجاهزة",NotificationManager.IMPORTANCE_LOW));}
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  lastStart=startId;generation=intent==null?generation:intent.getLongExtra("generation",generation);
  stopping=false;long idleMs=intent==null?MusicGpuSession.IDLE_MS:intent.getLongExtra("idleMs",MusicGpuSession.IDLE_MS);try{PendingIntent stop=PendingIntent.getService(this,194,new Intent(this,MusicGpuService.class).setAction("stop").putExtra("generation",generation),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);Notification n=new Notification.Builder(this,"gpu_warm").setSmallIcon(R.drawable.ic_launcher).setContentTitle("مدار · GPU والنموذج جاهزان").setContentText("جاهز لإعادة الاستخدام حتى "+Math.max(1,(idleMs+59999)/60000)+" دقيقة؛ يمكنك الإيقاف الآن").setOngoing(true).addAction(new Notification.Action.Builder(null,"إيقاف GPU الآن",stop).build()).build();startForeground(194,n);if(intent!=null&&"stop".equals(intent.getAction())){stopWarm();return START_NOT_STICKY;}if(lock==null){lock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MadarFiles:GpuIdle");lock.acquire(idleMs+10000);}timer.removeCallbacksAndMessages(null);timer.postDelayed(this::stopWarm,idleMs);}catch(RuntimeException error){stopWarm();}return START_NOT_STICKY;
 }
 private void stopWarm(){if(stopping)return;stopping=true;final long ticket=generation;final int start=lastStart;new Thread(()->{MusicGpuSession.expire(ticket);new Handler(Looper.getMainLooper()).post(()->{if(generation==ticket){stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(start);}});},"madar-gpu-idle-close").start();}
 @Override public void onTimeout(int startId,int type){stopWarm();}
 @Override public void onDestroy(){timer.removeCallbacksAndMessages(null);if(lock!=null&&lock.isHeld())lock.release();if(!stopping){final long ticket=generation;new Thread(()->MusicGpuSession.expire(ticket),"madar-gpu-stop").start();}super.onDestroy();}
 @Override public IBinder onBind(Intent intent){return null;}
}
