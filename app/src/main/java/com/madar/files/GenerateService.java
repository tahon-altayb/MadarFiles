package com.madar.files;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One generation at a time (image on T4 or clip on L4) in a foreground service, so leaving the app
 * never stops it. Progress and the finished file reach the chat that asked for it; the last state is
 * kept so the chat catches up when the owner comes back.
 */
public final class GenerateService extends Service {
 static final String EVENT="com.madar.files.GENERATE";
 static volatile boolean active;
 private final AtomicBoolean cancel=new AtomicBoolean();
 private NotificationManager notifications;private PowerManager.WakeLock wake;
 private String stage="تجهيز التوليد",thread="",task="",mode="image";private int percent;

 static void start(Context c,JSONObject request){androidx.core.content.ContextCompat.startForegroundService(c,new Intent(c,GenerateService.class).putExtra("request",request.toString()));}
 static JSONObject last(Context c){try{return new JSONObject(c.getSharedPreferences("generate",0).getString("chatState","{}"));}catch(JSONException e){return new JSONObject();}}

 @Override public void onCreate(){super.onCreate();notifications=getSystemService(NotificationManager.class);notifications.createNotificationChannel(new NotificationChannel("madar-generate","التوليد بالذكاء",NotificationManager.IMPORTANCE_LOW));}

 private Notification notification(boolean ongoing){
  PendingIntent open=PendingIntent.getActivity(this,740,new Intent(this,MainActivity.class).putExtra("agentThread",thread).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  PendingIntent stop=PendingIntent.getService(this,741,new Intent(this,GenerateService.class).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  Notification.Builder b=new Notification.Builder(this,"madar-generate").setSmallIcon(R.drawable.ic_launcher).setContentTitle(mode.equals("video")?"مدار · توليد مقطع":"مدار · توليد صورة").setContentText(stage).setOnlyAlertOnce(true).setOngoing(ongoing).setContentIntent(open);
  if(ongoing)b.setProgress(100,percent,percent<=0).addAction(new Notification.Action.Builder(null,"إيقاف",stop).build());
  return b.build();
 }

 private void emit(String status,String message,JSONObject result){
  try{
   JSONObject e=new JSONObject().put("type","generateState").put("thread",thread).put("task",task).put("mode",mode).put("status",status).put("message",message).put("progress",percent);
   if(result!=null)e.put("result",result);
   getSharedPreferences("generate",0).edit().putString("chatState",e.toString()).apply();
   sendBroadcast(new Intent(EVENT).setPackage(getPackageName()).putExtra("event",e.toString()));
  }catch(Exception ignored){}
 }

 @Override public int onStartCommand(Intent intent,int flags,int startId){
  if(intent==null){stopSelf();return START_NOT_STICKY;}
  if("cancel".equals(intent.getAction())){cancel.set(true);stage="إيقاف التوليد وإغلاق جلسة Colab";if(active)notifications.notify(74,notification(true));else stopSelf();return START_NOT_STICKY;}
  if(active)return START_NOT_STICKY;
  final JSONObject request=parse(intent.getStringExtra("request"));if(request==null){stopSelf();return START_NOT_STICKY;}
  thread=request.optString("thread");task=request.optString("task");mode="video".equals(request.optString("mode"))?"video":"image";
  ColabCredentials.Grant grant=ColabCredentials.take();active=true;cancel.set(false);percent=0;stage="تجهيز وصف التوليد";
  startForeground(74,notification(true));
  wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Madar:Generate");wake.acquire(70*60*1000L);
  new Thread(()->{
   try{
    emit("running",stage,null);
    JSONObject prompt=GeneratePrompt.resolve(this,request.optString("idea"),request.optString("prompt"),mode,cancel);
    JSONObject job=new JSONObject().put("mode",mode).put("prompt",prompt.getString("prompt")).put("negative",prompt.optString("negative")).put("style",request.optString("style")).put("aspect",request.optString("aspect")).put("seconds",request.optDouble("seconds",5));
    File out=GenerateCloud.run(this,job,grant,cancel,(p,m)->{percent=p;stage=m;emit("running",m,null);notifications.notify(74,notification(true));});
    percent=100;stage=mode.equals("video")?"المقطع جاهز":"الصورة جاهزة";
    emit("done",stage,FilesUtil.info(out).put("prompt",prompt.getString("prompt")));
   }catch(java.util.concurrent.CancellationException stopped){emit("cancelled","أوقفت التوليد وأغلقت جلسة Colab",null);}
   catch(Exception error){if(cancel.get())emit("cancelled","أوقفت التوليد وأغلقت جلسة Colab",null);else emit("error",error.getMessage()==null?"تعذر التوليد":error.getMessage(),null);}
   finally{
    active=false;if(wake!=null&&wake.isHeld())wake.release();stopForeground(STOP_FOREGROUND_DETACH);
    notifications.notify(74,notification(false));stopSelf();
   }
  },"madar-generate").start();
  return START_NOT_STICKY;
 }
 private static JSONObject parse(String value){try{return new JSONObject(value);}catch(Exception e){return null;}}
 @Override public void onTimeout(int startId,int type){cancel.set(true);stopForeground(STOP_FOREGROUND_DETACH);stopSelf();}
 @Override public void onDestroy(){cancel.set(true);if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
 @Override public IBinder onBind(Intent intent){return null;}
}
