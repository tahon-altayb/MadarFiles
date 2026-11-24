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
  final String id=java.util.UUID.randomUUID().toString(),identity,title;final Uri uri;final File source;final boolean ownsSource,cloud;final java.util.Map<String,String> headers;final String mime;String pipeline=MusicPipeline.LEGACY;int width,height;final long startUs,durationUs;
  final long began=SystemClock.elapsedRealtime();volatile long committedFrames;
  MediaHttp.Session http;final AtomicBoolean cancel=new AtomicBoolean();final LiveSeparation.Session clean;volatile String stage="تجهيز الصوت المنظّف",warning="";volatile boolean sourceReady,sourceTransferred;
  Job(Uri uri,File source,boolean owns,String identity,String title,java.util.Map<String,String> headers,String mime,long start,long duration,boolean cloud){this.uri=uri;this.source=source;ownsSource=owns;this.identity=identity;this.title=title;this.headers=headers;this.mime=mime;http=new MediaHttp.Session(uri,headers);startUs=start;durationUs=duration;this.cloud=cloud;sourceReady=!owns;clean=new LiveSeparation.Session(source,true);clean.coverage=new PcmRanges();}
  boolean usable(){return !cancel.get()&&!clean.failed&&(!sourceReady||source.isFile())&&(clean.frames==0||clean.pcm!=null&&clean.pcm.isFile());}
 }
 static boolean busy(){return active||starting||VideoExportService.active||FileProcessingService.active||FileProcessingService.starting||ImageBatchService.active||ImageBatchService.starting||TransferService.active;}
 static synchronized Job start(Context c,Uri uri,File file,String identity,String title,java.util.Map<String,String> headers,String mime,long positionMs,long durationMs,boolean cloud)throws Exception{
  return start(c,uri,file,identity,title,headers,mime,positionMs,durationMs,cloud,null);
 }
 static synchronized Job start(Context c,Uri uri,File file,String identity,String title,java.util.Map<String,String> headers,String mime,long positionMs,long durationMs,boolean cloud,MediaHttp.Session http)throws Exception{
  return start(c,uri,file,identity,title,headers,mime,positionMs,durationMs,cloud,http,c.getSharedPreferences("player",0).getBoolean("watch_complete",false));
 }
 static synchronized Job start(Context c,Uri uri,File file,String identity,String title,java.util.Map<String,String> headers,String mime,long positionMs,long durationMs,boolean cloud,MediaHttp.Session http,boolean complete)throws Exception{
  String profile=cloud?c.getSharedPreferences("player",0).getString("watch_pipeline",MusicPipeline.LEGACY):MusicPipeline.LEGACY;
  return start(c,uri,file,identity,title,headers,mime,positionMs,durationMs,cloud,http,complete,profile,0,0,false);
 }
 static synchronized Job start(Context c,Uri uri,File file,String identity,String title,java.util.Map<String,String> headers,String mime,long positionMs,long durationMs,boolean cloud,MediaHttp.Session http,boolean complete,String profile,int width,int height,boolean progressive)throws Exception{
  if(busy())throw new IOException("انتظر انتهاء المعالجة الحالية أو أوقفها أولًا");
  if(durationMs<=0||positionMs<0||positionMs>=durationMs)throw new IOException("انتظر تحميل مدة المقطع أو اختر موضعًا قبل نهايته");
  profile=cloud?MusicPipeline.checked(profile):MusicPipeline.LEGACY;
  if(MusicPipeline.AVCASS.equals(profile)&&(width<=0||height<=0))throw new IOException("اختر فيديو لميزة الكلام مع صوت المحيط");
  if(cloud&&!MusicGpuSession.hasAccess(c,profile))throw new IOException("اتصل بحساب Colab أولًا");
  Job old=current;boolean inherited=old!=null&&file!=null&&old.ownsSource&&file.equals(old.source);if(inherited)old.sourceTransferred=true;if(old!=null&&!old.clean.watching)dispose(old);if(old==null){File[] orphaned=c.getCacheDir().listFiles(f->f.getName().startsWith("madar_watch_input_")||f.getName().startsWith("madar_watch_clean_")||f.getName().startsWith("madar_watch_source_"));if(orphaned!=null)for(File orphan:orphaned)orphan.delete();}
  boolean owned=file==null||inherited;File source=file==null?File.createTempFile("madar_watch_input_",".media",c.getCacheDir()):file;
  Job job=new Job(uri,source,owned,identity,title,headers,mime,positionMs*1000,durationMs*1000,cloud);job.pipeline=profile;job.width=width;job.height=height;if(file!=null)job.sourceReady=true;if(http!=null)job.http=http;job.clean.buffer=new WatchBuffer(durationMs/1000.0,true,progressive,MusicPipeline.AVCASS.equals(profile)&&progressive?120:0);job.clean.waitForComplete=complete;job.clean.forceWaitForComplete=MusicPipeline.AVCASS.equals(profile);current=job;starting=true;
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
  jobStartId=startId;
  if(intent==null){stopSelfResult(jobStartId);return START_NOT_STICKY;}
  if("cancel".equals(intent.getAction())){if(job!=null&&job.id.equals(intent.getStringExtra("job"))){job.cancel.set(true);update("إلغاء الفصل وإغلاق مهمته في Colab…");MusicGpuSession.cancelOwner(job.cancel);}if(job==null)stopSelfResult(jobStartId);return START_NOT_STICKY;}
  if(active)return START_NOT_STICKY;job=current;jobStartId=startId;
  if(job==null||!job.id.equals(intent.getStringExtra("job"))){starting=false;stopSelfResult(jobStartId);return START_NOT_STICKY;}
  try{if(Build.VERSION.SDK_INT>=35)startForeground(81,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);else startForeground(81,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);active=true;processing=job;starting=false;lock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MadarFiles:WatchMusic");lock.acquire(5L*60*60*1000);new Thread(this::process,"madar-watch-music").start();}
  catch(Exception error){starting=false;LiveSeparation.end(job.clean,error.getMessage());active=false;stopSelfResult(jobStartId);}return START_NOT_STICKY;
 }
 private void update(String text){job.stage=MusicPipeline.AVCASS.equals(job.pipeline)?MusicRecovery.status(text,job.committedFrames,WatchPlan.frame(job.durationUs),SystemClock.elapsedRealtime()-job.began):text;main.post(()->{if(active)notifications.notify(81,notification());});}
 private void process(){final Job job=this.job;String error=null;ColabCredentials.Grant grant=job.cloud?ColabCredentials.take():null;MusicRecovery gpu=null;WatchSourceDownload download=null;
  try{
   if(job.cloud)gpu=new MusicRecovery(this,grant,job.cancel,(p,m)->update(m),job.pipeline);
   long sourceAt=SystemClock.elapsedRealtime();
   if(job.ownsSource&&!job.sourceReady&&!ExternalMedia.network(job.uri)){update("تجهيز نسخة خاصة من المقطع · الأصل محفوظ");try(InputStream in=getContentResolver().openInputStream(job.uri);OutputStream out=new FileOutputStream(job.source)){if(in==null)throw new IOException("تعذر قراءة المقطع");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){MusicSeparator.check(job.cancel);if(job.source.getUsableSpace()<16L*1024*1024)throw new IOException("المساحة لا تكفي لتجهيز المقطع");out.write(b,0,n);}}job.sourceReady=true;}
   MusicSeparator.check(job.cancel);if(!ExternalMedia.network(job.uri))try(MediaMetadataRetriever metadata=new MediaMetadataRetriever()){metadata.setDataSource(job.source.getPath());if(!"yes".equals(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)))throw new IOException("المقطع لا يحتوي على صوت");}
   File aggregate=File.createTempFile("madar_watch_clean_",".pcm",getCacheDir());LiveSeparation.attach(job.clean,aggregate,0);
   if(job.cloud)gpu.session.diagnostic.measure("SOURCE",SystemClock.elapsedRealtime()-sourceAt,0,0);
   boolean network=ExternalMedia.network(job.uri);
   boolean prefetchStarted=network&&!MusicPipeline.AVCASS.equals(job.pipeline);
   if(prefetchStarted)download=WatchSourceDownload.start(this,job.http,job.mime,gpu!=null?gpu.session.diagnostic:null);
   boolean localSnapshotUsed=false;
   try(RandomAccessFile out=new RandomAccessFile(aggregate,"rw")){
    boolean audiovisual=MusicPipeline.AVCASS.equals(job.pipeline);
    WatchPlan plan=new WatchPlan(job.startUs,job.durationUs,job.cloud,job.clean.coverage,audiovisual);double transferSecondsPerMedia=0;byte[] previousTail=null;WatchWindows window;Lookahead ahead=null;
    try{
    while((window=plan.next(job.clean.requestedFrame))!=null){
     MusicSeparator.check(job.cancel);long cycleBegan=SystemClock.elapsedRealtime();AudioPipeline.Decoded decoded=null;File processed=null,visual=null;MusicCloudJob.Staged staged=null;Lookahead mine=null;
     if(ahead!=null){
      // The next window was prepared (and uploaded) while the previous one separated. Use it only if the plan agrees.
      ahead.finish(job.cancel);
      if(WatchPlan.same(ahead.window,window)&&ahead.decoded!=null)mine=ahead;
      else{if(gpu!=null&&gpu.session!=null)gpu.session.diagnostic.record("MUSIC_LOOKAHEAD_DROPPED");ahead.release(gpu);}
      ahead=null;
     }
     try{
      if(job.clean.buffer!=null)job.clean.buffer.planGap(mine!=null?0:transferSecondsPerMedia*(window.decodeEnd-window.decodeStart)/1e6);long decodeAt=SystemClock.elapsedRealtime();
      ColabDiagnostics diagnostic=job.cloud?gpu.session.diagnostic:null;
      long decodeMs;
      File snapshot=download==null?null:download.completed();
      if(mine!=null){
       decoded=mine.decoded;visual=mine.visual;staged=mine.staged;decodeMs=mine.decodeMs;mine.decoded=null;mine.visual=null;mine.staged=null;
       if(diagnostic!=null)diagnostic.record("MUSIC_LOOKAHEAD_USED");
      }else{
      Runnable retry=()->{if(diagnostic!=null)diagnostic.record("WATCH_MUX_RETRY");update("إعادة تجهيز الجزء الحالي بعد مهلة قراءة الوسائط…");};
      update("تجهيز الصوت بالترتيب من البداية · "+(window.coreStart/1000000)+" ثانية");
      if(snapshot!=null){
       try{decoded=AudioPipeline.decodeRange(this,snapshot,window.decodeStart,window.decodeEnd,job.cancel,(p,m)->update(m));}
       catch(java.util.concurrent.CancellationException cancelled){throw cancelled;}
       catch(Exception unavailable){
        download.close();download=null;snapshot=null;
        if(diagnostic!=null)diagnostic.record("WATCH_SOURCE_FALLBACK");
       }
       if(snapshot!=null&&!localSnapshotUsed){
        localSnapshotUsed=true;
        if(diagnostic!=null)diagnostic.record("WATCH_SOURCE_LOCAL");
       }
      }
      if(decoded==null)decoded=network?RemoteWatchAudio.decode(this,job.http,job.mime,window.decodeStart,window.decodeEnd,job.cancel,retry):AudioPipeline.decodeRange(this,job.source,window.decodeStart,window.decodeEnd,job.cancel,(p,m)->update(m));
      if(diagnostic!=null)diagnostic.measure("DECODE",SystemClock.elapsedRealtime()-decodeAt,decoded.pcm.length(),decoded.pcm.length()/4);
      if(audiovisual){
       long visualAt=SystemClock.elapsedRealtime();
       update("تجهيز صورة المشهد لفصل الكلام والمؤثرات");
       visual=soundVisual(job,snapshot,decoded,window,job.cancel,retry);
       if(diagnostic!=null)diagnostic.measure("WATCH_VIDEO",SystemClock.elapsedRealtime()-visualAt,visual.length(),0);
      }
      decodeMs=SystemClock.elapsedRealtime()-decodeAt;
      }
      // Upload now on a warm session, so the window after it can be uploaded while this one separates.
      if(job.cloud&&audiovisual&&staged==null)staged=gpu.stage(decoded,visual,null,(p,m)->update(m));
      long skip=Math.max(0,Math.round((window.coreStart-decoded.offsetUs)*44100.0/1000000)),base=WatchPlan.frame(window.coreStart),count=WatchPlan.frame(window.coreEnd)-base;long[] copied={0};
      LiveSeparation.Session part=new LiveSeparation.Session(job.source,true);
      final byte[] join=previousTail;
      MusicSeparator.Progress progress=(p,m)->{try{copied[0]=appendWindow(part,job.clean,out,base,copied[0],skip,count,join);update(m);}catch(IOException e){throw new java.io.UncheckedIOException(e);}};
      if(job.cloud&&audiovisual){
       WatchWindows following=plan.following(window);
       if(following!=null)ahead=Lookahead.start(this,job,gpu,following,network,download==null?null:download.completed(),staged!=null);
      }
      try{
       if(job.cloud){ColabMusic.Result result=gpu.run(decoded,visual,progress,part,audiovisual?live->job.clean.forceWaitForComplete=!live:null,staged);staged=null;processed=result.pcm;}else processed=MusicSeparator.separate(this,decoded,job.cancel,progress,part);
      }finally{
       staged=null;
       // This window's batch now exists in order on Colab: the next one may take its number.
       if(ahead!=null)ahead.allowStage();
      }
      copied[0]=appendWindow(part,job.clean,out,base,copied[0],skip,count,join);MusicSeparator.check(job.cancel);
      if(audiovisual)previousTail=tailForJoin(part,skip+count);
      if(copied[0]<count){if(WatchPlan.frame(window.coreEnd)<WatchPlan.frame(job.durationUs))throw new IOException("جزء الصوت غير مكتمل");plan.trimEnd(base+copied[0]);}
      if(copied[0]==0&&job.clean.coverage.total()==0)throw new IOException("لم يُنتج الفصل صوتًا قابلًا للمشاهدة");
      long cycleMs=SystemClock.elapsedRealtime()-cycleBegan-(job.cloud?gpu.session.lastColdStartMs:0);
      if(job.cloud){
       if(mine==null){cycleMs=Math.max(cycleMs,decodeMs+gpu.session.lastUploadMs+gpu.session.lastProcessMs);transferSecondsPerMedia=(decodeMs+gpu.session.lastUploadMs)/1000.0/((window.decodeEnd-window.decodeStart)/1e6);}
       else transferSecondsPerMedia=0;
      }
      if(audiovisual&&copied[0]>0){job.committedFrames+=copied[0];gpu.committed(copied[0],WatchPlan.frame(job.durationUs));update("حُفظت الدفعة المفصولة");
       if(network&&!prefetchStarted){
        prefetchStarted=true;
        download=WatchSourceDownload.start(this,job.http,job.mime,gpu.session.diagnostic);
       }
      }
      if(job.clean.buffer!=null)job.clean.buffer.completeCycle(copied[0]/44100.0,Math.max(1,cycleMs)/1000.0);
     }finally{if(decoded!=null)decoded.pcm.delete();if(processed!=null)processed.delete();if(visual!=null)visual.delete();if(staged!=null&&gpu!=null)gpu.discard(staged);if(mine!=null)mine.release(gpu);}
    }
    }finally{if(ahead!=null){ahead.stop();ahead.finish(null);ahead.release(gpu);}}
   }
  }catch(java.util.concurrent.CancellationException cancelled){error="أوقفت إزالة الموسيقى";}catch(Exception|OutOfMemoryError|UnsatisfiedLinkError failure){
   boolean muxTimeout=RemoteWatchAudio.muxTimedOut(failure);
   if(gpu!=null&&gpu.session!=null){
    if(muxTimeout)gpu.session.diagnostic.record("WATCH_MUX_TIMEOUT");
    gpu.session.diagnostic.record("FAILED");
   }
   error=muxTimeout?"انتهت مهلة قراءة الوسائط في الجزء الحالي · 7002": "تعذر تنظيف الصوت: "+(failure.getMessage()==null?"جرّب مقطعًا آخر":failure.getMessage());
  }
  finally{if(download!=null)download.close();if(gpu!=null){if(error!=null&&gpu.session!=null)gpu.session.healthy=false;gpu.close();if(job.cancel.get())MusicGpuSession.awaitCancelled(job.cancel,(p,m)->update(m));if(gpu.session!=null)job.warning=gpu.session.warning;}LiveSeparation.end(job.clean,error);job.stage=error==null?"اكتمل تنظيف الصوت"+(job.warning.isEmpty()?"":" · "+job.warning):error;main.post(()->{main.removeCallbacksAndMessages(null);if(lock!=null&&lock.isHeld())lock.release();if(processing==job){processing=null;active=false;starting=false;}stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(jobStartId);});}
 }
 /** Write a bounded core at its original time, then publish only bytes already written. */
 /** Small video of the window for AV-CASS; falls back from the local snapshot to the original source. */
 static File soundVisual(WatchMusicService context,Job job,File snapshot,AudioPipeline.Decoded decoded,WatchWindows window,AtomicBoolean cancel,Runnable retry)throws Exception{
  long visualStart=decoded.offsetUs/1000,visualEnd=(window.decodeEnd+999)/1000;
  PlayerInput visualInput=snapshot==null?PlayerInput.processing(job):PlayerInput.processing(snapshot);
  try{return CensorVideoWindow.extractForSound(context,visualInput,visualStart,visualEnd,job.width,job.height,cancel,retry);}
  catch(IOException visualFailure){
   if(snapshot==null)throw visualFailure;
   return CensorVideoWindow.extractForSound(context,PlayerInput.processing(job),visualStart,visualEnd,job.width,job.height,cancel,retry);
  }
 }
 private File soundVisual(Job job,File snapshot,AudioPipeline.Decoded decoded,WatchWindows window,AtomicBoolean cancel,Runnable retry)throws Exception{return soundVisual(this,job,snapshot,decoded,window,cancel,retry);}
 /**
  * Voice + ambience: prepares the next window (audio, scene video, upload, queue) while the current one separates,
  * so the GPU goes from one batch to the next without waiting for the phone. Only order-safe steps run early:
  * the upload waits until the current window's batch exists, because Colab runs batches strictly by number.
  */
 static final class Lookahead {
  final WatchWindows window;final AtomicBoolean cancel=new AtomicBoolean();final java.util.concurrent.CountDownLatch mayStage=new java.util.concurrent.CountDownLatch(1);
  volatile AudioPipeline.Decoded decoded;volatile File visual;volatile MusicCloudJob.Staged staged;volatile long decodeMs;private Thread thread;
  /** When the current window was already queued early: the session that holds it. Staging on any other session could jump ahead of it. */
  private ColabMusic.Session expect;
  private Lookahead(WatchWindows window){this.window=window;}
  static Lookahead start(WatchMusicService context,Job job,MusicRecovery gpu,WatchWindows window,boolean network,File snapshot,boolean currentQueued){
   Lookahead ahead=new Lookahead(window);
   if(currentQueued){ahead.expect=gpu.session;ahead.mayStage.countDown();}
   ahead.thread=new Thread(()->ahead.prepare(context,job,gpu,network,snapshot),"madar-watch-lookahead");ahead.thread.setDaemon(true);ahead.thread.start();
   return ahead;
  }
  void allowStage(){mayStage.countDown();}
  void stop(){cancel.set(true);mayStage.countDown();}
  private void prepare(WatchMusicService context,Job job,MusicRecovery gpu,boolean network,File snapshot){
   ColabDiagnostics diagnostic=gpu.session==null?null:gpu.session.diagnostic;
   try{
    long at=SystemClock.elapsedRealtime();AudioPipeline.Decoded audio=null;File scene=null;
    try{
     if(snapshot!=null){
      try{audio=AudioPipeline.decodeRange(context,snapshot,window.decodeStart,window.decodeEnd,cancel,(p,m)->{});}
      catch(java.util.concurrent.CancellationException stopped){throw stopped;}
      catch(Exception unavailable){audio=null;snapshot=null;}
     }
     if(audio==null)audio=network?RemoteWatchAudio.decode(context,job.http,job.mime,window.decodeStart,window.decodeEnd,cancel,()->{}):AudioPipeline.decodeRange(context,job.source,window.decodeStart,window.decodeEnd,cancel,(p,m)->{});
     check(job);
     scene=soundVisual(context,job,snapshot,audio,window,cancel,()->{});
     check(job);
     long spent=SystemClock.elapsedRealtime()-at;
     if(diagnostic!=null){diagnostic.measure("DECODE",spent,audio.pcm.length(),audio.pcm.length()/4);diagnostic.record("MUSIC_LOOKAHEAD_READY");}
     // Wait until the current window holds its batch number (or the main loop gives up on this one).
     while(!mayStage.await(200,java.util.concurrent.TimeUnit.MILLISECONDS))check(job);
     check(job);
     MusicCloudJob.Staged queued=gpu.stage(audio,scene,expect,(p,m)->{});
     decodeMs=spent;decoded=audio;visual=scene;staged=queued;audio=null;scene=null;
     if(cancel.get()&&queued!=null){staged=null;gpu.discard(queued);}
    }finally{if(audio!=null)audio.pcm.delete();if(scene!=null)scene.delete();}
   }catch(Throwable skipped){
    // Not fatal: the window is prepared again the normal way when its turn comes.
    if(diagnostic!=null&&!cancel.get()&&!job.cancel.get())diagnostic.record("MUSIC_STAGE_SKIPPED");
   }
  }
  private void check(Job job){if(cancel.get()||job.cancel.get())throw new java.util.concurrent.CancellationException();}
  /** Wait for the preparation to end (or be stopped by cancel). */
  void finish(AtomicBoolean jobCancel){
   if(thread==null)return;
   for(;;){
    if(jobCancel!=null&&jobCancel.get())stop();
    try{thread.join(200);}catch(InterruptedException interrupted){stop();Thread.currentThread().interrupt();try{thread.join(15000);}catch(InterruptedException ignored){}return;}
    if(!thread.isAlive())return;
    if(jobCancel==null){try{thread.join(15000);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}return;}
   }
  }
  /** Delete what was prepared and close its batch if it was queued but will not be used. */
  void release(MusicRecovery gpu){
   AudioPipeline.Decoded audio=decoded;File scene=visual;MusicCloudJob.Staged queued=staged;decoded=null;visual=null;staged=null;
   if(audio!=null)audio.pcm.delete();if(scene!=null)scene.delete();
   if(queued!=null&&gpu!=null)gpu.discard(queued);
  }
 }
 static long appendWindow(LiveSeparation.Session part,LiveSeparation.Session target,RandomAccessFile out,long start,long copied,long skip,long count)throws IOException{
  return appendWindow(part,target,out,start,copied,skip,count,null);
 }
 static long appendWindow(LiveSeparation.Session part,LiveSeparation.Session target,RandomAccessFile out,long start,long copied,long skip,long count,byte[] tail)throws IOException{
  if(part.pcm==null)return copied;
  long available=Math.min(count,Math.max(0,part.frames-skip)),keep=available-copied;
  if(keep<=0)return copied;
  try(RandomAccessFile in=new RandomAccessFile(part.pcm,"r")){
   in.seek((skip+copied)*4);out.seek((start+copied)*4);
   byte[] bytes=new byte[65536];
   while(keep>0){
    int n=(int)Math.min(keep,bytes.length/4);
    in.readFully(bytes,0,n*4);
    if(tail!=null)blendJoin(bytes,n,copied,tail);
    out.write(bytes,0,n*4);copied+=n;keep-=n;
   }
   LiveSeparation.publishRange(target,start,start+copied);
   return copied;
  }
 }
 static void blendJoin(byte[] next,int count,long offset,byte[] tail){
  int frames=tail.length/4;
  for(int frame=0;frame<count&&offset+frame<frames;frame++){
   int from=(int)(offset+frame)*4;
   float alpha=(offset+frame)/(float)Math.max(1,frames-1);
   for(int channel=0;channel<2;channel++){
    int i=frame*4+channel*2,j=from+channel*2;
    short a=(short)((tail[j]&255)|(tail[j+1]<<8)),b=(short)((next[i]&255)|(next[i+1]<<8));
    short sample=(short)Math.round(a*(1-alpha)+b*alpha);
    next[i]=(byte)sample;next[i+1]=(byte)(sample>>8);
   }
  }
 }
 static byte[] tailForJoin(LiveSeparation.Session part,long start)throws IOException{
  int frames=(int)Math.min(4410,Math.max(0,part.frames-start));
  if(frames<2||part.pcm==null)return null;
  byte[] bytes=new byte[frames*4];
  try(RandomAccessFile file=new RandomAccessFile(part.pcm,"r")){file.seek(start*4);file.readFully(bytes);}
  return bytes;
 }
 /** Copy only complete new core frames; context is never duplicated at window boundaries. */
 static void appendPrefix(LiveSeparation.Session part,LiveSeparation.Session target,OutputStream out,long base,long skip,long count)throws IOException{
  if(part.pcm==null)return;long copied=target.frames-base,available=Math.min(count,Math.max(0,part.frames-skip)),keep=available-copied;if(keep<=0)return;
  try(RandomAccessFile in=new RandomAccessFile(part.pcm,"r")){in.seek((skip+copied)*4);byte[] bytes=new byte[65536];while(keep>0){int frames=(int)Math.min(keep,bytes.length/4);in.readFully(bytes,0,frames*4);out.write(bytes,0,frames*4);copied+=frames;keep-=frames;}out.flush();LiveSeparation.publish(target,base+copied);}
 }
 @Override public void onTimeout(int startId,int type){if(job!=null){job.cancel.set(true);job.stage="أوقف النظام جلسة الفصل؛ يمكن بدء جلسة جديدة";}stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(jobStartId);}
 @Override public void onDestroy(){if(processing==job&&job!=null){job.cancel.set(true);MusicGpuSession.cancelOwner(job.cancel);}super.onDestroy();}
 @Override public IBinder onBind(Intent intent){return null;}
}
