package com.madar.files;
import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Image and document work lives independently of the visible Activity. */
public class FileProcessingService extends Service {
 static volatile boolean active,starting;static volatile String lastEvent;
 private final AtomicBoolean cancel=new AtomicBoolean();private Thread worker;private PowerManager.WakeLock wake;
 static synchronized void start(Context c,File input,JSONObject options)throws Exception{
  if(active||starting)throw new IOException("انتظر اكتمال معالجة الملف");starting=true;lastEvent=null;
  try{c.startForegroundService(new Intent(c,FileProcessingService.class).putExtra("input",input.getAbsolutePath()).putExtra("options",options.toString()));}catch(Exception e){starting=false;throw e;}
 }
 private Notification notification(String text){Intent launch=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);PendingIntent open=PendingIntent.getActivity(this,734,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return new Notification.Builder(this,"madar-files-work").setSmallIcon(com.madar.files.R.drawable.ic_launcher).setContentTitle("مدار · معالجة ملفك").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null,"إلغاء",PendingIntent.getService(this,735,new Intent(this,FileProcessingService.class).setAction("cancel"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build()).build();}
 @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("madar-files-work","معالجة الصور والمستندات",NotificationManager.IMPORTANCE_LOW));}
 @Override public int onStartCommand(Intent intent,int flags,int id){if(intent!=null&&"cancel".equals(intent.getAction())){cancel.set(true);return START_NOT_STICKY;}if(active)return START_NOT_STICKY;starting=false;if(intent==null){stopSelf();return START_NOT_STICKY;}
  startForeground(734,notification("جارٍ تجهيز الملف"));active=true;cancel.set(false);wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Madar:FileWork");wake.acquire(60*60*1000L);
  worker=new Thread(()->{try{File input=new File(intent.getStringExtra("input"));String path=input.getCanonicalPath();if(!input.isFile()||!(path.startsWith(getCacheDir().getCanonicalPath()+File.separator)||AssistantFiles.owns(this,input)))throw new IOException("ملف المصدر غير متاح");JSONObject o=new JSONObject(intent.getStringExtra("options"));String kind=o.getString("kind");boolean documentTool=java.util.Arrays.asList("ocr","text","word","layoutWord").contains(o.optString("tool"));progress("جارٍ معالجة الملف");
                String tool=o.optString("tool");
                if(documentTool&&o.optString("documentMode","local").equals("gemini")){
                    boolean imageWord=kind.equals("image")&&!o.optString("ocrOutput","text").equals("text");CloudDocuments.Result result=CloudDocuments.process(this,input,kind.equals("pdf"),kind.equals("image")?1:o.optInt("first",1),kind.equals("image")?1:o.optInt("last",1),tool.equals("layoutWord")||tool.equals("word")||imageWord,tool.equals("layoutWord")||imageWord&&o.optString("ocrOutput").equals("layout"),cancel,p->progress(p));if(result.word!=null)emit(FilesUtil.info(result.word).put("type","result"));else emit(new JSONObject().put("type","text").put("text",result.text));
                }
                else if(tool.equals("layoutWord")||tool.equals("word")){File f=LayoutWordExporter.export(this,input,o.optInt("first",1),o.optInt("last",1),o.optString("ocrLanguage","ara"),o.optString("pdfTextMode").equals("ocr"),tool.equals("layoutWord"));if(cancel.get()){f.delete();throw new java.util.concurrent.CancellationException();}emit(FilesUtil.info(f).put("type","result"));}
                else if(kind.equals("image")&&tool.equals("ocr")&&!o.optString("ocrOutput","text").equals("text")){File f=LayoutWordExporter.image(this,input,o.optString("ocrOutput").equals("layout"));if(cancel.get()){f.delete();throw new java.util.concurrent.CancellationException();}emit(FilesUtil.info(f).put("type","result"));}
                else if(tool.equals("ocr")||tool.equals("text")||tool.equals("word")){
                    String text=kind.equals("image")?TextProcessor.image(this,input,o.optString("ocrLanguage","ara")):TextProcessor.pdf(this,input,o.optInt("first",1),o.optInt("last",1),o.optString("ocrLanguage","ara"),o.optString("pdfTextMode").equals("ocr"),p->{ColabProbe.check(cancel);progress(p);});
                    ColabProbe.check(cancel);if(text.trim().isEmpty())throw new IOException("لم يُعثر على نص واضح في الملف");emit(new JSONObject().put("type","text").put("text",text));
                }else{File f=kind.equals("image")?ImageProcessor.export(this,input,o):PdfProcessor.export(this,input,o);emit(FilesUtil.info(f).put("type","result"));}

  }catch(java.util.concurrent.CancellationException e){safeEvent("cancelled",null);}catch(Exception|OutOfMemoryError e){safeEvent("error",e instanceof OutOfMemoryError?"الملف يتجاوز الذاكرة المتاحة؛ جرّب دقة أقل":e.getMessage()==null?"لم تكتمل معالجة الملف":e.getMessage());}finally{active=false;if(wake!=null&&wake.isHeld())wake.release();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}},"madar-file-processing");worker.start();return START_NOT_STICKY;
 }
 private void safeEvent(String type,String message){try{JSONObject e=new JSONObject().put("type",cancel.get()?"cancelled":type);if(message!=null)e.put("message",message);emit(e);}catch(Exception ignored){}}
 private void progress(String text){try{ColabProbe.check(cancel);emit(new JSONObject().put("type","workProgress").put("message",text));getSystemService(NotificationManager.class).notify(734,notification(text));}catch(org.json.JSONException ignored){}}
 private void emit(JSONObject event){if(cancel.get()&&!event.optString("type").equals("cancelled")){if(event.optString("type").equals("result"))try{FilesUtil.resolve(this,event.getString("name")).delete();}catch(Exception ignored){}throw new java.util.concurrent.CancellationException();}try{event.put("at",System.currentTimeMillis());}catch(JSONException ignored){}lastEvent=event.toString();if(java.util.Arrays.asList("result","text","subtitles","driveShared").contains(event.optString("type")))WorkNotifications.complete(this,742);sendBroadcast(new Intent(VideoExportService.EVENT).setPackage(getPackageName()).putExtra("event",lastEvent));}
 @Override public IBinder onBind(Intent i){return null;}
 @Override public void onDestroy(){cancel.set(true);if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
}
