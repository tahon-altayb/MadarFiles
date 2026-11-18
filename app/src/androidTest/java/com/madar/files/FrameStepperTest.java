package com.madar.files;
import android.content.Context;
import android.media.MediaExtractor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class FrameStepperTest {
 @Test public void stepsUseActualNeighbouringPresentationTimes()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File file=File.createTempFile("frame-step-",".mp4",c.getCacheDir());try{try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("sample.mp4");OutputStream out=new FileOutputStream(file)){FilesUtil.copy(in,out);}TreeSet<Long> times=new TreeSet<>();MediaExtractor extractor=new MediaExtractor();try{extractor.setDataSource(file.getPath());for(int i=0;i<extractor.getTrackCount();i++)if(extractor.getTrackFormat(i).getString("mime").startsWith("video/")){extractor.selectTrack(i);break;}while(extractor.getSampleTime()>=0){times.add(extractor.getSampleTime());if(!extractor.advance())break;}}finally{extractor.release();}List<Long> sorted=new ArrayList<>(times);assertTrue(sorted.size()>5);for(int i:new int[]{1,sorted.size()/2,sorted.size()-2}){assertEquals(sorted.get(i+1)/1000000.0,FrameStepper.step(file,sorted.get(i)/1000000.0,1),.00001);assertEquals(sorted.get(i-1)/1000000.0,FrameStepper.step(file,sorted.get(i)/1000000.0,-1),.00001);}assertEquals(sorted.get(0)/1000000.0,FrameStepper.step(file,sorted.get(0)/1000000.0,-1),.00001);}finally{file.delete();}
 }
}
