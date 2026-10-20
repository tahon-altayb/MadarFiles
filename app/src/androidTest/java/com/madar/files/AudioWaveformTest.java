package com.madar.files;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class AudioWaveformTest {
 @Test public void decodedWaveformIsBoundedAndCancellationStopsIt()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File source=File.createTempFile("wave-test-",".mp4",c.getCacheDir());
  try{try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("sample.mp4");OutputStream out=new FileOutputStream(source)){FilesUtil.copy(in,out);}
   JSONObject data=AudioWaveform.read(source,()->false);assertTrue(data.getBoolean("hasAudio"));assertTrue(data.getDouble("duration")>2);JSONArray peaks=data.getJSONArray("peaks");assertTrue(peaks.length()>=32&&peaks.length()<=2400);double max=0;for(int i=0;i<peaks.length();i++){double p=peaks.getDouble(i);assertTrue(Double.isFinite(p)&&p>=0&&p<=1);max=Math.max(max,p);}assertTrue("Fixture sound was not decoded",max>0);
   boolean stopped=false;try{AudioWaveform.read(source,()->true);}catch(java.util.concurrent.CancellationException expected){stopped=true;}assertTrue(stopped);
  }finally{source.delete();}
 }
 @Test public void envelopePreservesTimestampsAndStereoPeaks(){
  ByteBuffer pcm=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);for(short v:new short[]{0,16384,-24576,0,0,Short.MIN_VALUE,0,0})pcm.putShort(v);pcm.flip();float[] peaks=new float[4];AudioWaveform.accumulate(peaks,pcm,2,4,false,0,1000000);assertArrayEquals(new float[]{.5f,.75f,1,0},peaks,.001f);
  ByteBuffer floating=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);floating.putFloat(Float.NaN).putFloat(-.25f).putFloat(2).putFloat(0).flip();float[] delayed=new float[4];AudioWaveform.accumulate(delayed,floating,1,4,true,500000,1000000);assertArrayEquals(new float[]{0,0,0,.25f},delayed,.001f);
 }
}
