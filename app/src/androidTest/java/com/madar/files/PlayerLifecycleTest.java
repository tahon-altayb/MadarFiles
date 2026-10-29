package com.madar.files;

import android.app.*;
import android.content.*;
import android.media.MediaMetadata;
import android.media.session.*;
import android.os.*;
import android.view.*;
import android.widget.LinearLayout;
import androidx.media3.common.Player;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

/** Real system media controls, PiP, retained PCM clock, and overlapping Activity lifecycles. */
public class PlayerLifecycleTest {
 private final Instrumentation i=InstrumentationRegistry.getInstrumentation();
 private final Context c=i.getTargetContext();
 private interface Check {boolean get()throws Exception;}
 private void await(Check check)throws Exception{long end=SystemClock.elapsedRealtime()+12000;while(SystemClock.elapsedRealtime()<end){boolean[] done={false};Throwable[] error={null};i.runOnMainSync(()->{try{done[0]=check.get();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(error[0]);if(done[0])return;Thread.sleep(40);}fail("Lifecycle condition timed out");}
 private Object field(Object value,String name)throws Exception{java.lang.reflect.Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
 private File source()throws Exception{File f=File.createTempFile("lifecycle-",".mp4",c.getCacheDir());try(InputStream in=i.getContext().getAssets().open("watch-long.mp4");OutputStream out=new FileOutputStream(f)){FilesUtil.copy(in,out);}return f;}
 private File pcm()throws Exception{File f=File.createTempFile("lifecycle-",".pcm",c.getCacheDir());try(RandomAccessFile out=new RandomAccessFile(f,"rw")){out.setLength(24L*44100*4);}return f;}
 private PlayerActivity start(File f,LiveSeparation.Session live){Intent intent=new Intent(c,PlayerActivity.class).putExtra("path",f.getPath()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK);if(live!=null)intent.putExtra("separationSession",live.id);return (PlayerActivity)i.startActivitySync(intent);}
 private LiveSeparation.Session live(File source,File pcm){LiveSeparation.Session live=LiveSeparation.begin(source,true);LiveSeparation.attach(live,pcm,0);LiveSeparation.publish(live,24*44100);LiveSeparation.end(live,null);return live;}
 private MediaController controller()throws Exception{final MediaSession.Token[] token={null};await(()->{for(android.service.notification.StatusBarNotification n:c.getSystemService(NotificationManager.class).getActiveNotifications())if(n.getId()==83)token[0]=n.getNotification().extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);return token[0]!=null;});return new MediaController(c,token[0]);}
 private void shell(String command)throws Exception{try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(i.getUiAutomation().executeShellCommand(command))){in.readAllBytes();}}
 @Test public void systemSeekbarHasDurationAccurateClockAndWorkingTransportActions()throws Exception{
  File source=source(),pcm=pcm();PlayerActivity a=start(source,live(source,pcm));
  try{PlaybackSession s=PlaybackSession.current;await(()->s.clean!=null&&s.position()>200);MediaController controller=controller();await(()->controller.getMetadata()!=null&&controller.getMetadata().getLong(MediaMetadata.METADATA_KEY_DURATION)>23000);MediaController.TransportControls transport=controller.getTransportControls();transport.pause();await(()->!s.wanted());transport.seekTo(6000);await(()->Math.abs(s.position()-6000)<100);await(()->Math.abs(controller.getPlaybackState().getPosition()-6000)<100);
   PlaybackState state=controller.getPlaybackState();assertTrue((state.getActions()&PlaybackState.ACTION_SEEK_TO)!=0);assertEquals(0,state.getPlaybackSpeed(),0);assertEquals(3,state.getCustomActions().size());assertEquals(PlaybackService.ACTION+"REWIND",state.getCustomActions().get(0).getAction());assertEquals(PlaybackService.ACTION+"FORWARD",state.getCustomActions().get(1).getAction());
   transport.sendCustomAction(PlaybackService.ACTION+"FORWARD",null);await(()->Math.abs(s.position()-16000)<100);transport.sendCustomAction(PlaybackService.ACTION+"REWIND",null);await(()->Math.abs(s.position()-6000)<100);transport.play();await(()->s.position()>6200);i.runOnMainSync(()->assertEquals(0,s.player.getVolume(),0));
   // Save native UI evidence for visual inspection, not a fabricated mockup.
   shell("cmd statusbar expand-settings");Thread.sleep(600);shell("screencap -p /data/local/tmp/madar-media-controls.png");shell("cmd statusbar collapse");
  }finally{shell("cmd statusbar collapse");i.runOnMainSync(a::finish);source.delete();pcm.delete();}
 }
 @Test public void repeatedPipPauseResumeAndReturnKeepTheSameAudioTrack()throws Exception{
  assertTrue(c.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE));File source=source(),pcm=pcm();PlayerActivity a=start(source,live(source,pcm));
  try{PlaybackSession s=PlaybackSession.current;await(()->s.clean!=null&&s.position()>200);CleanAudioController clean=s.clean;MediaController system=controller();
   for(int n=0;n<3;n++){i.runOnMainSync(()->{s.setWanted(false);s.seekTo(1000);try{java.lang.reflect.Method enter=PlayerActivity.class.getDeclaredMethod("enterMiniPlayer");enter.setAccessible(true);assertTrue("A repeated PiP request must be accepted or queued until RESUMED",(boolean)enter.invoke(a));}catch(Exception e){throw new AssertionError(e);}});await(a::isInPictureInPictureMode);
    i.runOnMainSync(()->{try{PictureInPictureParams params=(PictureInPictureParams)field(a,"miniParams");assertEquals(3,params.getActions().size());assertEquals("تقديم 10 ثوانٍ",params.getActions().get(2).getTitle().toString());LinearLayout bar=(LinearLayout)field(a,"toolbar");assertEquals(View.GONE,((View)bar.getParent()).getVisibility());}catch(Exception e){throw new AssertionError(e);}});
    system.getTransportControls().play();await(()->s.position()>1300);system.getTransportControls().pause();await(()->!s.wanted());long[] at={0};i.runOnMainSync(()->at[0]=s.position());Thread.sleep(180);i.runOnMainSync(()->assertEquals(at[0],s.position(),100));system.getSessionActivity().send();await(()->!a.isInPictureInPictureMode()&&a.hasWindowFocus());i.runOnMainSync(()->{assertSame(s,PlaybackSession.current);assertSame(clean,s.clean);assertFalse(s.wanted());assertFalse(a.isDestroyed());});
   }
  }finally{i.runOnMainSync(a::finish);source.delete();pcm.delete();}
 }
 @Test public void staleNotificationCannotPauseTheNextVideo()throws Exception{
  File first=source(),second=source();PlayerActivity a=start(first,null),b=null;
  try{PlaybackSession previous=PlaybackSession.current;await(()->previous.position()>200);PendingIntent stale=PlaybackService.action(c,previous.id,"PAUSE",85);b=start(second,null);PlaybackSession next=PlaybackSession.current;assertNotSame(previous,next);await(()->next.position()>200);stale.send();Thread.sleep(350);i.runOnMainSync(()->{assertTrue(next.wanted());assertSame(next,PlaybackSession.current);assertTrue(previous.closed);});
  }finally{PlayerActivity latest=b;i.runOnMainSync(()->{a.finish();if(latest!=null)latest.finish();});first.delete();second.delete();}
 }
 @Test public void closingAnOlderScreenCannotCloseTheNewScreenForTheSameVideo()throws Exception{
  File source=source();PlayerActivity a=start(source,null),b=null;
  try{PlaybackSession s=PlaybackSession.current;await(()->s.position()>200);b=start(source,null);assertNotSame(a,b);i.runOnMainSync(a::finish);Thread.sleep(150);i.runOnMainSync(()->{assertSame(s,PlaybackSession.current);assertFalse(s.closed);assertTrue(s.wanted());});
  }finally{PlayerActivity latest=b;i.runOnMainSync(()->{a.finish();if(latest!=null)latest.finish();});source.delete();}
 }
 @Test public void recreationRetainsResolvedSourceAndPlaybackSettings()throws Exception{
  File source=source(),resolved=source();PlayerActivity a=start(source,null),replacement=null;Instrumentation.ActivityMonitor monitor=i.addMonitor(PlayerActivity.class.getName(),null,false);
  try{PlaybackSession s=PlaybackSession.current;await(()->s.position()>200);i.runOnMainSync(()->{s.original();s.setWanted(false);s.prepare(s.input.resolved(new MediaHttp.Probe(android.net.Uri.fromFile(resolved),"video/mp4",false,java.util.Collections.emptyMap())),null,4000,false);s.settings(1.25f,1,.37f);s.displayMode=2;a.recreate();});replacement=(PlayerActivity)i.waitForMonitorWithTimeout(monitor,10000);assertNotNull(replacement);PlayerActivity latest=replacement;await(()->s.player.getPlaybackState()==Player.STATE_READY);i.runOnMainSync(()->{assertSame(s,PlaybackSession.current);assertEquals(android.net.Uri.fromFile(resolved),s.player.getCurrentMediaItem().localConfiguration.uri);assertEquals(.37f,s.volume,0);assertEquals(1.25f,s.speed,0);assertFalse(s.wanted());assertEquals(4000,s.position(),100);try{assertEquals(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,((androidx.media3.ui.PlayerView)field(latest,"view")).getResizeMode());}catch(Exception e){throw new AssertionError(e);}});
  }finally{i.removeMonitor(monitor);PlayerActivity latest=replacement==null?a:replacement;i.runOnMainSync(latest::finish);source.delete();resolved.delete();}
 }
 @Test public void completedVideoHasPlayStateAndCanRestartFromSystemControls()throws Exception{
  File source=source();PlayerActivity a=start(source,null);
  try{PlaybackSession s=PlaybackSession.current;await(()->s.duration()>23000);MediaController system=controller();i.runOnMainSync(()->s.seekTo(Long.MAX_VALUE));await(()->s.player.getPlaybackState()==Player.STATE_ENDED);await(()->system.getPlaybackState().getState()==PlaybackState.STATE_STOPPED);i.runOnMainSync(()->assertFalse(s.wanted()));system.getTransportControls().play();await(()->s.position()>100&&s.position()<5000);
  }finally{i.runOnMainSync(a::finish);source.delete();}
 }
 @Test public void homeAndBackKeepBackgroundMediaWithoutAutomaticPip()throws Exception{
  File source=source(),pcm=pcm();PlayerActivity a=start(source,live(source,pcm));
  try{PlaybackSession s=PlaybackSession.current;await(()->s.clean!=null&&s.position()>200);CleanAudioController clean=s.clean;MediaController system=controller();
   i.runOnMainSync(()->{try{PictureInPictureParams params=(PictureInPictureParams)field(a,"miniParams");assertFalse(params.isAutoEnterEnabled());}catch(Exception e){throw new AssertionError(e);}});
   for(boolean home:new boolean[]{true,false}){long[] before={0};i.runOnMainSync(()->{s.seekTo(1000);before[0]=s.position();});if(home)shell("input keyevent KEYCODE_HOME");else i.runOnMainSync(a::onBackPressed);
    await(()->!a.hasWindowFocus());await(()->s.position()>before[0]+300);i.runOnMainSync(()->{assertFalse(a.isInPictureInPictureMode());assertFalse(a.isDestroyed());assertSame(clean,s.clean);assertFalse(s.closed);});
    system.getTransportControls().pause();await(()->!s.wanted());system.getTransportControls().play();await(()->s.wanted());system.getSessionActivity().send();await(()->a.hasWindowFocus()&&!a.isInPictureInPictureMode());
   }
  }finally{i.runOnMainSync(a::finish);source.delete();pcm.delete();}
 }
 @Test public void fiveAndTenSecondSeeksDoNotReapplyTheWholeEpisodeReserve()throws Exception{
  File source=source(),pcm=pcm();LiveSeparation.Session live=LiveSeparation.begin(source,true);LiveSeparation.attach(live,pcm,0);LiveSeparation.publish(live,24*44100);PlayerActivity a=start(source,live);
  try{PlaybackSession s=PlaybackSession.current;await(()->s.clean!=null&&s.position()>200);CleanAudioController clean=s.clean;
   i.runOnMainSync(()->{live.buffer=new WatchBuffer(1500,true);live.buffer.planGap(300);});
   for(long at:new long[]{5000,10000,5000}){i.runOnMainSync(()->{s.seekTo(at);assertEquals(at,s.position(),2);});await(()->s.position()>at+200);i.runOnMainSync(()->{assertSame(clean,s.clean);assertFalse(live.done);assertEquals(0,s.player.getVolume(),0);});}
   i.runOnMainSync(()->{s.setWanted(false);s.seekTo(10000);});Thread.sleep(350);i.runOnMainSync(()->{assertFalse(s.wanted());assertEquals(10000,s.position(),100);});
  }finally{LiveSeparation.end(live,null);i.runOnMainSync(a::finish);source.delete();pcm.delete();}
 }
 @Test public void seekBeyondReadyWaitsForOnlyItsPrefixAndNeverLeaksOriginalAudio()throws Exception{
  File source=source(),pcm=pcm();LiveSeparation.Session live=LiveSeparation.begin(source,true);LiveSeparation.attach(live,pcm,0);LiveSeparation.publish(live,8*44100);PlayerActivity a=start(source,live);
  try{PlaybackSession s=PlaybackSession.current;await(()->s.clean!=null&&s.position()>200);i.runOnMainSync(()->{live.buffer=new WatchBuffer(1500,true);s.seekTo(12000);});Thread.sleep(300);i.runOnMainSync(()->{assertEquals(12000,s.position(),100);assertTrue(s.buffering());assertEquals(0,s.player.getVolume(),0);});LiveSeparation.publish(live,16*44100);await(()->s.position()>12200);i.runOnMainSync(()->assertFalse(live.done));
  }finally{LiveSeparation.end(live,null);i.runOnMainSync(a::finish);source.delete();pcm.delete();}
 }
 @Test public void explicitWaitForCompletionStillWaitsAfterASeek()throws Exception{
  File source=source(),pcm=pcm();LiveSeparation.Session live=LiveSeparation.begin(source,true);LiveSeparation.attach(live,pcm,0);LiveSeparation.publish(live,24*44100);live.waitForComplete=true;PlayerActivity a=start(source,live);
  try{PlaybackSession s=PlaybackSession.current;await(()->s.clean!=null);i.runOnMainSync(()->s.seekTo(5000));Thread.sleep(400);i.runOnMainSync(()->{assertTrue(s.buffering());assertEquals(5000,s.position(),100);assertEquals(0,s.player.getVolume(),0);});LiveSeparation.end(live,null);await(()->s.position()>5200);
  }finally{LiveSeparation.end(live,null);i.runOnMainSync(a::finish);source.delete();pcm.delete();}
 }
 @Test public void preparationBannerExpiresDespiteProgressAndCanBeRevealedByTouch()throws Exception{
  File source=source(),pcm=pcm();LiveSeparation.Session live=LiveSeparation.begin(source,true);LiveSeparation.attach(live,pcm,0);live.waitForComplete=true;LiveSeparation.publish(live,8*44100);PlayerActivity a=start(source,live);
  try{
   PlaybackSession s=PlaybackSession.current;await(()->s.clean!=null);
   android.widget.TextView banner=(android.widget.TextView)field(a,"cleanStatus");
   await(()->banner.getVisibility()==View.VISIBLE);
   // Repeated controller refreshes are not touches and cannot extend the timer.
   i.runOnMainSync(()->{try{java.lang.reflect.Method show=PlayerActivity.class.getDeclaredMethod("showControls",boolean.class);show.setAccessible(true);for(int n=0;n<3;n++){show.invoke(a,false);show.invoke(a,true);}}catch(Exception e){throw new AssertionError(e);}});
   await(()->banner.getVisibility()==View.GONE);
   i.runOnMainSync(()->{assertTrue(s.buffering());LiveSeparation.publish(live,16*44100);});Thread.sleep(500);
   i.runOnMainSync(()->{
    assertEquals(View.GONE,banner.getVisibility());long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,80,200,0),up=MotionEvent.obtain(now,now+20,MotionEvent.ACTION_UP,80,200,0);
    try{a.dispatchTouchEvent(down);a.dispatchTouchEvent(up);}finally{down.recycle();up.recycle();}
    assertEquals(View.VISIBLE,banner.getVisibility());
   });
  }finally{LiveSeparation.end(live,null);i.runOnMainSync(a::finish);source.delete();pcm.delete();}
 }

