package com.madar.files;
import android.app.Activity;
import android.content.*;
import android.graphics.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class ImageBatchTest {
 @Test public void checkpointSkipsCompletedImagesAndProcessesRemaining()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();Activity activity=InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));File input=File.createTempFile("batch-test-",".png",c.getCacheDir());File result=null;
  try{
   ImageBatchQueue.clear(c);Bitmap bitmap=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.RED);try{ImageProcessor.writeImage(bitmap,input,"png",100);}finally{bitmap.recycle();}
   JSONObject state=ImageBatchQueue.create(c,Arrays.asList(input,input),new JSONArray().put("first.png").put("second.png"),new JSONObject().put("tool","targetImage").put("targetKb",20));
   JSONObject done=state.getJSONArray("jobs").getJSONObject(0);ImageBatchQueue.input(c,done.getString("input")).delete();done.put("status","done");ImageBatchQueue.save(c,state);
   JSONObject restored=ImageBatchQueue.read(c);assertEquals("done",restored.getJSONArray("jobs").getJSONObject(0).getString("status"));assertEquals("pending",restored.getJSONArray("jobs").getJSONObject(1).getString("status"));assertFalse(ImageBatchQueue.view(restored,false).getJSONArray("jobs").getJSONObject(1).has("input"));
   c.startForegroundService(new Intent(c,ImageBatchService.class));long deadline=System.currentTimeMillis()+20000;boolean complete=false;
   while(System.currentTimeMillis()<deadline){restored=ImageBatchQueue.read(c);if("complete".equals(restored.getString("status"))){complete=true;break;}Thread.sleep(100);}
   assertTrue("Batch did not complete",complete);JSONObject second=restored.getJSONArray("jobs").getJSONObject(1);assertEquals("done",second.getString("status"));result=FilesUtil.resolve(c,second.getJSONObject("result").getString("name"));assertTrue(result.length()<=20*1024);assertFalse("Completed job must not run again",restored.getJSONArray("jobs").getJSONObject(0).has("result"));
  }finally{input.delete();if(result!=null)result.delete();if(!ImageBatchService.active)ImageBatchQueue.clear(c);InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);}
 }
 @Test public void unknownBatchOperationIsRejected()throws Exception{boolean rejected=false;try{ImageBatchQueue.validate(new JSONObject().put("tool","music"));}catch(IOException expected){rejected=true;}assertTrue(rejected);}
}
