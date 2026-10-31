package com.madar.files;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import androidx.media3.ui.PlayerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
@RunWith(AndroidJUnit4.class)
public class CensorPlaybackTest {
 private PlayerActivity activity;private PlaybackSession session;private PlayerView view;private File media;private WatchCensorService.Job job;
 private void main(Runnable task){InstrumentationRegistry.getInstrumentation().runOnMainSync(task);}
 private Object field(String name)throws Exception{Field f=PlayerActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
 private void waitFor(java.util.function.BooleanSupplier test,long timeout)throws Exception{long end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){AtomicBoolean ok=new AtomicBoolean();main(()->ok.set(test.getAsBoolean()));if(ok.get())return;Thread.sleep(40);}fail("Timed out waiting for actual player state");}
 @Before public void start()throws Exception{startWith("watch-long.mp4",24000,160,90);}
 private void startWith(String fixture,long duration,int width,int height)throws Exception{
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();media=File.createTempFile("censor_playback_",".mp4",context.getCacheDir());
  try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(fixture);OutputStream out=new FileOutputStream(media)){FilesUtil.copy(in,out);}
  activity=(PlayerActivity)InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(context,PlayerActivity.class).putExtra("path",media.getPath()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  waitFor(()->{try{return ((PlaybackSession)field("playback")).duration()>0;}catch(Exception e){return false;}},15000);
  session=(PlaybackSession)field("playback");view=(PlayerView)field("view");main(()->session.setWanted(false));
  job=new WatchCensorService.Job(context,session.input,session.id,0,duration,width,height,false);
 }
 private void publish(long start,long end)throws Exception{job.timeline.install(CensorTimelineTest.data(job.id,start,end,true).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),start*1000,end*1000);}
 private void attach(){main(()->{view.setPlayer(null);session.useCensor(job);view.setPlayer(session.controls);session.setWanted(true);});}
 @Test(timeout=60000) public void playbackWaitsForVerifiedMasksAndUsesMediaPresentationTimestamps()throws Exception{
  attach();Thread.sleep(300);main(()->{assertTrue(session.controls.getPlayWhenReady());assertFalse(session.player.getPlayWhenReady());assertTrue(session.censorShielded());});
  publish(0,8000);publish(8000,16000);waitFor(()->session.player.isPlaying()&&session.position()>400,12000);
  main(()->{assertTrue(session.censorOverlay.lastTimestampUs>=0);assertTrue("Effect must receive media time, not ExoPlayer's internal offset",session.censorOverlay.lastTimestampUs<24000000);assertFalse(session.censorShielded());session.controls.pause();});
  AtomicLong at=new AtomicLong();main(()->at.set(session.position()));Thread.sleep(300);main(()->{assertFalse(session.wanted());assertTrue(Math.abs(session.position()-at.get())<120);});
 }
 @Test(timeout=60000) public void rotatedVideoMasksUseDisplayedPortraitCoordinates()throws Exception{
  close();startWith("censor-time-rotated.mp4",3000,90,160);
  org.json.JSONObject data=CensorTimelineTest.data(job.id,0,3000,true);org.json.JSONArray frames=data.getJSONArray("frames");
  for(int i=0;i<frames.length();i++)frames.getJSONArray(i).put(2,new org.json.JSONArray().put(new org.json.JSONArray().put(0).put(0).put(0).put(.5).put(1)));
  job.timeline.install(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),0,3000000);job.done=true;attach();
  waitFor(()->session.player.isPlaying()&&session.position()>=1100,12000);main(()->session.controls.pause());
  AtomicReference<Bitmap> frame=new AtomicReference<>();AtomicInteger copied=new AtomicInteger(-1);java.util.concurrent.CountDownLatch copy=new java.util.concurrent.CountDownLatch(1);
  main(()->{android.view.SurfaceView surface=(android.view.SurfaceView)view.getVideoSurfaceView();assertTrue("Displayed rotated video must be portrait",surface.getHeight()>surface.getWidth());Bitmap bitmap=Bitmap.createBitmap(surface.getWidth(),surface.getHeight(),Bitmap.Config.ARGB_8888);frame.set(bitmap);android.view.PixelCopy.request(surface,bitmap,result->{copied.set(result);copy.countDown();},new android.os.Handler(android.os.Looper.getMainLooper()));});
  assertTrue(copy.await(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,copied.get());Bitmap bitmap=frame.get();
  long left=0,right=0,count=0;for(int y=bitmap.getHeight()*3/10;y<bitmap.getHeight()*7/10;y+=2)for(int x=bitmap.getWidth()/10;x<bitmap.getWidth()*4/10;x+=2){left+=Color.green(bitmap.getPixel(x,y));right+=Color.green(bitmap.getPixel(bitmap.getWidth()-1-x,y));count++;}
  assertTrue("The left half of the displayed portrait frame must be opaque",left/(double)Math.max(1,count)<8);assertTrue("The right half must retain the green source frame",right/(double)Math.max(1,count)>70);bitmap.recycle();
 }
 @Test(timeout=60000) public void seekIntoUnknownStaysCoveredAndRewindUsesCachedHistory()throws Exception{
  publish(0,8000);publish(8000,16000);attach();waitFor(()->session.player.isPlaying(),12000);
  main(()->session.controls.seekTo(21000));waitFor(()->session.censorWaiting,4000);
  main(()->{assertFalse(session.player.getPlayWhenReady());assertEquals(21000,job.requestedMs,200);assertTrue(session.censorShielded());});
  main(()->session.controls.seekTo(1000));waitFor(()->session.player.isPlaying()&&session.position()<5000,8000);
  main(()->{assertTrue(job.timeline.has(0));assertTrue(job.timeline.has(8000));assertEquals(0,job.timeline.boxesAt(22000000)==null?0:1);});
 }
 @Test(timeout=60000) public void pictureInPictureRetainsTheSameFrameMaskAndPlaybackOwner()throws Exception{
  publish(0,8000);publish(8000,16000);publish(16000,24000);job.done=true;attach();waitFor(()->session.player.isPlaying()&&session.censorSurfaceReady,12000);
  AtomicBoolean entered=new AtomicBoolean();main(()->{try{Method method=PlayerActivity.class.getDeclaredMethod("enterMiniPlayer");method.setAccessible(true);entered.set((Boolean)method.invoke(activity));}catch(Exception e){throw new RuntimeException(e);}});assertTrue(entered.get());waitFor(()->activity.isInPictureInPictureMode(),8000);
  main(()->{assertSame(session,PlaybackSession.current);assertSame(job,session.censorJob);assertTrue(session.censorRequested);assertTrue(session.censorOverlay.enabled);assertNotNull(session.censorOverlay.timeline.boxesAt(session.position()*1000));});
  long stamp=session.censorOverlay.lastTimestampUs;waitFor(()->session.censorOverlay.lastTimestampUs>stamp,5000);
  AtomicReference<Bitmap> frame=new AtomicReference<>();AtomicInteger copied=new AtomicInteger(-1);java.util.concurrent.CountDownLatch copy=new java.util.concurrent.CountDownLatch(1);
  main(()->{android.view.View surface=view.getVideoSurfaceView();assertTrue(surface instanceof android.view.SurfaceView);Bitmap image=Bitmap.createBitmap(surface.getWidth(),surface.getHeight(),Bitmap.Config.ARGB_8888);frame.set(image);android.view.PixelCopy.request((android.view.SurfaceView)surface,image,result->{copied.set(result);copy.countDown();},new android.os.Handler(android.os.Looper.getMainLooper()));});
  assertTrue(copy.await(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,copied.get());Bitmap image=frame.get();long sum=0,count=0;for(int y=image.getHeight()*3/10;y<image.getHeight()*7/10;y+=2)for(int x=image.getWidth()*3/10;x<image.getWidth()*7/10;x+=2){int c=image.getPixel(x,y);sum+=Color.red(c)+Color.green(c)+Color.blue(c);count+=3;}assertTrue("Actual PiP video surface must contain the opaque body mask",sum/(double)Math.max(1,count)<8);image.recycle();
 }
 @After public void close()throws Exception{if(activity!=null)main(()->{if(session!=null&&!session.closed)session.close();activity.finishAndRemoveTask();});if(job!=null)job.timeline.close();if(media!=null)media.delete();}
}
