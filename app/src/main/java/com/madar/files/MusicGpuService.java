package com.madar.files;
import android.app.*;import android.content.*;import android.os.*;
/** Always enters foreground before handling stop/idle, including rapid task handoffs. */
public final class MusicGpuService extends Service {
 private final Handler timer=new Handler(Looper.getMainLooper());private PowerManager.WakeLock lock;private long generation;
 @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("gpu_warm","جلسة GPU الجاهزة",NotificationManager.IMPORTANCE_LOW));}
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  generation=MusicGpuSession.generation();final long ticket=generation;long idleMs=MusicGpuSession.remainingIdle();boolean busy=MusicGpuSession.busy();
  PendingIntent stop=PendingIntent.getService(this,194,new Intent(this,MusicGpuService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  String message=busy?"معالجة GPU جارية؛ يمكنك إيقاف العملية من هنا":"جاهز لإعادة الاستخدام حتى "+Math.max(1,(idleMs+59999)/60000)+" دقيقة؛ بعدها تُغلق الجلسة تلقائيًا";
  Notification n=new Notification.Builder(this,"gpu_warm").setSmallIcon(R.drawable.ic_launcher).setContentTitle("مدار · جلسة GPU").setContentText(message).setOngoing(true).addAction(new Notification.Action.Builder(null,"إيقاف GPU الآن",stop).build()).build();
  startForeground(194,n); // Must happen even when a queued start arrives after a lease ended.
  timer.removeCallbacksAndMessages(null);if(lock!=null&&lock.isHeld())lock.release();
  if(!MusicGpuSession.held()){stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);return START_NOT_STICKY;}
  if(intent!=null&&"stop".equals(intent.getAction()))new Thread(()->MusicGpuSession.stopGeneration(ticket),"madar-gpu-stop").start();
  else if(!busy){lock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MadarFiles:GpuIdle");lock.acquire(Math.max(1,idleMs)+10000);timer.postDelayed(()->new Thread(()->MusicGpuSession.expire(ticket),"madar-gpu-idle-close").start(),Math.max(1,idleMs));}
  return START_NOT_STICKY;
 }
 @Override public void onTimeout(int startId,int type){final long ticket=generation;new Thread(()->MusicGpuSession.stopGeneration(ticket),"madar-gpu-timeout").start();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
 @Override public void onDestroy(){timer.removeCallbacksAndMessages(null);if(lock!=null&&lock.isHeld())lock.release();final long ticket=generation;new Thread(()->MusicGpuSession.stopGeneration(ticket),"madar-gpu-service-end").start();super.onDestroy();}
 @Override public IBinder onBind(Intent intent){return null;}
}
