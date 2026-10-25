package com.madar.files;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AgentIntentsTest {
 private JSONObject video(long duration)throws Exception{return new JSONObject().put("kind","video").put("duration",duration);}
 @Test public void userReportedMusicConversationNeedsNoTiming()throws Exception{
  for(String request:new String[]{"ازل الموسيقى منه","إزالة الموسيقى من المقطع","شيل الموسيقى","احذف الموسيقى من الفيديو"}){
   JSONObject p=AgentTools.validate(AgentIntents.resolve(request,video(45000),new JSONArray()),"video");assertEquals("music",p.getString("tool"));assertFalse(p.toString().contains("\"start\""));assertFalse(p.toString().contains("\"end\""));assertTrue(p.toString().contains("video"));
  }
  JSONArray history=new JSONArray().put(new JSONObject().put("role","user").put("text","ازل الموسيقى منه")).put(new JSONObject().put("role","assistant").put("text","حدد البداية والنهاية"));
  assertEquals("music",AgentIntents.resolve("كامل المقطع",video(45000),history).getString("tool"));
 }
 @Test public void longMusicUsesCloudAndArabicDigitsTrimCorrectly()throws Exception{
  assertTrue(AgentIntents.resolve("شيل الموسيقى",video(120000),null).toString().contains("colab"));
  JSONObject p=AgentIntents.resolve("قص من ٢ إلى ٨ ثوان",video(30000),null);assertEquals("trim",p.getString("tool"));assertEquals("2",p.getJSONArray("fields").getJSONObject(0).getString("value"));assertEquals("8",p.getJSONArray("fields").getJSONObject(1).getString("value"));
 }
 @Test public void negationQuestionsAndMultipleStepsAreNotSilentlyExecuted()throws Exception{
  for(String request:new String[]{"لا تحذف الموسيقى","ما ابي ازالة الموسيقى","كيف ازيل الموسيقى؟","هل تقدر تشيل الموسيقى؟","شيل الموسيقى ثم قص من 2 إلى 5"})assertNull(request,AgentIntents.resolve(request,video(30000),null));
 }
 @Test public void imageCutoutUsesGeneralModel()throws Exception{JSONObject p=AgentIntents.resolve("ازل الخلفية",new JSONObject().put("kind","image"),null);assertEquals("removebg",p.getString("tool"));assertTrue(p.toString().contains("general"));}
 @Test public void onlyPlatformHttpsUrlsAreAccepted()throws Exception{
  assertEquals("https://youtu.be/abc",LinkDownloads.url("هذا مقطع https://youtu.be/abc"));
  for(String bad:new String[]{"https://youtube.com.evil.example/test","http://youtube.com/test","https://user@youtube.com/test","https://127.0.0.1/test","https://youtube.com:8443/test"})try{LinkDownloads.url(bad);fail(bad);}catch(java.io.IOException expected){}
 }
 @Test public void workflowUsesTheRealOutputKind()throws Exception{JSONObject step=AgentIntents.plan("sound","volume","75");step.remove("message");JSONObject chain=AgentIntents.plan("audio").put("steps",new JSONArray().put(step));AgentTools.validate(chain,"video");step.put("tool","removebg");try{AgentTools.validate(chain,"video");fail("audio cannot feed image isolation");}catch(java.io.IOException expected){}step.put("tool","deleteRecent");try{AgentTools.validate(chain,"video");fail("no deletion inside workflow");}catch(java.io.IOException expected){}}

}
