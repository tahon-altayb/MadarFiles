package com.madar.files;

import android.content.Context;
import android.net.Uri;
import android.os.*;
import androidx.media3.common.*;
import androidx.media3.common.util.Clock;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.transformer.*;
import java.io.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Fetch only a bounded audio window from progressive/HLS/DASH VOD, including its HTTP headers. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class RemoteWatchAudio {
 static AudioPipeline.Decoded decode(Context c,Uri uri,Map<String,String> headers,String mime,long startUs,long endUs,AtomicBoolean cancel)throws Exception{
  return decode(c,new MediaHttp.Session(uri,headers),mime,startUs,endUs,cancel);
 }
 static AudioPipeline.Decoded decode(Context c,MediaHttp.Session session,String mime,long startUs,long endUs,AtomicBoolean cancel)throws Exception{
  Uri uri=session.uri;Map<String,String> headers=session.headersFor(uri);
  String path=uri.getPath()==null?"":uri.getPath().toLowerCase(java.util.Locale.ROOT);
  if(!MimeTypes.APPLICATION_M3U8.equals(mime)&&!MimeTypes.APPLICATION_MPD.equals(mime)&&!path.endsWith(".m3u8")&&!path.endsWith(".mpd")){
   // Progressive files need no intermediate AAC encoding/muxer. Some servers
   // work in the player but reject Transformer's second export/read pattern.
   try{return AudioPipeline.decodeRemoteRange(c,uri,headers,startUs,endUs,cancel);}catch(java.util.concurrent.CancellationException cancelled){throw cancelled;}catch(Exception direct){MusicSeparator.check(cancel);}
  }
  File audio=File.createTempFile("madar_watch_network_",".m4a",c.getCacheDir());if(!audio.delete())throw new IOException("تعذر تجهيز صوت الرابط");
  HandlerThread thread=new HandlerThread("madar-watch-network");thread.start();Handler handler=new Handler(thread.getLooper());CountDownLatch done=new CountDownLatch(1);AtomicReference<Exception> failure=new AtomicReference<>();AtomicReference<Transformer> running=new AtomicReference<>();
  try{
   handler.post(()->{try{
    MusicSeparator.check(cancel);DefaultMediaSourceFactory sources=new DefaultMediaSourceFactory(session.factory(c));
    ExoPlayerAssetLoader.Factory loader=new ExoPlayerAssetLoader.Factory(c,new DefaultDecoderFactory.Builder(c).build(),Clock.DEFAULT,sources);
    Transformer transformer=new Transformer.Builder(c).setAssetLoaderFactory(loader).setAudioMimeType(MimeTypes.AUDIO_AAC).addListener(new Transformer.Listener(){
     @Override public void onCompleted(Composition composition,ExportResult result){done.countDown();}
     @Override public void onError(Composition composition,ExportResult result,ExportException error){failure.set(new IOException(MediaFailure.describe(error,true)+" · رمز "+error.errorCode,error));done.countDown();}
    }).build();running.set(transformer);
    MediaItem.Builder media=new MediaItem.Builder().setUri(uri).setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startUs/1000).setEndPositionMs((endUs+999)/1000).build());if(mime!=null)media.setMimeType(mime);
    transformer.start(new EditedMediaItem.Builder(media.build()).setRemoveVideo(true).build(),audio.getPath());
   }catch(Exception e){failure.set(e);done.countDown();}});
   long began=SystemClock.elapsedRealtime();while(!done.await(200,TimeUnit.MILLISECONDS)){MusicSeparator.check(cancel);if(SystemClock.elapsedRealtime()-began>10L*60*1000)throw new IOException("انتهت مهلة تحميل صوت الرابط");}
   MusicSeparator.check(cancel);if(failure.get()!=null)throw failure.get();
   AudioPipeline.Decoded result=AudioPipeline.decodeRange(c,audio,0,endUs-startUs,cancel,(p,m)->{});result.offsetUs+=startUs;return result;
  }finally{CountDownLatch stopped=new CountDownLatch(1);handler.post(()->{try{Transformer transformer=running.get();if(transformer!=null)transformer.cancel();}finally{stopped.countDown();}});try{stopped.await(5,TimeUnit.SECONDS);}finally{thread.quitSafely();audio.delete();}}
 }
}
