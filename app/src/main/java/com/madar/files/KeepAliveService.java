package com.madar.files;

import android.app.*;
import android.content.*;
import android.os.*;

/**
 * Keeps the app's own work (understanding a request, preparing a motion job, document steps) running
 * when the owner leaves the app: a quiet foreground notification while that work lasts, then it stops
 * by itself. Long jobs already have their own services; this covers the work done in the screen.
 */
public final class KeepAliveService extends Service {
 static volatile boolean running;
 interface Check{boolean busy();}
 static volatile Check check=()->false;
 static void start(Context c,Check busy){check=busy;if(running)return;try{androidx.core.content.ContextCompat.startForegroundService(c,new Intent(c,KeepAliveService.class));}catch(Exception ignored){}}
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  NotificationManager n=getSystemService(NotificationManager.class);n.createNotificationChannel(new NotificationChannel("madar-work","شغل مدار في الخلفية",NotificationManager.IMPORTANCE_LOW));
  PendingIntent open=PendingIntent.getActivity(this,733,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  Notification note=new Notification.Builder(this,"madar-work").setSmallIcon(R.drawable.ic_launcher).setContentTitle("مدار يكمل طلبك").setContentText("ارجع للتطبيق متى ما تبي؛ الشغل مستمر").setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).build();
  try{startForeground(736,note);}catch(Exception refused){stopSelf();return START_NOT_STICKY;}
  if(running)return START_NOT_STICKY;running=true;
  new Thread(()->{try{Thread.sleep(1500);while(check.busy())Thread.sleep(1500);}catch(InterruptedException ignored){}running=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();},"madar-keep-alive").start();
  return START_NOT_STICKY;
 }
 @Override public void onDestroy(){running=false;super.onDestroy();}
 @Override public IBinder onBind(Intent intent){return null;}
}
