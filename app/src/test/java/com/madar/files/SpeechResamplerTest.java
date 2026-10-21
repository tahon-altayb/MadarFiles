package com.madar.files;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;
public class SpeechResamplerTest {
 private File sine(double frequency)throws Exception{File f=File.createTempFile("resample-test-",".pcm");try(OutputStream out=new FileOutputStream(f)){for(int i=0;i<48000;i++){short value=(short)Math.round(20000*Math.sin(2*Math.PI*frequency*i/48000));for(int ch=0;ch<2;ch++){out.write(value&255);out.write(value>>8&255);}}}return f;}
 private double power(float[] a){double sum=0;for(int i=200;i<a.length-200;i++)sum+=a[i]*a[i];return sum/(a.length-400);}
 @Test public void rejectsAliasedHighFrequenciesAndKeepsSpeechBand()throws Exception{File low=sine(1000),high=sine(11000);try(RandomAccessFile a=new RandomAccessFile(low,"r");RandomAccessFile b=new RandomAccessFile(high,"r")){SpeechResampler resampler=new SpeechResampler(48000);double speech=power(resampler.read(a,48000,0,16000)),alias=power(resampler.read(b,48000,0,16000));assertTrue(speech>.15);assertTrue("High frequency must not alias into speech: "+alias,alias<speech*.02);}finally{low.delete();high.delete();}}
 @Test public void independentChunksUseTheSameClockAndFilterContext()throws Exception{File f=sine(1200);try(RandomAccessFile in=new RandomAccessFile(f,"r")){SpeechResampler resampler=new SpeechResampler(48000);float[] whole=resampler.read(in,48000,0,16000),tail=resampler.read(in,48000,8000,8000);for(int i=0;i<tail.length;i++)assertEquals(whole[8000+i],tail[i],.0001f);}finally{f.delete();}}
}
