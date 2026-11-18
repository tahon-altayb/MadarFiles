package com.madar.files;
import android.content.*;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;import org.junit.runner.RunWith;
import java.io.*;import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CensorVideoWindowTest {
 private void check(String name,int width,int height,boolean portrait)throws Exception{
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();File input=File.createTempFile("censor_range_",".mp4",context.getCacheDir()),result=null;
  try{
   try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name);OutputStream out=new FileOutputStream(input)){FilesUtil.copy(in,out);}
   PlayerInput source=PlayerInput.read(context,new Intent().putExtra("path",input.getPath()));result=CensorVideoWindow.extract(context,source,1000,2000,width,height,new AtomicBoolean());
   try(MediaMetadataRetriever media=new MediaMetadataRetriever()){
    media.setDataSource(result.getPath());assertNotEquals("yes",media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));long duration=Long.parseLong(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));assertTrue(duration>=850&&duration<=1150);
    Bitmap frame=media.getFrameAtTime(300000,MediaMetadataRetriever.OPTION_CLOSEST);assertNotNull(frame);int pixel=frame.getPixel(frame.getWidth()/2,frame.getHeight()/2);assertTrue("The clipped window must be from the green second, not the red/blue seconds",Color.green(pixel)>80&&Color.red(pixel)<60&&Color.blue(pixel)<60);
    int rotation=Integer.parseInt(java.util.Objects.toString(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),"0"));int codedWidth=Integer.parseInt(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)),codedHeight=Integer.parseInt(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));int displayWidth=rotation%180==0?codedWidth:codedHeight,displayHeight=rotation%180==0?codedHeight:codedWidth;
    System.out.println("CENSOR_WINDOW coded="+codedWidth+"x"+codedHeight+" rotation="+rotation+" display="+displayWidth+"x"+displayHeight);if(portrait)assertTrue("Display orientation includes the MP4 rotation metadata",displayHeight>displayWidth);frame.recycle();
   }
  }finally{input.delete();if(result!=null)result.delete();}
 }
 @Test(timeout=120000) public void clippedVideoUsesCorrectSourceTimeAndNeverUploadsAudio()throws Exception{check("censor-time.mp4",160,90,false);}
 @Test(timeout=120000) public void rotationIsKeptConsistentWithDisplayedCoordinates()throws Exception{check("censor-time-rotated.mp4",90,160,true);}
}
