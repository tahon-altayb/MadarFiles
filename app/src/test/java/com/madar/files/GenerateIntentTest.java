package com.madar.files;
import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class GenerateIntentTest {
 private static Map<String,String> fields(JSONObject plan)throws Exception{Map<String,String> out=new HashMap<>();JSONArray a=plan.getJSONArray("fields");for(int i=0;i<a.length();i++)out.put(a.getJSONObject(i).getString("name"),a.getJSONObject(i).getString("value"));return out;}
 private static JSONObject none(){return new JSONObject();}

 @Test public void ideasBecomeGenerationWithStyleAndShape()throws Exception{
  JSONObject p=AgentIntents.resolve("ولّد لي مقطع انمي لولد يركض في الصحراء وقت الغروب",none(),null);assertEquals("colabGenerate",AgentTools.validate(p,"").getString("tool"));Map<String,String> f=fields(p);assertEquals("video",f.get("genMode"));assertEquals("anime",f.get("genStyle"));assertEquals("9:16",f.get("genAspect"));assertTrue(f.get("genIdea").contains("الصحراء"));
  f=fields(AgentIntents.resolve("ارسم صورة سينمائية لصقر فوق برج المملكة لليوتيوب",none(),null));assertEquals("image",f.get("genMode"));assertEquals("cinematic",f.get("genStyle"));assertEquals("16:9",f.get("genAspect"));
  f=fields(AgentIntents.resolve("ابي صورة كرتون لقطوة تشرب قهوة",none(),null));assertEquals("image",f.get("genMode"));assertEquals("cartoon",f.get("genStyle"));
  f=fields(AgentIntents.resolve("generate a video of waves hitting rocks",none(),null));assertEquals("video",f.get("genMode"));assertEquals("realistic",f.get("genStyle"));
 }
 @Test public void advertsEditsAndQuestionsAreNotGeneration()throws Exception{
  JSONObject motion=AgentIntents.resolve("سوي موشن جرافيك لمحلي",none(),null);assertEquals("motionStudio",motion.getString("tool"));
  assertNull(AgentIntents.resolve("كيف أولد فيديو انمي؟",none(),null));
  JSONObject edit=AgentIntents.resolve("ولد صورة من هذي الصورة المرفقة",new JSONObject().put("kind","image"),null);assertTrue(edit==null||!edit.getString("tool").equals("colabGenerate"));
  JSONObject montage=AgentIntents.resolve("سوي مقطع قصير من الفيديو",new JSONObject().put("kind","video").put("duration",30000),null);assertTrue(montage==null||!montage.getString("tool").equals("colabGenerate"));
 }
 @Test public void plannerFieldsAreBounded()throws Exception{
  AgentTools.validate(AgentIntents.plan("colabGenerate","genMode","video","genPrompt","a falcon over dunes","genSeconds","4.5","genAspect","1:1"),"image");
  for(String[] bad:new String[][]{{"genMode","audio"},{"genStyle","oil"},{"genAspect","4:3"},{"genSeconds","12"},{"voice","x"}}){try{AgentTools.validate(AgentIntents.plan("colabGenerate",bad[0],bad[1]),"");fail(bad[0]);}catch(java.io.IOException expected){}}
 }
 @Test public void cloudRequestIsValidatedAndFailuresReadable()throws Exception{
  JSONObject r=GenerateCloud.request(new JSONObject().put("mode","video").put("prompt","a falcon over dunes").put("style","oil").put("aspect","4:3").put("seconds",30));assertEquals("realistic",r.getString("style"));assertEquals("9:16",r.getString("aspect"));assertEquals(5.0,r.getDouble("seconds"),0);assertEquals(1,r.getInt("protocol"));
  for(JSONObject bad:new JSONObject[]{new JSONObject().put("mode","audio").put("prompt","a cat"),new JSONObject().put("mode","image").put("prompt","x")}){try{GenerateCloud.request(bad);fail();}catch(java.io.IOException expected){}}
  assertTrue(GenerateCloud.failure("GEN_VIDEO_NEEDS_L4").contains("L4"));assertTrue(GenerateCloud.failure("weird code!").contains("GEN_FAILED"));
  assertTrue(GeneratePrompt.english("a falcon over desert dunes"));assertFalse(GeneratePrompt.english("صقر فوق الكثبان"));assertFalse(GeneratePrompt.english("123"));
 }
}
