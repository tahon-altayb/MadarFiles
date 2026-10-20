package com.madar.files;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
public class ImageBatchService extends Service {
 static volatile boolean active,starting;static volatile String lastEvent;
 private final AtomicBoolean pause=new AtomicBoolean();private Thread worker;private PowerManager.WakeLock wake;
 private Notification notification(String message){PendingIntent open=PendingIntent.getActivity(this,30,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);PendingIntent stop=PendingIntent.getService(this,31,new Intent(this,ImageBatchService.class).setAction("pause"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this,"image-batch").setSmallIcon(R.drawable.ic_launcher).setContentTitle("مدار · معالجة الصور").setContentText(message).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"إيقاف بعد الصورة الحالية",stop).build()).build();}
 @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("image-batch","معالجة الصور دفعة واحدة",NotificationManager.IMPORTANCE_LOW));}
 @Override public int onStartCommand(Intent intent,int flags,int id){
  if(intent!=null&&"pause".equals(intent.getAction())){pause.set(true);if(worker==null)stopSelf();return START_NOT_STICKY;}
  if(worker!=null)return START_NOT_STICKY;
  try{if(Build.VERSION.SDK_INT>=35)startForeground(8,notification("تجهيز الطابور"),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);else startForeground(8,notification("تجهيز الطابور"),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);active=true;starting=false;pause.set(false);wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MadarFiles:ImageBatch");wake.acquire(60L*60*1000);worker=new Thread(this::runQueue,"madar-image-batch");worker.start();}
  catch(Exception e){active=false;starting=false;publishError(e);stopSelf();}return START_NOT_STICKY;
 }
 private void runQueue(){JSONObject state=null;try{
  state=ImageBatchQueue.read(this);if(state==null)throw new IOException("لا يوجد طابور محفوظ");JSONObject options=ImageBatchQueue.validate(state.getJSONObject("options"));JSONArray jobs=state.getJSONArray("jobs");state.put("status","running");ImageBatchQueue.save(this,state);publish(state,true);
  long deadline=System.currentTimeMillis()+55L*60*1000;
  for(int i=0;i<jobs.length();i++){
   if(pause.get()||System.currentTimeMillis()>deadline)break;JSONObject job=jobs.getJSONObject(i);if(!job.optString("status").equals("pending"))continue;
   getSystemService(NotificationManager.class).notify(8,notification("الصورة "+(i+1)+" من "+jobs.length()));
   File result=null;try{File source=ImageBatchQueue.input(this,job.getString("input"));result=ImageProcessor.export(this,source,options);job.put("result",FilesUtil.info(result)).put("status","done");ImageBatchQueue.save(this,state);source.delete();}
   catch(Exception|OutOfMemoryError e){if(result!=null)result.delete();job.remove("result");job.put("status","failed").put("error",e instanceof OutOfMemoryError?"الصورة تتجاوز الذاكرة المتاحة":"تعذرت معالجة الصورة");ImageBatchQueue.save(this,state);}
   publish(state,true);
  }
  boolean pending=false;for(int i=0;i<jobs.length();i++)if(jobs.getJSONObject(i).optString("status").equals("pending"))pending=true;
  state.put("status",pending?"paused":"complete");ImageBatchQueue.save(this,state);publish(state,false);
 }catch(Exception e){publishError(e);}finally{active=false;starting=false;if(wake!=null&&wake.isHeld())wake.release();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}}
 private void publish(JSONObject state,boolean running)throws Exception{lastEvent=ImageBatchQueue.view(state,running).toString();sendBroadcast(new Intent(VideoExportService.EVENT).setPackage(getPackageName()).putExtra("event",lastEvent));}
 private void publishError(Exception error){try{lastEvent=new JSONObject().put("type","error").put("message","توقف طابور الصور؛ يمكنك استئنافه من أداة الدفعات. "+error.getMessage()).toString();sendBroadcast(new Intent(VideoExportService.EVENT).setPackage(getPackageName()).putExtra("event",lastEvent));}catch(JSONException ignored){}}
 @Override public void onTimeout(int startId,int type){pause.set(true);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
 @Override public void onDestroy(){pause.set(true);super.onDestroy();}
 @Override public IBinder onBind(Intent intent){return null;}
}
