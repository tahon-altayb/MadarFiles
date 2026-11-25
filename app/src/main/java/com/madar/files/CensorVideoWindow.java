package com.madar.files;

import android.content.Context;
import android.os.*;
import androidx.media3.common.*;
import androidx.media3.common.util.Clock;
import androidx.media3.effect.Presentation;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.transformer.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Small silent H.264 windows; same authenticated source factory as the visible player. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class CensorVideoWindow {
 static File extract(Context context,PlayerInput input,long startMs,long endMs,int width,int height,AtomicBoolean cancel)throws Exception{
  return extract(context,input,startMs,endMs,width,height,cancel,false,false);
 }
 static File extractForSound(Context context,PlayerInput input,long startMs,long endMs,int width,int height,AtomicBoolean cancel)throws Exception{
  return extractForSound(context,input,startMs,endMs,width,height,cancel,()->{});
 }
 static File extractForSound(Context context,PlayerInput input,long startMs,long endMs,int width,int height,AtomicBoolean cancel,Runnable retry)throws Exception{
  try{return extract(context,input,startMs,endMs,width,height,cancel,true,false);}
  catch(IOException error){
   if(!RemoteWatchAudio.muxTimedOut(error))throw error;
   ColabProbe.check(cancel);
   retry.run();
   return extract(context,input,startMs,endMs,width,height,cancel,true,true);
  }
 }
 private static File extract(Context context,PlayerInput input,long startMs,long endMs,int width,int height,AtomicBoolean cancel,boolean sound,boolean retry)throws Exception{
  if(startMs<0||endMs<=startMs||endMs-startMs>(sound?66000:16000)||width<=0||height<=0)throw new IOException("Colab · CENSOR_VIDEO_FORMAT");
  File video=File.createTempFile("madar_censor_window_",".mp4",context.getCacheDir());if(!video.delete())throw new IOException("تعذر تجهيز جزء الفيديو");
  HandlerThread thread=new HandlerThread("madar-censor-video");thread.start();Handler handler=new Handler(thread.getLooper());CountDownLatch done=new CountDownLatch(1);AtomicReference<Exception> error=new AtomicReference<>();AtomicReference<Transformer> current=new AtomicReference<>();boolean complete=false;
  try{
   handler.post(()->{try{
    ColabProbe.check(cancel);DefaultMediaSourceFactory sources=new DefaultMediaSourceFactory(input.http.factory(context),new androidx.media3.extractor.DefaultExtractorsFactory().setTsExtractorFlags(androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS|androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES));
    ExoPlayerAssetLoader.Factory loader=new ExoPlayerAssetLoader.Factory(context,new DefaultDecoderFactory.Builder(context).build(),Clock.DEFAULT,sources);
    DefaultEncoderFactory encoder=new DefaultEncoderFactory.Builder(context).setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate(sound?240000:900000).build()).setEnableFallback(true).build();
    Transformer.Builder builder=new Transformer.Builder(context).setAssetLoaderFactory(loader).setEncoderFactory(encoder);
    if(retry)builder.setMaxDelayBetweenMuxerSamplesMs(90000);
    Transformer transformer=builder
     .setTransformationRequest(new TransformationRequest.Builder().setVideoMimeType(MimeTypes.VIDEO_H264).setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build())
     .addListener(new Transformer.Listener(){@Override public void onCompleted(Composition composition,ExportResult result){done.countDown();}@Override public void onError(Composition composition,ExportResult result,ExportException failure){error.set(new IOException("تعذر تجهيز صورة الفيديو · "+failure.errorCode,failure));done.countDown();}}).build();current.set(transformer);
    MediaItem.Builder media=new MediaItem.Builder().setUri(input.uri).setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build());if(input.mime!=null)media.setMimeType(input.mime);
    int target=Math.max(2,(int)Math.round(Math.min(height,(sound?352.0:640.0)*height/Math.max(width,height))));target-=target%2;
    List<Effect> effects=new ArrayList<>();
    // AV-CASS reads 4 frames a second at 224 px: sending 4 (same bits per frame as 8 at 450 kb/s) halves the upload.
    if(sound)effects.add(androidx.media3.effect.FrameDropEffect.createDefaultFrameDropEffect(4));
    effects.add(Presentation.createForHeight(target));
    EditedMediaItem edited=new EditedMediaItem.Builder(media.build()).setRemoveAudio(true).setEffects(new Effects(Collections.emptyList(),effects)).build();
    transformer.start(edited,video.getPath());
   }catch(Exception failure){error.set(failure);done.countDown();}});
   long began=SystemClock.elapsedRealtime();while(!done.await(200,TimeUnit.MILLISECONDS)){ColabProbe.check(cancel);if(SystemClock.elapsedRealtime()-began>10*60000L)throw new IOException("Colab · CENSOR_VIDEO_TIMEOUT");}
   ColabProbe.check(cancel);if(error.get()!=null)throw error.get();if(video.length()<=0||video.length()>20000000)throw new IOException("Colab · CENSOR_INPUT_LENGTH");complete=true;return video;
  }finally{
   CountDownLatch stopped=new CountDownLatch(1);handler.post(()->{try{Transformer transformer=current.get();if(transformer!=null)transformer.cancel();}finally{stopped.countDown();}});
   try{stopped.await(5,TimeUnit.SECONDS);}finally{thread.quitSafely();if(!complete)video.delete();}
  }
 }
 private CensorVideoWindow(){}
}
