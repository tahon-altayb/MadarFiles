package com.madar.files;
import android.app.Activity;
import android.content.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;
public class SeparationPlayerTest {
 @Test public void videoRemainsMutedAndStopsAtCommittedAudioPrefix()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File source=File.createTempFile("watch-video-",".mp4",c.getCacheDir()),pcm=File.createTempFile("watch-audio-",".pcm",c.getCacheDir());
  try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("sample.mp4");OutputStream out=new FileOutputStream(source)){FilesUtil.copy(in,out);}
  LiveSeparation.Session session=LiveSeparation.begin(source,true);try(RandomAccessFile out=new RandomAccessFile(pcm,"rw")){out.setLength(44100*4);}LiveSeparation.attach(session,pcm,0);LiveSeparation.publish(session,44100);
  Activity activity=InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(c,SeparationPlayerActivity.class).putExtra("session",session.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  try{java.lang.reflect.Field f=SeparationPlayerActivity.class.getDeclaredField("video");f.setAccessible(true);ExoPlayer player=(ExoPlayer)f.get(activity);Thread.sleep(3000);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{assertEquals(0,player.getVolume(),0);assertTrue("Clean playback must actually start",player.getCurrentPosition()>200);assertTrue("Playback must not pass clean audio",player.getCurrentPosition()<=1150);});try(RandomAccessFile out=new RandomAccessFile(pcm,"rw")){out.setLength(88200*4);}LiveSeparation.publish(session,88200);Thread.sleep(800);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{assertEquals(0,player.getVolume(),0);assertTrue("Playback must stay within the enlarged prefix",player.getCurrentPosition()<=2150);});
  }finally{LiveSeparation.end(session,null);InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);pcm.delete();source.delete();}
 }
}
