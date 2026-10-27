package com.madar.files;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.widget.Button;
import androidx.core.content.FileProvider;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;
public class PlayerWatchTest {
 private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
 private File fixture(Context c)throws Exception{File f=File.createTempFile("watch-player-",".mp4",c.getCacheDir());try(InputStream in=instrumentation.getContext().getAssets().open("sample.mp4");OutputStream out=new FileOutputStream(f)){FilesUtil.copy(in,out);}return f;}
 private Object field(Object o,String key)throws Exception{java.lang.reflect.Field f=o.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(o);}
 @Test public void androidOpenWithRoutesContentDirectlyToTheFullPlayer()throws Exception{
  Context c=instrumentation.getTargetContext();File source=fixture(c);Uri uri=FileProvider.getUriForFile(c,c.getPackageName()+".files",source);Intent open=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
  assertTrue(c.getPackageManager().queryIntentActivities(open,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).stream().anyMatch(r->r.activityInfo.name.equals(OpenMediaActivity.class.getName())&&r.activityInfo.exported));
  Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(PlayerActivity.class.getName(),null,false);PlayerActivity activity=null;
  try{instrumentation.startActivitySync(open.setClass(c,OpenMediaActivity.class).putExtra("path","/invalid/ignored.mp4"));activity=(PlayerActivity)instrumentation.waitForMonitorWithTimeout(monitor,10000);assertNotNull(activity);PlayerInput input=(PlayerInput)field(activity,"input");assertEquals(uri,input.uri);assertNull("Normal playback must not first copy the entire video",input.file);assertEquals(PlayerInput.read(c,new Intent().setData(uri)).key,input.key);assertTrue(field(activity,"view") instanceof PlayerView);}finally{instrumentation.removeMonitor(monitor);if(activity!=null){PlayerActivity a=activity;instrumentation.runOnMainSync(a::finish);}source.delete();}
 }
 @Test public void fullPlayerStartsRealRemovalAtCurrentPositionAndKeepsControls()throws Exception{
  Context c=instrumentation.getTargetContext();File source=fixture(c);long bytes=source.length(),modified=source.lastModified();boolean prior=c.getSharedPreferences("player",0).getBoolean("watch_cloud",false);c.getSharedPreferences("player",0).edit().putBoolean("watch_cloud",false).commit();PlayerActivity activity=(PlayerActivity)instrumentation.startActivitySync(new Intent(c,PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("path",source.getPath()));WatchMusicService.Job job=null;
  try{ExoPlayer player=(ExoPlayer)field(activity,"player");long end=System.currentTimeMillis()+15000;final boolean[] ready={false};while(System.currentTimeMillis()<end){instrumentation.runOnMainSync(()->ready[0]=player.getDuration()>1500);if(ready[0])break;Thread.sleep(50);}assertTrue(ready[0]);Button music=(Button)field(activity,"musicButton");instrumentation.runOnMainSync(()->{player.seekTo(600);player.play();music.performClick();});job=WatchMusicService.current;assertNotNull(job);assertEquals(600000,job.startUs);long deadline=System.currentTimeMillis()+120000;final boolean[] moved={false};while(System.currentTimeMillis()<deadline){assertFalse(job.stage,job.clean.failed);instrumentation.runOnMainSync(()->moved[0]=player.getCurrentPosition()>850);if(job.clean.frames>0&&moved[0])break;Thread.sleep(50);}assertTrue("Actual cleaned playback must start",moved[0]);assertTrue(job.clean.frames>0);assertEquals(600000,job.clean.offsetUs,150);
   CleanAudioController clean=(CleanAudioController)field(activity,"clean");PlayerView view=(PlayerView)field(activity,"view");instrumentation.runOnMainSync(()->{assertSame(player,view.getPlayer());assertEquals(0,player.getVolume(),0);assertTrue(player.getCurrentPosition()<=jobReady(clean,WatchMusicService.current)+150);player.pause();assertFalse(clean.wanted());});long paused=position(player);Thread.sleep(350);assertEquals(paused,position(player),100);instrumentation.runOnMainSync(()->{player.seekTo(0);assertTrue("Clean seeking must not enter the unprocessed prefix",player.getCurrentPosition()>=590);assertEquals(0,player.getVolume(),0);});assertEquals(bytes,source.length());assertEquals(modified,source.lastModified());
  }finally{if(job!=null&&!job.clean.done)c.startService(new Intent(c,WatchMusicService.class).setAction("cancel").putExtra("job",job.id));instrumentation.runOnMainSync(activity::finish);long end=System.currentTimeMillis()+15000;while(WatchMusicService.active&&System.currentTimeMillis()<end)Thread.sleep(50);if(job!=null){if(WatchMusicService.current==job)WatchMusicService.current=null;WatchMusicService.dispose(job);}source.delete();c.getSharedPreferences("player",0).edit().putBoolean("watch_cloud",prior).commit();}
 }
 @Test public void exportPreviewAlsoUsesFullPlayerAndWaitsForCleanPrefix()throws Exception{
  Context c=instrumentation.getTargetContext();File source=fixture(c),pcm=File.createTempFile("full-player-prefix-",".pcm",c.getCacheDir());LiveSeparation.Session session=LiveSeparation.begin(source,true);try(RandomAccessFile out=new RandomAccessFile(pcm,"rw")){out.setLength(44100*4);}LiveSeparation.attach(session,pcm,0);LiveSeparation.publish(session,44100);
  PlayerActivity activity=(PlayerActivity)instrumentation.startActivitySync(new Intent(c,PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("path",source.getPath()).putExtra("separationSession",session.id));
  try{ExoPlayer player=(ExoPlayer)field(activity,"player");Thread.sleep(2500);instrumentation.runOnMainSync(()->{assertEquals(0,player.getVolume(),0);assertTrue(player.getCurrentPosition()>200);assertTrue(player.getCurrentPosition()<=1150);});long before=position(player);try(RandomAccessFile out=new RandomAccessFile(pcm,"rw")){out.setLength(88200*4);}LiveSeparation.publish(session,88200);Thread.sleep(800);instrumentation.runOnMainSync(()->{assertEquals(0,player.getVolume(),0);assertTrue("Clean playback must resume when new PCM arrives",player.getCurrentPosition()>before+200);assertTrue(player.getCurrentPosition()<=2150);});}
  finally{LiveSeparation.end(session,null);instrumentation.runOnMainSync(activity::finish);source.delete();pcm.delete();}
 }
 private long position(ExoPlayer player){final long[] at={0};instrumentation.runOnMainSync(()->at[0]=player.getCurrentPosition());return at[0];}
 private long jobReady(CleanAudioController ignored,WatchMusicService.Job job){return Math.round(job.clean.ready()*1000);}
}
