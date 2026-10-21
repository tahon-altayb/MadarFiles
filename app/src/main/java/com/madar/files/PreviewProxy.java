package com.madar.files;
import android.content.Context;
import android.net.Uri;
import android.os.*;
import androidx.media3.common.*;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.*;
import java.io.*;
import java.util.*;
import org.json.JSONObject;
/** A private, disposable H.264 viewing copy. Exports always use the original source. Main thread only. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class PreviewProxy {
 interface Events{void send(JSONObject event);}
 private final Context context;private final Events events;private final Handler handler=new Handler(Looper.getMainLooper());
 private Transformer transformer;private File source,ready,partial;private int token,generation;private long began;
 PreviewProxy(Context c,Events e){context=c;events=e;}
 void prepare(File input,int request)throws Exception{
  if(input.equals(source)&&ready!=null&&ready.isFile()){token=request;emit("proxyReady",100,ready.getName());return;}
  cancel();source=input;token=request;final int run=generation;if(context.getCacheDir().getUsableSpace()<96L*1024*1024)throw new IOException("المساحة لا تكفي لتجهيز معاينة متوافقة");
  ready=File.createTempFile("madar_proxy_",".mp4",context.getCacheDir());if(!ready.delete())throw new IOException("تعذر تجهيز المعاينة");partial=new File(ready.getPath()+".partial");began=SystemClock.elapsedRealtime();
  Transformer.Builder builder=new Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC).setEncoderFactory(new DefaultEncoderFactory.Builder(context).setEnableFallback(true).build());
  builder.addListener(new Transformer.Listener(){@Override public void onCompleted(Composition composition,ExportResult result){if(run!=generation)return;transformer=null;handler.removeCallbacks(poll);if(partial==null||!partial.renameTo(ready)){fail("تعذر حفظ نسخة المعاينة");return;}partial=null;emit("proxyReady",100,ready.getName());}@Override public void onError(Composition composition,ExportResult result,ExportException error){if(run!=generation)return;fail("تعذر تجهيز معاينة هذا الترميز ("+error.errorCode+"). يمكنك تشغيل الأصل في مشغل مدار.");}});
  transformer=builder.build();EditedMediaItem edited=new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input))).setEffects(new Effects(Collections.emptyList(),Collections.singletonList(Presentation.createForHeight(previewHeight(input))))).build();
  transformer.start(edited,partial.getPath());emit("proxyProgress",0,"");handler.postDelayed(poll,500);
 }
 private final Runnable poll=new Runnable(){public void run(){if(transformer==null)return;if(SystemClock.elapsedRealtime()-began>15*60*1000){fail("تجهيز المعاينة استغرق وقتًا طويلًا؛ استخدم مشغل مدار للأصل");return;}ProgressHolder p=new ProgressHolder();int status=transformer.getProgress(p);emit("proxyProgress",status==Transformer.PROGRESS_STATE_AVAILABLE?p.progress:-1,"");handler.postDelayed(this,750);}};
 private void emit(String type,int progress,String message){try{events.send(new JSONObject().put("type",type).put("token",token).put("progress",progress).put(type.equals("proxyReady")?"name":"message",message));}catch(Exception ignored){}}
 private void fail(String message){int previous=token;cancel();token=previous;emit("proxyError",0,message);}
 private int previewHeight(File file)throws Exception{try(android.media.MediaMetadataRetriever meta=new android.media.MediaMetadataRetriever()){meta.setDataSource(file.getPath());int height=Integer.parseInt(meta.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));String rotation=meta.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);if("90".equals(rotation)||"270".equals(rotation))height=Integer.parseInt(meta.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));return Math.min(720,Math.max(2,height/2*2-2));}}
 void stopPreparing(){if(transformer!=null)cancel();}
 void cancel(){generation++;handler.removeCallbacks(poll);if(transformer!=null){transformer.cancel();transformer=null;}if(partial!=null){partial.delete();partial=null;}if(ready!=null){ready.delete();ready=null;}source=null;}
}
