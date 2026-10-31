package com.madar.files;

import android.content.Context;
import android.graphics.*;
import androidx.media3.common.util.Size;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CensorTimelineTest {
 static JSONObject data(String source,long startMs,long endMs,boolean covered)throws Exception{
  JSONArray frames=new JSONArray();for(long at=startMs*1000;at<endMs*1000;at+=100000){JSONArray boxes=new JSONArray();if(covered)boxes.put(new JSONArray().put(0).put(.25).put(.2).put(.75).put(.8));frames.put(new JSONArray().put(at).put(Math.min(endMs*1000,at+100000)).put(boxes));}
  return new JSONObject().put("protocol",1).put("fingerprint",CensorTimeline.FINGERPRINT).put("source",source).put("provider","cuda").put("core_start_us",startMs*1000).put("core_end_us",endMs*1000).put("decode_start_us",startMs*1000).put("frames",frames);
 }
 private File directory(){Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();return new File(c.getCacheDir(),"censor_qa_"+java.util.UUID.randomUUID());}
 @Test public void unknownAndVerifiedEmptyHaveDifferentMeaning()throws Exception{
  String source="a".repeat(32);try(CensorTimeline timeline=new CensorTimeline(directory(),source)){
   assertNull(timeline.boxesAt(0));timeline.install(data(source,0,8000,false).toString().getBytes(StandardCharsets.UTF_8),0,8000000);
   assertEquals(0,timeline.boxesAt(1000000).length);assertNull(timeline.boxesAt(8000000));assertEquals(8000,timeline.contiguousEnd(1000));
   for(JSONObject bad:new JSONObject[]{data(source,8000,16000,true).put("provider","cpu"),data("b".repeat(32),8000,16000,true),data(source,8000,16000,true).put("fingerprint","legacy")}){
    try{timeline.install(bad.toString().getBytes(StandardCharsets.UTF_8),8000000,16000000);fail();}catch(IOException expected){}
    assertFalse(timeline.has(8000));
   }
  }
 }
 @Test public void motionMarginUnionsTrackPositionsAndRejectsInvalidCoordinates()throws Exception{
  String source="a".repeat(32);JSONObject input=data(source,0,1000,false);JSONArray frames=input.getJSONArray("frames");
  frames.getJSONArray(0).put(2,new JSONArray().put(new JSONArray().put(7).put(.1).put(.1).put(.2).put(.8)));
  frames.getJSONArray(1).put(2,new JSONArray().put(new JSONArray().put(7).put(.5).put(.1).put(.6).put(.8)));
  try(CensorTimeline timeline=new CensorTimeline(directory(),source)){
   timeline.install(input.toString().getBytes(StandardCharsets.UTF_8),0,1000000);double[][] boxes=timeline.boxesAt(100000);assertEquals(1,boxes.length);assertEquals(.1,boxes[0][0],.0001);assertEquals(.6,boxes[0][2],.0001);
   JSONObject bad=data(source,1000,2000,true);bad.getJSONArray("frames").getJSONArray(0).getJSONArray(2).getJSONArray(0).put(1,-.1);
   try{timeline.install(bad.toString().getBytes(StandardCharsets.UTF_8),1000000,2000000);fail();}catch(IOException expected){}assertNull(timeline.boxesAt(1500000));
  }
 }
 @Test public void oldHistoryReloadsWithoutUnboundedMemoryAndTamperedCacheStaysBlocked()throws Exception{
  String source="a".repeat(32);File dir=directory();try(CensorTimeline timeline=new CensorTimeline(dir,source)){
   for(int i=0;i<12;i++)timeline.install(data(source,i*8000,(i+1)*8000,false).toString().getBytes(StandardCharsets.UTF_8),i*8000000L,(i+1)*8000000L);
   assertTrue(timeline.loadedClips()<=8);assertTrue(timeline.has(0));assertNull(timeline.boxesAt(1000));
   long until=android.os.SystemClock.elapsedRealtime()+3000;while(timeline.boxesAt(1000)==null&&android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(10);assertNotNull(timeline.boxesAt(1000));
   // Evict it again, then replace it with well-formed but different mask data.
   for(int i=12;i<22;i++)timeline.install(data(source,i*8000,(i+1)*8000,false).toString().getBytes(StandardCharsets.UTF_8),i*8000000L,(i+1)*8000000L);
   try(FileOutputStream out=new FileOutputStream(new File(dir,"0.json"))){out.write(data(source,0,8000,true).toString().getBytes(StandardCharsets.UTF_8));}
   assertNull(timeline.boxesAt(1000));until=android.os.SystemClock.elapsedRealtime()+3000;while(timeline.failure.isEmpty()&&android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(10);assertFalse(timeline.failure.isEmpty());assertNull(timeline.boxesAt(1000));
  }
 }
 @Test public void frameOverlayCoversUnknownMasksAtRealCoordinatesAndDisablesExplicitly()throws Exception{
  String source="a".repeat(32);try(CensorTimeline timeline=new CensorTimeline(directory(),source)){
   timeline.install(data(source,0,8000,true).toString().getBytes(StandardCharsets.UTF_8),0,8000000);
   CensorOverlay overlay=new CensorOverlay();overlay.configure(new Size(1920,1080));overlay.timeline=timeline;overlay.enabled=true;
   Bitmap mask=overlay.getBitmap(1000000);assertEquals(Color.BLACK,mask.getPixel(mask.getWidth()/2,mask.getHeight()/2));assertEquals(0,Color.alpha(mask.getPixel(0,0)));
   mask=overlay.getBitmap(9000000);assertEquals(Color.BLACK,mask.getPixel(0,0));overlay.enabled=false;mask=overlay.getBitmap(9000000);assertEquals(0,Color.alpha(mask.getPixel(mask.getWidth()/2,mask.getHeight()/2)));
  }
 }
}
