package com.madar.files;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import androidx.media3.common.*;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.*;
import org.json.JSONObject;
import java.io.File;
import java.util.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class VideoExportService extends Service {
    static final String EVENT="com.madar.files.EXPORT";
    static volatile boolean active=false;
    static volatile String lastEvent;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Transformer transformer;
    private File partial,output;private boolean previewDraft;
    private volatile boolean terminal;
    private final java.util.concurrent.atomic.AtomicBoolean musicCancel=new java.util.concurrent.atomic.AtomicBoolean();
    private Thread musicWorker;
    private PowerManager.WakeLock cpuLock;
    private long began;
    private String stageText="جارٍ التجهيز…";
    private NotificationManager notifications;
    @Override public void onCreate(){super.onCreate();notifications=getSystemService(NotificationManager.class);notifications.createNotificationChannel(new NotificationChannel("exports","تصدير الفيديو",NotificationManager.IMPORTANCE_LOW));}
    private Notification notification(int progress){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel=PendingIntent.getService(this,1,new Intent(this,VideoExportService.class).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"exports").setSmallIcon(com.madar.files.R.drawable.ic_launcher).setContentTitle("مدار · معالجة الوسائط").setContentText(stageText).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setProgress(100,Math.max(0,progress),progress<0).addAction(new Notification.Action.Builder(null,"إلغاء",cancel).build()).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if("cancel".equals(intent.getAction())){if(musicWorker!=null&&active){musicCancel.set(true);}else if(active)finish("cancelled",null);else stopSelf();return START_NOT_STICKY;}
        if(active)return START_NOT_STICKY;
        try{
            if(Build.VERSION.SDK_INT>=35)startForeground(7,notification(-1),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);else startForeground(7,notification(-1),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            active=true;terminal=false;began=System.currentTimeMillis();
            JSONObject o=new JSONObject(intent.getStringExtra("options"));previewDraft=o.optBoolean("previewDraft");if(previewDraft){File[] old=getCacheDir().listFiles(f->f.getName().startsWith("madar_preview_"));if(old!=null)for(File f:old)f.delete();}String tool=o.getString("tool");boolean audio=tool.equals("audio");
            File input=new File(intent.getStringExtra("input"));if(!input.isFile()||!input.getCanonicalPath().startsWith(getCacheDir().getCanonicalPath()+File.separator))throw new IllegalArgumentException("ملف المصدر غير موجود");
            long duration=intent.getLongExtra("duration",0);if(duration<=0)throw new IllegalArgumentException("مدة الفيديو غير معروفة");
            if(tool.equals("trim")&&o.optString("cutMode").equals("copy")){startCopyTrim(input,o);return START_NOT_STICKY;}
            if(tool.equals("convert")&&o.optString("format").matches("mkv|mov|webm")){startContainer(input,o);return START_NOT_STICKY;}
            if(tool.equals("subtitles")){startSpeech(input,o);return START_NOT_STICKY;}
            if(tool.equals("sound")||o.optString("kind").equals("audio")&&!tool.equals("music")){startSound(input,o);return START_NOT_STICKY;}
            if(tool.equals("music")){startMusic(input,o);return START_NOT_STICKY;}
            MediaItem.Builder media=new MediaItem.Builder().setUri(Uri.fromFile(input));if(previewDraft)media.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(0).setEndPositionMs(Math.min(duration,10000)).build());
            if(tool.equals("trim")){
                double start=o.getDouble("start"),end=o.getDouble("end");if(previewDraft)end=Math.min(end,start+10);if(!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<=start||end*1000>duration+100)throw new IllegalArgumentException("حدود القص غير صالحة");
                media.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(Math.round(start*1000)).setEndPositionMs(Math.min(duration,Math.round(end*1000))).build());
            }
            List<Effect> effects=new ArrayList<>();int height=o.optInt("height",0),originalHeight=intent.getIntExtra("height",0);
            if(tool.equals("burnCaptions"))effects.add(new androidx.media3.effect.OverlayEffect(Collections.singletonList(new CaptionOverlay(o.getJSONArray("cues"),o))));
            if(!audio&&height>0&&height<originalHeight)effects.add(Presentation.createForHeight(height));
            EditedMediaItem edited=new EditedMediaItem.Builder(media.build()).setRemoveAudio(tool.equals("mute")).setRemoveVideo(audio).setEffects(new Effects(Collections.emptyList(),effects)).build();
            output=FilesUtil.output(this,audio?"m4a":"mp4");partial=new File(output.getAbsolutePath()+".partial");
            Transformer.Builder builder=new Transformer.Builder(this).setAudioMimeType(MimeTypes.AUDIO_AAC);
            if(!audio)builder.setVideoMimeType(o.optString("format","mp4").equals("hevc")?MimeTypes.VIDEO_H265:MimeTypes.VIDEO_H264);
            int bitrate=o.optInt("bitrate",0);
            if(bitrate>0)builder.setEncoderFactory(new DefaultEncoderFactory.Builder(this).setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate((int)FilesUtil.bounded(bitrate,500000,50000000)).build()).build());
            builder.addListener(new Transformer.Listener(){
                @Override public void onCompleted(Composition composition,ExportResult result){if(partial==null||partial.length()==0||!partial.renameTo(output)){finish("error","تعذر حفظ نتيجة الفيديو");return;}try{publish(FilesUtil.info(output).put("type","result"));}catch(Exception e){finish("error","تعذر قراءة النتيجة");return;}finish("done",null);}
                @Override public void onError(Composition composition,ExportResult result,ExportException error){finish("error","تعذر تصدير الفيديو على هذا الجهاز ("+error.errorCode+"). جرّب H.264 ودقة أقل وتأكد من المساحة المتاحة.");}
            });
            transformer=builder.build();
            if(tool.equals("editor")){
                org.json.JSONArray ranges=o.getJSONArray("segments");if(ranges.length()<1||ranges.length()>50)throw new IllegalArgumentException("اختر من جزء واحد إلى 50 جزءًا");List<EditedMediaItem> items=new ArrayList<>();
                for(int i=0;i<ranges.length();i++){org.json.JSONArray range=ranges.getJSONArray(i);double start=range.getDouble(0),end=range.getDouble(1);if(!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<=start||end*1000>duration+100)throw new IllegalArgumentException("حدود أحد الأجزاء غير صحيحة");MediaItem item=new MediaItem.Builder().setUri(Uri.fromFile(input)).setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(Math.round(start*1000)).setEndPositionMs(Math.min(duration,Math.round(end*1000))).build()).build();items.add(new EditedMediaItem.Builder(item).setEffects(new Effects(Collections.emptyList(),effects)).build());}
                double total=0;for(int i=0;i<ranges.length();i++)total+=ranges.getJSONArray(i).getDouble(1)-ranges.getJSONArray(i).getDouble(0);Composition.Builder composition=new Composition.Builder(new EditedMediaItemSequence(items));List<Effect> finalVideoEffects=new ArrayList<>();if(o.has("layers")&&o.getJSONArray("layers").length()>0)finalVideoEffects.add(new androidx.media3.effect.OverlayEffect(Collections.singletonList(new EditorOverlay(this,o.getJSONArray("layers"),total))));composition.setEffects(new Effects(Collections.singletonList(new EditorAudioProcessor(o.optDouble("gain",100),o.optDouble("fade",0),total)),finalVideoEffects));transformer.start(composition.build(),partial.getAbsolutePath());
            }else transformer.start(edited,partial.getAbsolutePath());
            publish(new JSONObject().put("type","progress").put("progress",-1));handler.post(progress);
        }catch(Exception e){finish("error",e.getMessage()==null?"تعذر بدء التصدير":e.getMessage());}
        return START_NOT_STICKY;
    }
    private void startCopyTrim(File input,JSONObject options)throws Exception{
        musicCancel.set(false);publish(new JSONObject().put("type","progress").put("progress",-1).put("message","قص دون إعادة ترميز؛ البداية عند أقرب إطار رئيسي سابق"));
        musicWorker=new Thread(()->{try{LosslessTrim.Result r=LosslessTrim.trim(this,input,options.optDouble("start"),options.optDouble("end"),musicCancel);JSONObject data=FilesUtil.info(r.file).put("type","result").put("actualStart",r.startUs/1000000.0);handler.post(()->{if(!terminal){publish(data);musicWorker=null;finish("done",null);}});}catch(java.util.concurrent.CancellationException e){handler.post(()->{musicWorker=null;finish("cancelled",null);});}catch(Exception e){handler.post(()->{musicWorker=null;finish("error","تعذر القص دون ترميز. جرّب القص الدقيق. "+e.getMessage());});}},"madar-copy-trim");musicWorker.start();
    }
    private void startContainer(File input,JSONObject options)throws Exception{
        musicCancel.set(false);publish(new JSONObject().put("type","progress").put("progress",-1).put("message","نسخ المسارات إلى الحاوية الجديدة دون إعادة ترميز"));
        musicWorker=new Thread(()->{try{File f=ContainerTools.convert(this,input,options.optString("format"),musicCancel);JSONObject data=FilesUtil.info(f).put("type","result");handler.post(()->{if(!terminal){publish(data);musicWorker=null;finish("done",null);}});}catch(java.util.concurrent.CancellationException e){handler.post(()->{musicWorker=null;finish("cancelled",null);});}catch(Exception e){handler.post(()->{musicWorker=null;finish("error",e.getMessage());});}},"madar-container");musicWorker.start();
    }
    private void startSpeech(File input,JSONObject options)throws Exception{
        cpuLock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MadarFiles:Speech");cpuLock.acquire(5L*60*60*1000);musicCancel.set(false);
        MusicSeparator.Progress callback=(percent,message)->handler.post(()->{if(terminal)return;try{stageText=message;publish(new JSONObject().put("type","progress").put("progress",percent).put("message",message));notifications.notify(7,notification(percent));}catch(Exception ignored){}});
        musicWorker=new Thread(()->{try{String language=options.optString("language","auto"),target=options.optString("target","original");String translationMode=options.optString("translationMode","local");if(!target.equals("original"))TranslationEngine.preflight(this,translationMode);org.json.JSONArray cues=SpeechProcessor.transcribe(this,input,language,options.optString("speechQuality","maximum"),options.optBoolean("speechSample")?30000000L:0,musicCancel,(p,m)->callback.update(target.equals("original")?p:p*65/100,m));if(cues.length()==0)throw new java.io.IOException("لم يُعثر على كلام واضح؛ جرّب صوتًا أوضح ولغة صحيحة");org.json.JSONArray originalCues=new org.json.JSONArray(cues.toString());String resultTarget=target,translationWarning="";if(!target.equals("original")){try{cues=TranslationEngine.translate(this,cues,language,target,translationMode,musicCancel,(p,m)->callback.update(65+p*33/100,m));}catch(java.util.concurrent.CancellationException e){throw e;}catch(Exception e){cues=originalCues;resultTarget="original";translationWarning="تعذرت الترجمة؛ حُفظ التفريغ الأصلي ويمكن إعادة ترجمته. "+(e.getMessage()==null?"تحقق من الاتصال والإعدادات":e.getMessage());}}MusicSeparator.check(musicCancel);File f=SpeechProcessor.srt(this,cues);JSONObject data=FilesUtil.info(f).put("type","subtitles").put("cues",cues).put("originalCues",originalCues).put("language",SpeechProcessor.language(originalCues,language)).put("target",resultTarget).put("warning",translationWarning);handler.post(()->{if(!terminal){publish(data);musicWorker=null;finish("done",null);}});}catch(java.util.concurrent.CancellationException e){handler.post(()->{musicWorker=null;finish("cancelled",null);});}catch(Exception|OutOfMemoryError|UnsatisfiedLinkError e){handler.post(()->{musicWorker=null;finish("error","تعذر التفريغ أو الترجمة: "+(e instanceof OutOfMemoryError?"الذاكرة لا تكفي؛ اختر النموذج المتوازن أو السريع":e.getMessage()==null?"تحقق من توفر نموذج اللغة والمساحة":e.getMessage()));});}},"madar-speech");musicWorker.start();
    }
    private void startSound(File input,JSONObject options)throws Exception{
        cpuLock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MadarFiles:AudioEdit");cpuLock.acquire(5L*60*60*1000);musicCancel.set(false);
        MusicSeparator.Progress callback=(percent,message)->handler.post(()->{if(terminal)return;try{stageText=message;publish(new JSONObject().put("type","progress").put("progress",percent).put("message",message));notifications.notify(7,notification(percent));}catch(Exception ignored){}});
        musicWorker=new Thread(()->{File result=null;try{result=AudioEditor.render(this,input,options,musicCancel,callback);MusicSeparator.check(musicCancel);JSONObject data=FilesUtil.info(result).put("type","result");handler.post(()->{if(!terminal){publish(data);musicWorker=null;finish("done",null);}});}catch(java.util.concurrent.CancellationException e){if(result!=null)result.delete();handler.post(()->{musicWorker=null;finish("cancelled",null);});}catch(Exception|OutOfMemoryError e){handler.post(()->{musicWorker=null;if(musicCancel.get())finish("cancelled",null);else finish("error","تعذر تعديل الصوت: "+e.getMessage());});}},"madar-audio-edit");musicWorker.start();
    }
    private void startMusic(File input,JSONObject options)throws Exception{
        cpuLock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MadarFiles:MusicSeparation");cpuLock.acquire(5L*60*60*1000);
        musicCancel.set(false);boolean sample=previewDraft||options.optBoolean("sample",false),audioOnly=options.optString("kind").equals("audio")||options.optString("musicOutput","video").equals("audio");long limitUs=sample?10000000L:0;
        output=FilesUtil.output(this,audioOnly?"m4a":"mp4");partial=new File(output.getAbsolutePath()+".partial");
        publish(new JSONObject().put("type","progress").put("progress",0).put("message","فك الصوت محليًا"));
        MusicSeparator.Progress callback=(percent,message)->handler.post(()->{if(terminal)return;try{stageText=message;publish(new JSONObject().put("type","progress").put("progress",percent).put("message",message));notifications.notify(7,notification(percent));}catch(Exception ignored){}});
        musicWorker=new Thread(()->{
            AudioPipeline.Decoded decoded=null;File clean=null;
            try{decoded=AudioPipeline.decode(this,input,limitUs,musicCancel,callback);clean=MusicSeparator.separate(this,decoded,musicCancel,callback);MusicSeparator.check(musicCancel);AudioPipeline.mux(input,clean,partial,decoded.offsetUs,limitUs,audioOnly,musicCancel,callback);MusicSeparator.check(musicCancel);
                if(!partial.renameTo(output))throw new java.io.IOException("تعذر حفظ النتيجة");JSONObject result=FilesUtil.info(output).put("type","result");handler.post(()->{if(!terminal){publish(result);musicWorker=null;finish("done",null);}});
            }catch(java.util.concurrent.CancellationException e){handler.post(()->{musicWorker=null;finish("cancelled",null);});}
            catch(Exception|OutOfMemoryError|UnsatisfiedLinkError e){handler.post(()->{musicWorker=null;finish("error","تعذر فصل الموسيقى. "+(e.getMessage()==null?"جرّب عينة أقصر وتأكد من المساحة المتاحة.":e.getMessage()));});}
            finally{if(decoded!=null)decoded.pcm.delete();if(clean!=null)clean.delete();}
        },"madar-offline-separation");musicWorker.start();
    }
    private final Runnable progress=new Runnable(){@Override public void run(){if(!active||terminal||transformer==null)return;try{if(System.currentTimeMillis()-began>5L*60*60*1000){finish("error","توقف التصدير بعد تجاوز خمس ساعات");return;}ProgressHolder p=new ProgressHolder();int state=transformer.getProgress(p);int value=state==Transformer.PROGRESS_STATE_AVAILABLE?p.progress:-1;publish(new JSONObject().put("type","progress").put("progress",value));notifications.notify(7,notification(value));handler.postDelayed(this,750);}catch(Exception e){finish("error","تعذر متابعة التصدير");}}};
    private void publish(JSONObject o){if(previewDraft&&o.optString("type").equals("result")){try{File original=FilesUtil.resolve(this,o.getString("name"));String name=original.getName();File draft=File.createTempFile("madar_preview_",name.substring(name.lastIndexOf('.')),getCacheDir());if(!draft.delete()||!original.renameTo(draft))throw new java.io.IOException("تعذر تجهيز المعاينة");o=FilesUtil.info(draft).put("type","draftResult");}catch(Exception e){try{o=new JSONObject().put("type","error").put("message","تعذر تجهيز المعاينة المعالجة");}catch(Exception ignored){}}}lastEvent=o.toString();sendBroadcast(new Intent(EVENT).setPackage(getPackageName()).putExtra("event",lastEvent));}
    private void finish(String type,String message){
        if(terminal)return;terminal=true;musicCancel.set(true);handler.removeCallbacksAndMessages(null);if(transformer!=null){transformer.cancel();transformer=null;}if(!type.equals("done")){if(partial!=null)partial.delete();try{JSONObject event=new JSONObject().put("type",type);if(message!=null)event.put("message",message);publish(event);}catch(Exception ignored){}}
        if(cpuLock!=null&&cpuLock.isHeld())cpuLock.release();active=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public void onTimeout(int startId,int fgsType){finish("error","أوقف النظام المعالجة بعد انتهاء الوقت المتاح؛ جرّب فيديو أقصر");}
    @Override public void onDestroy(){if(active&&!terminal)finish("error","توقفت معالجة الفيديو");super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
