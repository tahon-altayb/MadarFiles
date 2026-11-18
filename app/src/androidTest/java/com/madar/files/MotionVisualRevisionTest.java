package com.madar.files;
import android.content.Context;
import android.graphics.Bitmap;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public class MotionVisualRevisionTest {
 private final Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private JSONObject project()throws Exception{
  JSONObject p=MotionProject.create(c),scene=MotionProject.scene("عنوان محفوظ","هذا نص النسخة السابقة الذي يجب الحفاظ عليه.");scene.put("graphic","creative").put("start",0).put("end",4).put("seconds",4);
  scene.put("composition",new JSONObject("{\"backdrop\":\"plain\",\"elements\":[{\"kind\":\"text\",\"text\":\"عنوان محفوظ\",\"size\":64,\"x\":0.08,\"y\":0.1,\"w\":0.84,\"h\":0.17,\"color\":\"ink\",\"motion\":\"rise\",\"delay\":0},{\"kind\":\"text\",\"text\":\"نص داعم محفوظ\",\"size\":38,\"x\":0.08,\"y\":0.35,\"w\":0.84,\"h\":0.16,\"color\":\"ink\",\"motion\":\"rise\",\"delay\":0.1},{\"kind\":\"icon\",\"icon\":\"book\",\"x\":0.3,\"y\":0.55,\"w\":0.4,\"h\":0.25,\"color\":\"accent\",\"motion\":\"draw\",\"delay\":0.1}]}"));
  p.put("agentThread","visual-review").put("agentTask","old").put("voiceMode","none").put("ready",true).put("duration",4).put("scenes",new JSONArray().put(scene)).put("creativeRequest",new JSONObject().put("text","طلب أصلي محفوظ").put("images",new JSONArray()).put("referenceMap",new JSONObject()));MotionProject.save(c,p);return p;
 }
 private JSONObject request(JSONObject p)throws Exception{
  File image=File.createTempFile("review-image-",".jpg",c.getCacheDir());Bitmap bitmap=Bitmap.createBitmap(180,180,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff986d38);try(OutputStream out=new FileOutputStream(image)){bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);}finally{bitmap.recycle();}
  JSONObject saved=AssistantFiles.store(c,image,new JSONObject().put("kind","image").put("name","مرجع.jpg").put("mime","image/jpeg").put("width",180).put("height",180));image.delete();
  return new JSONObject().put("thread","visual-review").put("task","new").put("previous",p.getString("id")).put("revisionKind","images").put("assetRole","photos").put("voice","none").put("prompt","أضف الصورة فقط").put("attachments",new JSONArray().put(saved.getString("attachmentId")));
 }
 @Test public void visualReviewKeepsCopyAndClocksAndOriginalRevision()throws Exception{
  JSONObject original=project(),r=request(original),revised=MotionRevision.create(c,r);assertNotNull(revised);assertNotEquals(original.getString("id"),revised.getString("id"));assertEquals("render",revised.getString("agentMode"));assertTrue(revised.getBoolean("ready"));assertEquals(original.getJSONArray("scenes").toString(),revised.getJSONArray("scenes").toString());assertEquals(original.getJSONArray("scenes").toString(),MotionProject.load(c,original.getString("id")).getJSONArray("scenes").toString());String name=revised.getJSONArray("visualRevisionAssets").getString(0);assertTrue(MotionProject.asset(c,revised.getString("id"),name).isFile());assertEquals(1,revised.getJSONObject("creativeRequest").getJSONArray("images").length());
 }
 @Test public void voiceChangeMustUseTheFullVoicePipeline()throws Exception{JSONObject p=project();assertNull(MotionRevision.create(c,request(p).put("voice","silma")));}
 @Test public void anotherConversationCannotReviseTheProject()throws Exception{JSONObject p=project();try{MotionRevision.create(c,request(p).put("thread","another-thread"));fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("المحادثة"));}}
}
