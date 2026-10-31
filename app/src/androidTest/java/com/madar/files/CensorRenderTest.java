package com.madar.files;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.*;
import androidx.media3.common.*;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.transformer.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
@RunWith(AndroidJUnit4.class)
public class CensorRenderTest {
 @Test(timeout=150000) public void actualEncodedVideoContainsMaskAndUnknownRegionIsEntirelyBlack()throws Exception{
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();File input=File.createTempFile("censor_input_",".mp4",context.getCacheDir()),output=File.createTempFile("censor_output_",".mp4",context.getCacheDir());output.delete();
  try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("sample.mp4");OutputStream out=new FileOutputStream(input)){FilesUtil.copy(in,out);}
  String source="a".repeat(32);File dir=new File(context.getCacheDir(),"censor_render_"+java.util.UUID.randomUUID());
  try(CensorTimeline timeline=new CensorTimeline(dir,source)){
   org.json.JSONObject data=CensorTimelineTest.data(source,0,800,true);org.json.JSONArray frames=data.getJSONArray("frames");
   for(int i=0;i<frames.length();i++)frames.getJSONArray(i).put(2,new org.json.JSONArray().put(new org.json.JSONArray().put(0).put(0).put(0).put(.5).put(1)));
   timeline.install(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),0,800000);
   CensorOverlay overlay=new CensorOverlay();overlay.enabled=true;overlay.timeline=timeline;CountDownLatch done=new CountDownLatch(1);AtomicReference<Exception> failure=new AtomicReference<>();AtomicReference<Transformer> running=new AtomicReference<>();
   InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
    Transformer t=new Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).addListener(new Transformer.Listener(){@Override public void onCompleted(Composition c,ExportResult r){done.countDown();}@Override public void onError(Composition c,ExportResult r,ExportException error){failure.set(error);done.countDown();}}).build();running.set(t);
    EditedMediaItem media=new EditedMediaItem.Builder(MediaItem.fromUri(android.net.Uri.fromFile(input))).setRemoveAudio(true).setEffects(new Effects(Collections.emptyList(),Collections.singletonList(new OverlayEffect(Collections.singletonList(overlay))))).build();t.start(media,output.getPath());
   });
   try{assertTrue(done.await(120,TimeUnit.SECONDS));assertNull(String.valueOf(failure.get()),failure.get());}finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->running.get().cancel());}
   try(MediaMetadataRetriever retriever=new MediaMetadataRetriever()){
    retriever.setDataSource(output.getPath());Bitmap covered=retriever.getFrameAtTime(300000,MediaMetadataRetriever.OPTION_CLOSEST);assertNotNull(covered);
    assertTrue("Left half must be composited black",brightness(covered,.1,.4)<8);assertTrue("Outside the mask must retain the video",brightness(covered,.6,.9)>15);covered.recycle();
    Bitmap unknown=retriever.getFrameAtTime(1400000,MediaMetadataRetriever.OPTION_CLOSEST);assertNotNull(unknown);assertTrue("Unanalysed frames must be black in the VIDEO, not only an Activity view",brightness(unknown,.1,.9)<8);unknown.recycle();
   }
  }finally{input.delete();output.delete();}
 }
 private static double brightness(Bitmap image,double left,double right){long sum=0,count=0;for(int y=image.getHeight()/5;y<image.getHeight()*4/5;y+=3)for(int x=(int)(image.getWidth()*left);x<image.getWidth()*right;x+=3){int c=image.getPixel(x,y);sum+=android.graphics.Color.red(c)+android.graphics.Color.green(c)+android.graphics.Color.blue(c);count+=3;}return sum/(double)Math.max(1,count);}
}
