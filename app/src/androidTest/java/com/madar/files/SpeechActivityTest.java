package com.madar.files;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class SpeechActivityTest {
 @Test public void silenceAndSteadyToneDoNotBecomeSpeech()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  try(SpeechActivity activity=new SpeechActivity(c)){
   assertEquals(0,activity.regions(new float[3*16000]).length);
   float[] tone=new float[3*16000];for(int i=0;i<tone.length;i++)tone[i]=(float)(.15*Math.sin(2*Math.PI*440*i/16000));
   assertEquals(0,activity.regions(tone).length);
   assertEquals(0,activity.regions(new float[16000]).length);
  }
 }
 @Test public void spokenFixtureHasBoundedSpeechRegions()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File input=File.createTempFile("speech-vad-",".wav",c.getCacheDir());AudioPipeline.Decoded decoded=null;
  try{
   try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("speech-test.wav");OutputStream out=new FileOutputStream(input)){FilesUtil.copy(in,out);}
   decoded=AudioPipeline.decode(c,input,0,new AtomicBoolean(),(p,m)->{});long frames=decoded.pcm.length()/4;int count=(int)Math.min(20*16000,Math.round(frames*16000.0/decoded.sampleRate));
   try(RandomAccessFile pcm=new RandomAccessFile(decoded.pcm,"r");SpeechActivity activity=new SpeechActivity(c)){
    int[] spans=activity.regions(new SpeechResampler(decoded.sampleRate).read(pcm,frames,0,count));assertTrue(spans.length>=2);assertEquals(0,spans.length%2);
    for(int i=0;i<spans.length;i+=2){assertTrue(spans[i]>=0);assertTrue(spans[i+1]>spans[i]);assertTrue(spans[i+1]<=count);}
   }
  }finally{input.delete();if(decoded!=null)decoded.pcm.delete();}
 }
 @Test public void streamingSpeechSurvivesAReadBufferBoundary()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File input=File.createTempFile("speech-stream-",".wav",c.getCacheDir());AudioPipeline.Decoded decoded=null;
  try{
   try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("speech-test.wav");OutputStream out=new FileOutputStream(input)){FilesUtil.copy(in,out);}
   decoded=AudioPipeline.decode(c,input,0,new AtomicBoolean(),(p,m)->{});long frames=decoded.pcm.length()/4;int count=(int)Math.round(frames*16000.0/decoded.sampleRate);float[] audio=new float[17*16000+count];
   try(RandomAccessFile pcm=new RandomAccessFile(decoded.pcm,"r")){System.arraycopy(new SpeechResampler(decoded.sampleRate).read(pcm,frames,0,count),0,audio,17*16000,count);}
   java.util.List<Integer> shortReads=stream(c,audio,512),largeReads=stream(c,audio,20*16000);assertEquals(shortReads,largeReads);assertTrue(largeReads.size()>=2);
   boolean crosses=false;for(int i=0;i+1<largeReads.size();i+=2)if(largeReads.get(i)<20*16000&&largeReads.get(i+1)>20*16000)crosses=true;
   assertTrue("A phrase crossing a read boundary must stay intact",crosses);
   try(SpeechActivity activity=new SpeechActivity(c)){activity.accept(java.util.Arrays.copyOf(audio,Math.min(audio.length,20*16000)),false);activity.restart();assertEquals(0,activity.accept(new float[3*16000],true).length);}
  }finally{input.delete();if(decoded!=null)decoded.pcm.delete();}
 }
 private static java.util.List<Integer> stream(Context c,float[] audio,int size)throws Exception{
  java.util.List<Integer> out=new java.util.ArrayList<>();try(SpeechActivity activity=new SpeechActivity(c)){
   for(int at=0;at<audio.length;at+=size){int[] spans=activity.accept(java.util.Arrays.copyOfRange(audio,at,Math.min(audio.length,at+size)),false);for(int value:spans)out.add(value);}
   for(int value:activity.accept(new float[0],true))out.add(value);
  }return out;
 }
}
