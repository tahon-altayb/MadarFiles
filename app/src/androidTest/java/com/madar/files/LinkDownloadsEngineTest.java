package com.madar.files;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.yausername.youtubedl_android.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.*;
import static org.junit.Assert.*;

/** Exercise the actual packaged executables; all media stays on localhost. */
@RunWith(AndroidJUnit4.class)
public class LinkDownloadsEngineTest {
 @Test(timeout=90000) public void bundledPythonAndDownloaderStartWithoutNetwork()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();LinkDownloads.init(c);
  YoutubeDLRequest request=new YoutubeDLRequest("https://youtu.be/unused");request.addOption("--version");String version=YoutubeDL.INSTANCE.execute(request,"madar-engine-smoke",null).getOut().trim();assertTrue("Expected the bundled yt-dlp version, got "+version,version.matches("(?s).*20[0-9]{2}\\.[0-9]{2}\\.[0-9]{2}.*"));
 }
 @Test(timeout=90000) public void downloaderFetchesLocalMediaAndFfmpegProducesPlayableAudio()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();LinkDownloads.init(c);File directory=new File(c.getCacheDir(),"link-smoke-"+System.nanoTime());assertTrue(directory.mkdir());File wave=new File(directory,"tone.wav"),output=new File(directory,"result.mp3");
  int samples=8000,bytes=samples*2;ByteBuffer data=ByteBuffer.allocate(44+bytes).order(ByteOrder.LITTLE_ENDIAN);data.put(new byte[]{'R','I','F','F'}).putInt(36+bytes).put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(bytes);for(int i=0;i<samples;i++)data.putShort((short)(5000*Math.sin(2*Math.PI*440*i/8000)));try(OutputStream out=new FileOutputStream(wave)){out.write(data.array());}
  try(LocalMediaServer server=new LocalMediaServer((route,name)->new LocalMediaServer.Media(wave,"audio/wav"))){YoutubeDLRequest request=LinkDownloads.request(server.base()+"/tone.wav");request.addOption("-x");request.addOption("--audio-format","mp3");request.addOption("-o",new File(directory,"result.%(ext)s").getAbsolutePath());YoutubeDL.INSTANCE.execute(request,"madar-ffmpeg-smoke",null);assertTrue("No completed MP3 was produced",output.isFile()&&output.length()>100);try(MediaMetadataRetriever metadata=new MediaMetadataRetriever()){metadata.setDataSource(output.getAbsolutePath());assertTrue(Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))>=900);}}
  finally{File[] files=directory.listFiles();if(files!=null)for(File f:files)f.delete();directory.delete();}
 }
}
