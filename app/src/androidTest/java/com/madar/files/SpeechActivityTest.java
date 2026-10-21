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
}
