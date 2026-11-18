package com.madar.files;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SpeechAudioTest {
 private final Context context=ApplicationProvider.getApplicationContext();
 private File pcm(int bytes)throws Exception{File f=File.createTempFile("speech-test-",".pcm",context.getCacheDir());try(RandomAccessFile out=new RandomAccessFile(f,"rw")){out.setLength(bytes);}return f;}
 @Test public void successfulPreparationRetainsOffsetAndDurationAndDeletesOriginalPcm()throws Exception{
  File original=pcm(48000*4),clean=pcm(44100*4);AtomicBoolean cancel=new AtomicBoolean();
  AudioPipeline.Decoded out=SpeechAudio.prepare(new AudioPipeline.Decoded(original,48000,123456),cancel,(p,m)->{},audio->{assertTrue(original.exists());return clean;});
  try{assertSame(clean,out.pcm);assertEquals(44100,out.sampleRate);assertEquals(123456,out.offsetUs);assertFalse(original.exists());}finally{clean.delete();original.delete();}
 }
 @Test public void failedOrIncompleteSeparationKeepsOriginalAndWarns()throws Exception{
  File original=pcm(48000*4),bad=pcm(44100*4-4);List<String> warnings=new ArrayList<>();
  AudioPipeline.Decoded source=new AudioPipeline.Decoded(original,48000,500);
  try{
   assertSame(source,SpeechAudio.prepare(source,new AtomicBoolean(),(p,m)->warnings.add(m),audio->bad));assertFalse(bad.exists());assertTrue(original.exists());
   assertSame(source,SpeechAudio.prepare(source,new AtomicBoolean(),(p,m)->warnings.add(m),audio->{throw new IOException("failure");}));
   assertSame(source,SpeechAudio.prepare(source,new AtomicBoolean(),(p,m)->warnings.add(m),audio->{throw new OutOfMemoryError();}));
   assertSame(source,SpeechAudio.prepare(source,new AtomicBoolean(),(p,m)->warnings.add(m),audio->original));
   assertEquals(4,warnings.size());for(String warning:warnings)assertEquals(SpeechAudio.FALLBACK,warning);
  }finally{original.delete();bad.delete();}
 }
 @Test public void cancellationDoesNotFallBackOrLeakSeparatedPcm()throws Exception{
  File original=pcm(16000*4),clean=pcm(44100*4);AtomicBoolean cancel=new AtomicBoolean();
  try{
   try{SpeechAudio.prepare(new AudioPipeline.Decoded(original,16000,0),cancel,(p,m)->fail("Cancellation must not resume ASR"),audio->{cancel.set(true);return clean;});fail("Expected cancellation");}catch(CancellationException expected){}
   assertTrue(original.exists());assertFalse(clean.exists());
  }finally{original.delete();clean.delete();}
 }
 @Test public void bundledVocalModelPreparesSampleWithoutChangingSource()throws Exception{
  File source=File.createTempFile("speech-source-",".mp4",context.getCacheDir());AudioPipeline.Decoded audio=null;
  try{
   try(InputStream in=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("sample.mp4");OutputStream out=new FileOutputStream(source)){FilesUtil.copy(in,out);}
   long length=source.length();List<String> messages=new ArrayList<>();
   audio=SpeechAudio.decode(context,source,1000000L,true,new AtomicBoolean(),(p,m)->messages.add(m));
   assertEquals(44100,audio.sampleRate);assertTrue(audio.pcm.length()>0);assertEquals(length,source.length());
   assertFalse(messages.contains(SpeechAudio.FALLBACK));
   assertTrue(messages.stream().anyMatch(m->m.startsWith("توضيح الكلام قبل التفريغ")));
  }finally{if(audio!=null)audio.pcm.delete();source.delete();}
 }
}
