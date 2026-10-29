package com.madar.files;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Playback-only processing. Long media is decoded in bounded windows, never re-exported. */
public class WatchMusicService extends Service {
 static volatile boolean active,starting;static volatile Job current;private static volatile Job processing;
 static final class Job {
  final String id=java.util.UUID.randomUUID().toString(),identity,title;final Uri uri;final File source;final boolean ownsSource,cloud;final java.util.Map<String,String> headers;final String mime;final long startUs,durationUs;
  MediaHttp.Session http;final AtomicBoolean cancel=new AtomicBoolean();final LiveSeparation.Session clean;volatile String stage="تجهيز الصوت المنظّف",warning="";volatile boolean sourceReady,sourceTransferred;
  Job(Uri uri,File source,boolean owns,String identity,String title,java.util.Map<String,String> headers,String mime,long start,long duration,boolean cloud){this.uri=uri;this.source=source;ownsSource=owns;this.identity=identity;this.title=title;this.headers=headers;this.mime=mime;http=new MediaHttp.Session(uri,headers);startUs=start;durationUs=duration;this.cloud=cloud;sourceReady=!owns;clean=new LiveSeparation.Session(source,true);clean.offsetUs=start;}
  boolean usable(){return !cancel.get()&&!clean.failed&&(!sourceReady||source.isFile())&&(clean.frames==0||clean.pcm!=null&&clean.pcm.isFile());}
 }
 static boolean busy(){return active||starting||VideoExportService.active||FileProcessingService.active||FileProcessingService.starting||ImageBatchService.active||ImageBatchService.starting||TransferService.active;}
 static synchronized Job start(Context c,Uri uri,File file,String identity,String title,java.util.Map<String,String> headers,String mime,long positionMs,long durationMs,boolean cloud)throws Exception{
  return start(c,uri,file,identity,title,headers,mime,positionMs,durationMs,cloud,null);
 }
 static synchronized Job start(Context c,Uri uri,File file,String identity,String title,java.util.Map<String,String> headers,String mime,long positionMs,long durationMs,boolean cloud,MediaHttp.Session http)throws Exception{
  if(busy())throw new IOException("انتظر انتهاء المعالجة الحالية أو أوقفها أولًا");
  if(durationMs<=0||positionMs<0||positionMs>=durationMs)throw new IOException("انتظر تحميل مدة المقطع أو اختر موضعًا قبل نهايته");
  if(cloud&&!ColabCredentials.ready()&&!MusicGpuSession.available(c))throw new IOException("اتصل بحساب Colab أولًا");
  Job old=current;boolean inherited=old!=null&&file!=null&&old.ownsSource&&file.equals(old.source);if(inherited)old.sourceTransferred=true;if(old!=null&&!old.clean.watching)dispose(old);if(old==null){File[] orphaned=c.getCacheDir().listFiles(f->f.getName().startsWith("madar_watch_input_")||f.getName().startsWith("madar_watch_clean_"));if(orphaned!=null)for(File orphan:orphaned)orphan.delete();}
  boolean owned=file==null||inherited;File source=file==null?File.createTempFile("madar_watch_input_",".media",c.getCacheDir()):file;
  Job job=new Job(uri,source,owned,identity,title,headers,mime,positionMs*1000,durationMs*1000,cloud);if(file!=null)job.sourceReady=true;if(http!=null)job.http=http;job.clean.buffer=new WatchBuffer((durationMs-positionMs)/1000.0,true);job.clean.waitForComplete=c.getSharedPreferences("player",0).getBoolean("watch_complete",false);current=job;starting=true;
  try{Intent intent=new Intent(c,WatchMusicService.class).putExtra("job",job.id).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("الصوت للمشاهدة",uri));c.startForegroundService(intent);return job;}catch(RuntimeException error){starting=false;current=old;if(inherited){old.sourceTransferred=false;job.sourceTransferred=true;}dispose(job);throw error;}
 }
 static void dispose(Job job){if(job.clean.pcm!=null)job.clean.pcm.delete();if(job.ownsSource&&!job.sourceTransferred)job.source.delete();}
 static void release(Job job){job.clean.watching=false;if(job.clean.done&&current!=job)dispose(job);}
 private Job job;private int jobStartId;private NotificationManager notifications;private PowerManager.WakeLock lock;private final Handler main=new Handler(Looper.getMainLooper());
 @Override public void onCreate(){super.onCreate();notifications=getSystemService(NotificationManager.class);notifications.createNotificationChannel(new NotificationChannel("watch_music","إزالة الموسيقى أثناء المشاهدة",NotificationManager.IMPORTANCE_LOW));}
 private Notification notification(){
  Intent player=new Intent(this,PlayerActivity.class).putExtra("watchJob",job.id).putExtra("path",job.source.getPath()).putExtra("title",job.title).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
  PendingIntent open=PendingIntent.getActivity(this,81,player,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  PendingIntent cancel=PendingIntent.getService(this,82,new Intent(this,WatchMusicService.class).setAction("cancel").putExtra("job",job.id),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  return new Notification.Builder(this,"watch_music").setSmallIcon(R.drawable.ic_launcher).setContentTitle("مدار · الصوت المنظّف أثناء المشاهدة").setContentText(job.stage).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setProgress(100,0,true).addAction(new Notification.Action.Builder(null,"إيقاف الفصل",cancel).build()).build();
 }
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  if(intent==null){stopSelf();return START_NOT_STICKY;}
  if("cancel".equals(intent.getAction())){if(job!=null&&job.id.equals(intent.getStringExtra("job"))){job.cancel.set(true);update("إلغاء الفصل وإغلاق مهمته في Colab…");MusicGpuSession.cancelOwner(job.cancel);}if(job==null)stopSelf();return START_NOT_STICKY;}
  if(active)return START_NOT_STICKY;job=current;jobStartId=startId;
  if(job==null||!job.id.equals(intent.getStringExtra("job"))){starting=false;stopSelf();return START_NOT_STICKY;}
  try{if(Build.VERSION.SDK_INT>=35)startForeground(81,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);else startForeground(81,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);active=true;processing=job;starting=false;lock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MadarFiles:WatchMusic");lock.acquire(5L*60*60*1000);new Thread(this::process,"madar-watch-music").start();}
  catch(Exception error){starting=false;LiveSeparation.end(job.clean,error.getMessage());active=false;stopSelf();}return START_NOT_STICKY;
 }
 private void update(String text){job.stage=text;main.post(()->{if(active)notifications.notify(81,notification());});}
 private void process(){final Job job=this.job;final int startId=jobStartId;String error=null;ColabCredentials.Grant grant=job.cloud?ColabCredentials.take():null;ColabMusic.Session cloudSession=null;
  try{
   if(job.ownsSource&&!job.sourceReady&&!ExternalMedia.network(job.uri)){update("تجهيز نسخة خاصة من المقطع · الأصل محفوظ");try(InputStream in=getContentResolver().openInputStream(job.uri);OutputStream out=new FileOutputStream(job.source)){if(in==null)throw new IOException("تعذر قراءة المقطع");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){MusicSeparator.check(job.cancel);if(job.source.getUsableSpace()<16L*1024*1024)throw new IOException("المساحة لا تكفي لتجهيز المقطع");out.write(b,0,n);}}job.sourceReady=true;}
   MusicSeparator.check(job.cancel);if(!ExternalMedia.network(job.uri))try(MediaMetadataRetriever metadata=new MediaMetadataRetriever()){metadata.setDataSource(job.source.getPath());if(!"yes".equals(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)))throw new IOException("المقطع لا يحتوي على صوت");}
   File aggregate=File.createTempFile("madar_watch_clean_",".pcm",getCacheDir());LiveSeparation.attach(job.clean,aggregate,job.startUs);
   if(job.cloud){cloudSession=MusicGpuSession.acquire(this,grant,job.cancel,(p,m)->update(m));cloudSession.startWarmup();}
   try(OutputStream out=new FileOutputStream(aggregate)){
    int windowIndex=0;double transferSecondsPerMedia=0;for(long next=job.startUs;next<job.durationUs;){
     MusicSeparator.check(job.cancel);long cycleBegan=SystemClock.elapsedRealtime();WatchWindows window=WatchWindows.forWatch(next,job.durationUs,job.cloud,windowIndex);AudioPipeline.Decoded decoded=null;File processed=null;
     try{
      if(job.clean.buffer!=null)job.clean.buffer.planGap(transferSecondsPerMedia*(window.decodeEnd-window.decodeStart)/1e6);long decodeAt=SystemClock.elapsedRealtime();
      update("فك الصوت من موضع المشاهدة");decoded=ExternalMedia.network(job.uri)?RemoteWatchAudio.decode(this,job.http,job.mime,window.decodeStart,window.decodeEnd,job.cancel):AudioPipeline.decodeRange(this,job.source,window.decodeStart,window.decodeEnd,job.cancel,(p,m)->update(m));
      long decodeMs=SystemClock.elapsedRealtime()-decodeAt;if(job.cloud)cloudSession.diagnostic.measure("DECODE",decodeMs,decoded.pcm.length(),decoded.pcm.length()/4);
      long skip=Math.max(0,Math.round((window.coreStart-decoded.offsetUs)*44100.0/1000000)),count=Math.round((window.coreEnd-window.coreStart)*44100.0/1000000),base=job.clean.frames;
      if(base==0)job.clean.offsetUs=decoded.offsetUs+Math.round(skip*1000000.0/44100);
      LiveSeparation.Session part=new LiveSeparation.Session(job.source,true);
      MusicSeparator.Progress progress=(p,m)->{try{appendPrefix(part,job.clean,out,base,skip,count);update(m);}catch(IOException e){throw new java.io.UncheckedIOException(e);}};
      if(job.cloud){ColabMusic.Result result=cloudSession.run(decoded,progress,part);processed=result.pcm;}else processed=MusicSeparator.separate(this,decoded,job.cancel,progress,part);
      appendPrefix(part,job.clean,out,base,skip,count);MusicSeparator.check(job.cancel);
      if(job.clean.frames==base)throw new IOException("لم يُنتج الفصل صوتًا قابلًا للمشاهدة");
      if(window.coreEnd<job.durationUs&&job.clean.frames-base<count)throw new IOException("جزء الصوت غير مكتمل");
      long cycleMs=SystemClock.elapsedRealtime()-cycleBegan-(job.cloud?cloudSession.lastColdStartMs:0);
      if(job.cloud){cycleMs=Math.max(cycleMs,decodeMs+cloudSession.lastUploadMs+cloudSession.lastProcessMs);transferSecondsPerMedia=(decodeMs+cloudSession.lastUploadMs)/1000.0/((window.decodeEnd-window.decodeStart)/1e6);}
      if(job.clean.buffer!=null)job.clean.buffer.completeCycle((job.clean.frames-base)/44100.0,Math.max(1,cycleMs)/1000.0);next=window.coreEnd;windowIndex++;
     }finally{if(decoded!=null)decoded.pcm.delete();if(processed!=null)processed.delete();}
    }
   }
  }catch(java.util.concurrent.CancellationException cancelled){error="أوقفت إزالة الموسيقى";}catch(Exception|OutOfMemoryError|UnsatisfiedLinkError failure){error="تعذر تنظيف الصوت: "+(failure.getMessage()==null?"جرّب مقطعًا آخر":failure.getMessage());}
  finally{if(cloudSession!=null){if(error!=null)cloudSession.healthy=false;cloudSession.close();if(job.cancel.get())MusicGpuSession.awaitCancelled(job.cancel,(p,m)->update(m));job.warning=cloudSession.warning;}LiveSeparation.end(job.clean,error);job.stage=error==null?"اكتمل تنظيف الصوت"+(job.warning.isEmpty()?"":" · "+job.warning):error;main.post(()->{main.removeCallbacksAndMessages(null);if(lock!=null&&lock.isHeld())lock.release();if(processing==job){processing=null;active=false;starting=false;}stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);});}
 }
 /** Copy only complete new core frames; context is never duplicated at window boundaries. */
 static void appendPrefix(LiveSeparation.Session part,LiveSeparation.Session target,OutputStream out,long base,long skip,long count)throws IOException{
  if(part.pcm==null)return;long copied=target.frames-base,available=Math.min(count,Math.max(0,part.frames-skip)),keep=available-copied;if(keep<=0)return;
  try(RandomAccessFile in=new RandomAccessFile(part.pcm,"r")){in.seek((skip+copied)*4);byte[] bytes=new byte[65536];while(keep>0){int frames=(int)Math.min(keep,bytes.length/4);in.readFully(bytes,0,frames*4);out.write(bytes,0,frames*4);copied+=frames;keep-=frames;}out.flush();LiveSeparation.publish(target,base+copied);}
 }
 @Override public void onTimeout(int startId,int type){if(job!=null){job.cancel.set(true);job.stage="أوقف النظام جلسة الفصل؛ يمكن بدء جلسة جديدة";}stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
 @Override public void onDestroy(){if(processing==job&&job!=null){job.cancel.set(true);MusicGpuSession.cancelOwner(job.cancel);}super.onDestroy();}
 @Override public IBinder onBind(Intent intent){return null;}
}
