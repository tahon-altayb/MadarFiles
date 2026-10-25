package com.madar.files;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AgentIntentUnitTest {
 private JSONObject video(long millis)throws Exception{return new JSONObject().put("kind","video").put("duration",millis);}
 @Test public void reportedMusicRequestsExecuteOnWholeFile()throws Exception{
  for(String text:new String[]{"ازل الموسيقى منه","ازالة الموسيقى من المقطع","شيل الموسيقى"}){JSONObject p=AgentIntents.resolve(text,video(45000),null);assertEquals("music",AgentTools.validate(p,"video").getString("tool"));assertFalse(p.toString().contains("\"start\""));assertFalse(p.toString().contains("\"end\""));}
  JSONArray h=new JSONArray().put(new JSONObject().put("role","user").put("text","ازل الموسيقى منه")).put(new JSONObject().put("role","assistant").put("text","ما البداية والنهاية؟"));assertEquals("music",AgentIntents.resolve("كامل المقطع",video(45000),h).getString("tool"));assertTrue(AgentIntents.resolve("شيل الموسيقى",video(65000),null).toString().contains("colab"));
 }
 @Test public void arabicDigitsResolveAndQuestionsDoNotExecute()throws Exception{JSONObject p=AgentIntents.resolve("قص من ٢ إلى ٨ ثوان",video(30000),null);assertEquals("trim",p.getString("tool"));assertEquals("2",p.getJSONArray("fields").getJSONObject(0).getString("value"));assertEquals("8",p.getJSONArray("fields").getJSONObject(1).getString("value"));for(String t:new String[]{"لا تحذف الموسيقى","ما ابي ازالة الموسيقى","كيف ازيل الموسيقى؟","شيل الموسيقى ثم قص من 2 إلى 5"})assertNull(t,AgentIntents.resolve(t,video(30000),null));}
 @Test public void workflowValidatesTransitionAndDisallowsDeletion()throws Exception{JSONObject step=AgentIntents.plan("sound","volume","75");step.remove("message");JSONObject chain=AgentIntents.plan("audio").put("steps",new JSONArray().put(step));AgentTools.validate(chain,"video");step.put("tool","removebg");try{AgentTools.validate(chain,"video");fail();}catch(java.io.IOException expected){}step.put("tool","deleteRecent");try{AgentTools.validate(chain,"video");fail();}catch(java.io.IOException expected){}assertTrue(AgentTools.schema("video").getJSONObject("properties").has("steps"));}
 @Test public void qrForAttachedFileDoesNotAskForManualLink()throws Exception{JSONObject p=AgentIntents.resolve("سوي QR للصورة",new JSONObject().put("kind","image"),null);assertEquals("qr",AgentTools.validate(p,"image").getString("tool"));assertEquals("image",p.getJSONArray("fields").getJSONObject(0).getString("value"));}
 @Test public void downloadLinkIsAvailableWithoutFileAndKeepsAudioRequest()throws Exception{JSONObject p=AgentIntents.resolve("حمل الصوت فقط https://youtu.be/example",new JSONObject(),null);assertEquals("downloadLink",AgentTools.validate(p,"").getString("tool"));assertTrue(p.toString().contains("audio"));assertTrue(p.toString().contains("https://youtu.be/example"));}

}
