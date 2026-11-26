package com.madar.files;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Playback metadata only. Original media is never replaced or re-exported. */
public class WatchCensorService extends Service {
 static volatile Job current;static volatile boolean active,starting;
 static final class Job {
  final String id=java.util.UUID.randomUUID().toString().replace("-",""),playbackId;final PlayerInput input;final long durationMs;final int width,height;final boolean waitComplete;
  final AtomicBoolean cancel=new AtomicBoolean();final CensorTimeline timeline;
  /** "female" or "male": who is covered (anyone not yet clearly the other group is covered too). */
  volatile String target="female";volatile long requestedMs;volatile boolean done,failed,watching;volatile String stage="تجهيز الحجب السحابي",error="",warning="";
  Job(Context context,PlayerInput input,String playbackId,long position,long duration,int width,int height,boolean wait)throws IOException{this.input=input;this.playbackId=playbackId;requestedMs=position;durationMs=duration;this.width=width;this.height=height;waitComplete=wait;timeline=new CensorTimeline(new File(context.getCacheDir(),"madar_censor_masks_"+id),id);}
  boolean usable(){return !failed&&!cancel.get();}
  void dispose(){cancel.set(true);timeline.close();}
 }
 static synchronized Job start(Context context,PlayerInput input,String playbackId,long position,long duration,int width,int height,boolean complete)throws Exception{return start(context,input,playbackId,position,duration,width,height,complete,"female");}
 static synchronized Job start(Context context,PlayerInput input,String playbackId,long position,long duration,int width,int height,boolean complete,String target)throws Exception{
  if(!"female".equals(target)&&!"male".equals(target))throw new IOException("اختر من يُحجب");
  if(active||starting||MusicGpuSession.busy())throw new IOException("انتظر اكتمال عملية GPU الحالية أو أوقفها من إشعارها أولًا");
  if((WatchMusicService.active||WatchMusicService.starting)&&WatchMusicService.current!=null&&WatchMusicService.current.cloud)throw new IOException("انتظر اكتمال الفصل السحابي الحالي أو أوقفه من إشعاره أولًا");
  if(VideoExportService.active||FileProcessingService.active||FileProcessingService.starting||ImageBatchService.active||ImageBatchService.starting||TransferService.active)throw new IOException("انتظر اكتمال المعالجة الحالية أولًا");
  if(!ColabCredentials.ready()&&!MusicGpuSession.available(context))throw new IOException("اتصل بحساب Colab أولًا");
  if(duration<=0||position<0||position>=duration||width<=0||height<=0)throw new IOException("انتظر تحميل الفيديو ومدته أولًا");
  Job old=current;if(old!=null&&!old.watching)old.dispose();
  Job job=new Job(context,input,playbackId,position,duration,width,height,complete);job.target=target;current=job;starting=true;
  Intent intent=new Intent(context,WatchCensorService.class).putExtra("job",job.id).setData(input.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("الفيديو للحجب",input.uri));
  try{context.startForegroundService(intent);return job;}catch(RuntimeException error){starting=false;current=old;job.dispose();throw error;}
 }
 static void release(Job job){if(job==null)return;job.watching=false;job.cancel.set(true);if(!job.done)MusicGpuSession.cancelOwner(job.cancel);if(job.done)job.timeline.close();}
 private Job job;private int startId;private NotificationManager manager;private final Handler main=new Handler(Looper.getMainLooper());private PowerManager.WakeLock wake;
 @Override public void onCreate(){super.onCreate();manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("watch_censor","حجب الأشخاص أثناء المشاهدة",NotificationManager.IMPORTANCE_LOW));}
 private Notification notification(){
  Intent player=new Intent(this,PlayerActivity.class).putExtra("playbackSession",job.playbackId).putExtra("censorJob",job.id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
  PendingIntent open=PendingIntent.getActivity(this,86,player,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  PendingIntent cancel=PendingIntent.getService(this,87,new Intent(this,WatchCensorService.class).setAction("cancel").putExtra("job",job.id),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  return new Notification.Builder(this,"watch_censor").setSmallIcon(R.drawable.ic_launcher).setContentTitle("مدار · حجب الأشخاص · "+("male".equals(job.target)?"الرجال":"النساء")).setContentText(job.stage).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setProgress(100,0,true).addAction(new Notification.Action.Builder(null,"إيقاف التحليل",cancel).build()).build();
 }
 @Override public int onStartCommand(Intent intent,int flags,int id){
  startId=id;
  if(intent==null){stopSelfResult(id);return START_NOT_STICKY;}
  if("cancel".equals(intent.getAction())){if(job!=null&&job.id.equals(intent.getStringExtra("job"))){job.cancel.set(true);MusicGpuSession.cancelOwner(job.cancel);update("إيقاف التحليل وإغلاق مهمته في Colab…");}if(job==null)stopSelfResult(id);return START_NOT_STICKY;}
  if(active)return START_NOT_STICKY;job=current;startId=id;
  if(job==null||!job.id.equals(intent.getStringExtra("job"))){starting=false;stopSelfResult(id);return START_NOT_STICKY;}
  try{
   if(Build.VERSION.SDK_INT>=35)startForeground(86,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);else startForeground(86,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
   active=true;starting=false;wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MadarFiles:WatchCensor");wake.acquire(5L*60*60*1000);new Thread(this::process,"madar-watch-censor").start();
  }catch(Exception error){starting=false;job.failed=true;job.done=true;job.error=job.stage="تعذر بدء الحجب في الخلفية";if(!job.watching)job.timeline.close();stopSelfResult(id);}return START_NOT_STICKY;
 }
 private void update(String message){job.stage=message;main.post(()->{if(active)manager.notify(86,notification());});}
 private void process(){String failure=null;
  try{
   job.warning=CensorAnalysis.run(this,job.input,job.id,job.durationMs,job.width,job.height,job.target,job.timeline,job.cancel,()->job.requestedMs,(p,m)->update(m));
  }catch(java.util.concurrent.CancellationException cancelled){failure="توقف التحليل؛ المشاهد غير المجهزة تبقى مغطاة";}
  catch(Exception|OutOfMemoryError|UnsatisfiedLinkError error){failure=job.cancel.get()?"توقف التحليل؛ المشاهد غير المجهزة تبقى مغطاة":"تعذر استكمال الحجب: "+(error.getMessage()==null?"أعد المحاولة":error.getMessage());}
  finally{
   job.error=failure==null?"":failure;job.failed=failure!=null;job.done=true;job.stage=failure==null?"اكتمل تحليل الحجب":failure;if(!job.watching)job.timeline.close();
   main.post(()->{active=false;starting=false;if(wake!=null&&wake.isHeld())wake.release();stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);});
  }
 }
 static String stamp(long ms){return String.format(java.util.Locale.US,"%d:%02d",ms/60000,ms/1000%60);}
 @Override public void onTimeout(int id,int type){if(job!=null){job.cancel.set(true);job.error="أوقف النظام تحليل الحجب";MusicGpuSession.cancelOwner(job.cancel);}stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(id);}
 @Override public void onDestroy(){if(job!=null&&!job.done){job.cancel.set(true);MusicGpuSession.cancelOwner(job.cancel);}super.onDestroy();}
 @Override public IBinder onBind(Intent intent){return null;}
}
