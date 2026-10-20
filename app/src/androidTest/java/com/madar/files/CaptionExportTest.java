package com.madar.files;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercise the real service, encoder and decoded output, not only caption bitmaps. */
@RunWith(AndroidJUnit4.class)
public class CaptionExportTest {
 @Test public void arabicCaptionsAreBurnedOnlyDuringCueAndKeepAudio() throws Exception {
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  Activity activity=InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  File input=File.createTempFile("caption-source-",".mp4",c.getCacheDir()),baseline=null,result=null;
  try {
   try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("sample.mp4");OutputStream out=new FileOutputStream(input)){FilesUtil.copy(in,out);}
   long duration;int height;String audio;
   try(MediaMetadataRetriever r=new MediaMetadataRetriever()){r.setDataSource(input.getPath());duration=Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));height=Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));audio=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);}
   assertTrue("Fixture must have at least 2 seconds",duration>=2000);
   baseline=export(c,input,new JSONObject().put("tool","convert").put("format","mp4"),duration,height);
   JSONObject cue=new JSONObject().put("start",.7).put("end",1.5).put("text","مدار الملفات · ترجمة عربية");
   result=export(c,input,new JSONObject().put("tool","burnCaptions").put("cues",new JSONArray().put(cue)).put("captionPosition","center").put("captionSize",6).put("captionBackground",true),duration,height);
   try(MediaMetadataRetriever a=new MediaMetadataRetriever();MediaMetadataRetriever b=new MediaMetadataRetriever()){
    a.setDataSource(baseline.getPath());b.setDataSource(result.getPath());
    assertEquals(audio,b.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
    long outputDuration=Long.parseLong(b.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));assertTrue("Duration changed",Math.abs(outputDuration-duration)<200);
    double before=difference(a,b,300000),during=difference(a,b,1000000),after=difference(a,b,1800000);
    assertTrue("Missing timed caption pixels: before="+before+", during="+during+", after="+after,during>3&&during>Math.max(before,after)+2);
   }
  } finally {
   if(VideoExportService.active)c.startService(new Intent(c,VideoExportService.class).setAction("cancel"));
   input.delete();if(baseline!=null)baseline.delete();if(result!=null)result.delete();
   InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
  }
 }
 private static File export(Context c,File input,JSONObject options,long duration,int height)throws Exception{
  VideoExportService.lastEvent=null;
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->c.startForegroundService(new Intent(c,VideoExportService.class).putExtra("input",input.getPath()).putExtra("options",options.toString()).putExtra("duration",duration).putExtra("height",height)));
  long deadline=System.currentTimeMillis()+120000;
  while(System.currentTimeMillis()<deadline){String event=VideoExportService.lastEvent;if(event!=null){JSONObject e=new JSONObject(event);if("error".equals(e.optString("type")))fail(event);if("result".equals(e.optString("type"))&&!VideoExportService.active)return FilesUtil.resolve(c,e.getString("name"));}Thread.sleep(100);}
  throw new AssertionError("Caption export timed out");
 }
 private static double difference(MediaMetadataRetriever a,MediaMetadataRetriever b,long time){
  Bitmap x=a.getFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST),y=b.getFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST);assertNotNull(x);assertNotNull(y);
  try{assertEquals(x.getWidth(),y.getWidth());assertEquals(x.getHeight(),y.getHeight());long total=0,count=0;
   for(int row=x.getHeight()/3;row<x.getHeight()*2/3;row+=2)for(int col=x.getWidth()/10;col<x.getWidth()*9/10;col+=2){int p=x.getPixel(col,row),q=y.getPixel(col,row);total+=Math.abs(Color.red(p)-Color.red(q))+Math.abs(Color.green(p)-Color.green(q))+Math.abs(Color.blue(p)-Color.blue(q));count+=3;}
   return (double)total/count;
  }finally{x.recycle();y.recycle();}
 }
}
