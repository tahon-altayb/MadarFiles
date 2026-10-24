package com.madar.files;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class LocalAgentRuntimeTest {
 @Test(timeout=600000) public void nativeAssistantCombinesInferenceWithExplicitDocumentIntent()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();LocalAgent agent=new LocalAgent();AtomicBoolean cancel=new AtomicBoolean();
  try{agent.install(c,cancel,(p,m)->{});assertEquals(LocalAgent.MODEL_SHA,LocalAgent.digest(LocalAgent.model(c)));
   JSONObject convert=agent.plan(c,"ابي احول هالصورة لصيغة png",new JSONObject().put("kind","image").put("width",800).put("height",600),cancel);assertEquals("convert",convert.getString("tool"));boolean png=false;JSONArray fields=convert.getJSONArray("fields");for(int i=0;i<fields.length();i++)if(fields.getJSONObject(i).getString("name").equals("format"))png=fields.getJSONObject(i).getString("value").equals("png");assertTrue("Explicit PNG format must survive inference",png);
   JSONObject word=agent.plan(c,"خلي البي دي اف وورد مع بقاء الجدول قابل للتحرير",new JSONObject().put("kind","pdf").put("pages",2),cancel);assertEquals("layoutWord",word.getString("tool"));
  }finally{agent.cancel();LocalAgent.model(c).delete();}
 }
 @Test public void modelCannotInjectConsentOrAccessAnotherToolKind()throws Exception{
  try{AgentTools.validate(new JSONObject("{\"tool\":\"convert\",\"fields\":[{\"name\":\"onlineAudioConsent\",\"value\":\"true\"}],\"message\":\"\"}"),"image");fail();}catch(java.io.IOException expected){}
  try{AgentTools.validate(new JSONObject("{\"tool\":\"music\",\"fields\":[],\"message\":\"\"}"),"pdf");fail();}catch(java.io.IOException expected){}
 }
}
