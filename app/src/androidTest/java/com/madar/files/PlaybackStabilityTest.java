package com.madar.files;

import android.app.*;
import android.content.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

/** Actual AudioTrack/ExoPlayer lifecycle and transport regressions. */
public class PlaybackStabilityTest {
 private final Instrumentation i=InstrumentationRegistry.getInstrumentation();
 private final Context c=i.getTargetContext();
 private interface Check {boolean get()throws Exception;}
 private void await(Check check)throws Exception{long end=System.currentTimeMillis()+12000;while(System.currentTimeMillis()<end){final boolean[] done={false};final Exception[] error={null};i.runOnMainSync(()->{try{done[0]=check.get();}catch(Exception e){error[0]=e;}});if(error[0]!=null)throw error[0];if(done[0])return;Thread.sleep(40);}fail("Playback condition timed out");}
 private File source()throws Exception{File f=File.createTempFile("stability-",".mp4",c.getCacheDir());try(InputStream in=i.getContext().getAssets().open("watch-long.mp4");OutputStream out=new FileOutputStream(f)){FilesUtil.copy(in,out);}return f;}
 private File pcm()throws Exception{File f=File.createTempFile("stability-",".pcm",c.getCacheDir());try(RandomAccessFile out=new RandomAccessFile(f,"rw")){out.setLength(24L*44100*4);}return f;}
 private PlayerActivity start(File source,LiveSeparation.Session live){return (PlayerActivity)i.startActivitySync(new Intent(c,PlayerActivity.class).putExtra("path",source.getPath()).putExtra("separationSession",live.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
 @Test public void repeatedSwitchesAndPausesKeepOnePlayerAndCorrectUserIntent()throws Exception{
  File source=source(),pcm=pcm();LiveSeparation.Session live=LiveSeparation.begin(source,true);LiveSeparation.attach(live,pcm,0);LiveSeparation.publish(live,24*44100);LiveSeparation.end(live,null);PlayerActivity activity=start(source,live);
  try{PlaybackSession s=PlaybackSession.current;ExoPlayer player=s.player;await(()->s.clean!=null&&s.clean.playedFrames()>22050);
   for(int n=0;n<8;n++){i.runOnMainSync(()->{s.setWanted(false);long at=s.position();s.original();assertFalse(s.wanted());assertEquals(at,s.position(),100);assertSame(player,s.player);s.useClean(live,null,true,s.position(),false);assertEquals(0,s.player.getVolume(),0);assertFalse(s.wanted());});long at[]=new long[1];i.runOnMainSync(()->at[0]=s.position());Thread.sleep(120);i.runOnMainSync(()->{assertEquals("Switching must preserve a manual pause",at[0],s.position(),80);s.controls.play();});await(()->s.position()>at[0]+80);}
   i.runOnMainSync(()->{assertSame(player,s.player);assertEquals(0,s.player.getVolume(),0);assertSame(s,PlaybackSession.current);});
  }finally{i.runOnMainSync(activity::finish);source.delete();pcm.delete();}
 }
 @Test public void recreationRetainsTheSameAudioTrackAndNotificationControls()throws Exception{
  File source=source(),pcm=pcm();LiveSeparation.Session live=LiveSeparation.begin(source,true);LiveSeparation.attach(live,pcm,0);LiveSeparation.publish(live,24*44100);LiveSeparation.end(live,null);PlayerActivity activity=start(source,live);PlayerActivity recreated=null;Instrumentation.ActivityMonitor monitor=i.addMonitor(PlayerActivity.class.getName(),null,false);
  try{PlaybackSession s=PlaybackSession.current;await(()->s.clean!=null&&s.clean.playedFrames()>22050);CleanAudioController clean=s.clean;ExoPlayer player=s.player;long before[]=new long[1];i.runOnMainSync(()->{before[0]=s.position();activity.recreate();});recreated=(PlayerActivity)i.waitForMonitorWithTimeout(monitor,10000);assertNotNull(recreated);assertNotSame(activity,recreated);await(()->s.position()>before[0]+200);
   i.runOnMainSync(()->{assertSame(s,PlaybackSession.current);assertSame("Lifecycle must not rebuild PCM/AudioTrack",clean,s.clean);assertSame(player,s.player);});
   PlaybackService.action(c,s.id,"PAUSE",184).send();await(()->!s.wanted());long paused[]=new long[1];i.runOnMainSync(()->paused[0]=s.position());Thread.sleep(250);i.runOnMainSync(()->assertEquals(paused[0],s.position(),100));PlaybackService.action(c,s.id,"PLAY",185).send();await(()->s.position()>paused[0]+200);
  }finally{i.removeMonitor(monitor);PlayerActivity last=recreated==null?activity:recreated;i.runOnMainSync(last::finish);source.delete();pcm.delete();}
 }
 @Test public void originalAheadOfTheReadyPrefixWaitsAtItsPositionInsteadOfRewinding()throws Exception{
  File source=source(),pcm=pcm();LiveSeparation.Session live=LiveSeparation.begin(source,true);LiveSeparation.attach(live,pcm,0);LiveSeparation.publish(live,8*44100);PlayerActivity activity=start(source,live);
  try{PlaybackSession s=PlaybackSession.current;await(()->s.clean!=null&&s.clean.playedFrames()>4410);i.runOnMainSync(()->{s.original();s.player.pause();s.player.seekTo(12000);s.useClean(live,null,true,12000,true);assertEquals(12000,s.position(),1);assertEquals(0,s.player.getVolume(),0);});Thread.sleep(400);i.runOnMainSync(()->assertEquals("Unready clean audio must not silently jump backward",12000,s.position(),100));LiveSeparation.publish(live,20*44100);await(()->s.position()>12200);
  }finally{LiveSeparation.end(live,null);i.runOnMainSync(activity::finish);source.delete();pcm.delete();}
 }
 @Test public void cancelledWatchCannotBeAttachedAgainEvenWithAReadyPrefix()throws Exception{
  File source=source(),pcm=pcm();WatchMusicService.Job job=new WatchMusicService.Job(android.net.Uri.fromFile(source),source,false,"cancelled","test",java.util.Collections.emptyMap(),null,0,24000000,true);LiveSeparation.attach(job.clean,pcm,0);LiveSeparation.publish(job.clean,24*44100);job.cancel.set(true);
  try{i.runOnMainSync(()->{PlaybackSession s=PlaybackSession.obtain(c,uncheckedInput(source));try{s.prepare(s.input,null,0,false);try{s.useClean(job.clean,job,false,0,true);fail("A cancelled job must require a fresh separation");}catch(IllegalStateException expected){assertFalse(job.usable());}}finally{s.close();}});}finally{source.delete();pcm.delete();}
 }
 private PlayerInput uncheckedInput(File source){try{return PlayerInput.read(c,new Intent().putExtra("path",source.getPath()));}catch(Exception e){throw new AssertionError(e);}}
}
