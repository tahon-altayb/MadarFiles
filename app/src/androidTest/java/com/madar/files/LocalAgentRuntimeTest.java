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
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();android.content.SharedPreferences preferences=c.getSharedPreferences("local_agent",0);boolean hadOffline=preferences.contains("offline"),wasOffline=preferences.getBoolean("offline",false);preferences.edit().putBoolean("offline",true).commit();LocalAgent agent=new LocalAgent();AtomicBoolean cancel=new AtomicBoolean();
  try{agent.install(c,cancel,(p,m)->{});assertEquals(LocalAgent.MODEL_SHA,LocalAgent.digest(LocalAgent.model(c)));
   JSONObject convert=agent.plan(c,"ابي احول هالصورة لصيغة png",new JSONObject().put("kind","image").put("width",800).put("height",600),cancel);assertEquals("convert",convert.getString("tool"));boolean png=false;JSONArray fields=convert.getJSONArray("fields");for(int i=0;i<fields.length();i++)if(fields.getJSONObject(i).getString("name").equals("format"))png=fields.getJSONObject(i).getString("value").equals("png");assertTrue("Explicit PNG format must survive inference",png);
   JSONObject word=agent.plan(c,"خلي البي دي اف وورد مع بقاء الجدول قابل للتحرير",new JSONObject().put("kind","pdf").put("pages",2),cancel);assertEquals("layoutWord",word.getString("tool"));
   JSONObject montage=agent.plan(c,"أضف النص مرحبا إلى الفيديو من الثانية 2 إلى الثانية 5",new JSONObject().put("kind","video").put("duration",10000).put("currentTool","editor").put("editedDuration",10),cancel);assertEquals("editor",montage.getString("tool"));JSONArray actions=montage.getJSONArray("actions");assertTrue(actions.length()>0);JSONObject overlay=actions.getJSONObject(0);assertEquals("addText",overlay.getString("action"));assertEquals(2,overlay.getDouble("start"),.001);assertEquals(5,overlay.getDouble("end"),.001);assertEquals("مرحبا",overlay.getString("text"));
  }finally{agent.cancel();LocalAgent.model(c).delete();android.content.SharedPreferences.Editor restore=preferences.edit();if(hadOffline)restore.putBoolean("offline",wasOffline);else restore.remove("offline");restore.commit();}
 }
 @Test public void modelCannotInjectConsentOrAccessAnotherToolKind()throws Exception{
  try{AgentTools.validate(new JSONObject("{\"tool\":\"convert\",\"fields\":[{\"name\":\"onlineAudioConsent\",\"value\":\"true\"}],\"message\":\"\"}"),"image");fail();}catch(java.io.IOException expected){}
  try{AgentTools.validate(new JSONObject("{\"tool\":\"music\",\"fields\":[],\"message\":\"\"}"),"pdf");fail();}catch(java.io.IOException expected){}
 }
}