 @Test public void startingRemovalFiveOrTenSecondsIntoTheEpisodeStartsBeforeCompletion()throws Exception{
  for(int offset:new int[]{5,10}){File source=File.createTempFile("lifecycle-offset-",".mp4",c.getCacheDir()),pcm=File.createTempFile("lifecycle-offset-",".pcm",c.getCacheDir());try(InputStream in=i.getContext().getAssets().open("music-long.mp4");OutputStream out=new FileOutputStream(source)){FilesUtil.copy(in,out);}try(RandomAccessFile out=new RandomAccessFile(pcm,"rw")){out.setLength(40L*44100*4);}LiveSeparation.Session live=LiveSeparation.begin(source,true);LiveSeparation.attach(live,pcm,offset*1000000L);live.buffer=new WatchBuffer(65-offset,true);live.buffer.planGap(180);LiveSeparation.publish(live,40L*44100);PlayerActivity a=start(source,live);
   try{PlaybackSession s=PlaybackSession.current;await(()->s.clean!=null&&s.position()>offset*1000+200);i.runOnMainSync(()->{assertFalse(live.done);assertTrue(s.position()>=offset*1000);assertEquals(0,s.player.getVolume(),0);});}finally{LiveSeparation.end(live,null);i.runOnMainSync(a::finish);source.delete();pcm.delete();}
  }
 }

}
