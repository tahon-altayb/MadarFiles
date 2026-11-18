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
  if(startMs<0||endMs<=startMs||endMs-startMs>16000||width<=0||height<=0)throw new IOException("Colab · CENSOR_VIDEO_FORMAT");
  File video=File.createTempFile("madar_censor_window_",".mp4",context.getCacheDir());if(!video.delete())throw new IOException("تعذر تجهيز جزء الفيديو");
  HandlerThread thread=new HandlerThread("madar-censor-video");thread.start();Handler handler=new Handler(thread.getLooper());CountDownLatch done=new CountDownLatch(1);AtomicReference<Exception> error=new AtomicReference<>();AtomicReference<Transformer> current=new AtomicReference<>();boolean complete=false;
  try{
   handler.post(()->{try{
    ColabProbe.check(cancel);DefaultMediaSourceFactory sources=new DefaultMediaSourceFactory(input.http.factory(context),new androidx.media3.extractor.DefaultExtractorsFactory().setTsExtractorFlags(androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS|androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES));
    ExoPlayerAssetLoader.Factory loader=new ExoPlayerAssetLoader.Factory(context,new DefaultDecoderFactory.Builder(context).build(),Clock.DEFAULT,sources);
    DefaultEncoderFactory encoder=new DefaultEncoderFactory.Builder(context).setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate(900000).build()).setEnableFallback(true).build();
    Transformer transformer=new Transformer.Builder(context).setAssetLoaderFactory(loader).setEncoderFactory(encoder)
     .setTransformationRequest(new TransformationRequest.Builder().setVideoMimeType(MimeTypes.VIDEO_H264).setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build())
     .addListener(new Transformer.Listener(){@Override public void onCompleted(Composition composition,ExportResult result){done.countDown();}@Override public void onError(Composition composition,ExportResult result,ExportException failure){error.set(new IOException("تعذر تجهيز صورة الفيديو للحجب · "+failure.errorCode,failure));done.countDown();}}).build();current.set(transformer);
    MediaItem.Builder media=new MediaItem.Builder().setUri(input.uri).setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build());if(input.mime!=null)media.setMimeType(input.mime);
    int target=Math.max(2,(int)Math.round(Math.min(height,640.0*height/Math.max(width,height))));target-=target%2;
    EditedMediaItem edited=new EditedMediaItem.Builder(media.build()).setRemoveAudio(true).setEffects(new Effects(Collections.emptyList(),Collections.singletonList(Presentation.createForHeight(target)))).build();
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
