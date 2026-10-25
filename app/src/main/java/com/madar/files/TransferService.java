package com.madar.files;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground transfers survive navigating away from the editor and hold a bounded wake lock. */
public class TransferService extends Service {
 static volatile boolean active;static volatile String lastEvent;private static JSONObject pending;private static String grant;
 private final AtomicBoolean cancel=new AtomicBoolean();private DriveShares drive;private Thread worker;private PowerManager.WakeLock wake;private Handler timer;
 static synchronized void start(Context c,JSONObject job,String token)throws Exception{if(active||pending!=null)throw new IOException("انتظر اكتمال النقل الحالي");pending=job;grant=token;try{c.startForegroundService(new Intent(c,TransferService.class));}catch(Exception e){pending=null;grant=null;throw e;}}
 @Override public void onCreate(){super.onCreate();NotificationChannel ch=new NotificationChannel("madar-transfer","تحميل ورفع الملفات",NotificationManager.IMPORTANCE_LOW);getSystemService(NotificationManager.class).createNotificationChannel(ch);startForeground(731,new Notification.Builder(this,"madar-transfer").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("مدار · نقل الملفات").setContentText("جارٍ التجهيز").setOngoing(true).build());}
 @Override public int onStartCommand(Intent intent,int flags,int id){if(intent!=null&&"cancel".equals(intent.getAction())){cancel.set(true);LinkDownloads.cancel();if(drive!=null)drive.cancel();if(worker!=null)worker.interrupt();return START_NOT_STICKY;}if(active)return START_NOT_STICKY;JSONObject job;String token;synchronized(TransferService.class){job=pending;token=grant;pending=null;grant=null;}if(job==null){stopSelf();return START_NOT_STICKY;}active=true;cancel.set(false);timer=new Handler(Looper.getMainLooper());timer.postDelayed(()->{cancel.set(true);LinkDownloads.cancel();if(drive!=null)drive.cancel();if(worker!=null)worker.interrupt();},20*60*1000L);wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Madar:Transfer");wake.acquire(21*60*1000L);
  worker=new Thread(()->{try{String action=job.getString("action");emit(new JSONObject().put("type","transferProgress").put("progress",0).put("message","جارٍ تجهيز النقل"));
   if(action.equals("linkInspect")){JSONObject info=LinkDownloads.inspect(this,job.getString("url"),cancel,this::progress);emit(info.put("type","linkInfo"));}
   else if(action.equals("linkDownload")){File result=LinkDownloads.download(this,job,cancel,this::progress);emit(FilesUtil.info(result).put("type","result"));}
   else if(action.equals("linkUpdate")){LinkDownloads.update(this);emit(new JSONObject().put("type","linkUpdated"));}
   else if(action.equals("driveUpload")||action.equals("driveDelete")){if(token==null)throw new IOException("اربط حساب Google أولًا");try(DriveShares session=new DriveShares(this,token,cancel)){drive=session;if(action.equals("driveDelete")){session.delete(job.getString("id"));emit(new JSONObject().put("type","driveFiles").put("files",DriveShares.list(this)));}else{File file=new File(job.getString("path"));String path=file.getCanonicalPath();if(!(path.startsWith(getCacheDir().getCanonicalPath()+File.separator)||path.startsWith(getFilesDir().getCanonicalPath()+File.separator)))throw new IOException("مسار الملف غير صالح");JSONObject share=session.upload(file,job.getJSONObject("info"),this::progress);File qr=QrTools.create(this,new JSONObject().put("qrType","link").put("qrValue",share.getString("url")).put("qrSize",job.optInt("qrSize",1024)));emit(new JSONObject().put("type","driveShared").put("share",share).put("result",FilesUtil.info(qr)));}}finally{drive=null;}}
  }catch(Exception|OutOfMemoryError e){try{emit(new JSONObject().put("type",cancel.get()?"cancelled":"error").put("message",cancel.get()?"أُوقف النقل":e instanceof OutOfMemoryError?"الذاكرة لا تكفي الآن؛ أغلق الأدوات الأخرى":job.optString("action").startsWith("link")?LinkDownloads.failure((Exception)e):e.getMessage()==null?"تعذر النقل":e.getMessage()));}catch(Exception ignored){}}
  finally{active=false;if(timer!=null)timer.removeCallbacksAndMessages(null);if(wake!=null&&wake.isHeld())wake.release();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}},"madar-transfer");worker.start();return START_NOT_STICKY;
 }
 private void progress(int percent,String text){try{emit(new JSONObject().put("type","transferProgress").put("progress",percent).put("message",text));}catch(Exception ignored){}}
 private void emit(JSONObject event){lastEvent=event.toString();sendBroadcast(new Intent(VideoExportService.EVENT).setPackage(getPackageName()).putExtra("event",lastEvent));}
 @Override public IBinder onBind(Intent i){return null;}
 @Override public void onDestroy(){if(active){cancel.set(true);LinkDownloads.cancel();if(drive!=null)drive.cancel();if(worker!=null)worker.interrupt();}if(timer!=null)timer.removeCallbacksAndMessages(null);if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
}
