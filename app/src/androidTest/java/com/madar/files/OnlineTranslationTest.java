package com.madar.files;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class OnlineTranslationTest {
 private JSONArray original()throws Exception{return new JSONArray("[{\"start\":1.25,\"end\":3.5,\"text\":\"No fourth time.\",\"language\":\"en\"},{\"start\":4.1,\"end\":6.2,\"text\":\"Do not retreat.\",\"language\":\"en\"}]");}
 @Test public void reorderedResponsePreservesTimingsAndOriginals()throws Exception {
  JSONArray source=original();JSONArray out=OnlineTranslation.validate(source,0,2,new JSONArray("[{\"id\":1,\"text\":\"لا تتراجع.\"},{\"id\":0,\"text\":\"لن تكون هناك مرة رابعة.\"}]"));
  assertEquals("لن تكون هناك مرة رابعة.",out.getJSONObject(0).getString("text"));assertEquals(1.25,out.getJSONObject(0).getDouble("start"),0);assertEquals(6.2,out.getJSONObject(1).getDouble("end"),0);assertEquals("en",out.getJSONObject(0).getString("language"));assertEquals("No fourth time.",source.getJSONObject(0).getString("text"));
 }
 @Test public void rejectsMissingDuplicateForeignAndInvalidLines()throws Exception {
  String[] responses={"[]","[{id:0,text:a},{id:0,text:b}]","[{id:0,text:a},{id:3,text:b}]","[{id:0,text:a},{id:1,text:''}]","[{id:0,text:a},{id:1.5,text:b}]","[{id:0,text:a},{id:1,text:123}]"};
  for(String response:responses){try{OnlineTranslation.validate(original(),0,2,new JSONArray(response));fail(response);}catch(java.io.IOException expected){}}
 }
 @Test public void contextualBatchUsesGlobalIds()throws Exception {
  JSONArray out=OnlineTranslation.validate(original(),1,2,new JSONArray("[{id:1,text:'لا تتراجع.'}]"));assertEquals(4.1,out.getJSONObject(0).getDouble("start"),0);
 }
 @Test public void unknownModeFailsBeforeNetwork()throws Exception {
  try{TranslationEngine.preflight(InstrumentationRegistry.getInstrumentation().getTargetContext(),"automatic");fail();}catch(java.io.IOException expected){}
 }
 @Test public void credentialsAreEncryptedAndRemovable()throws Exception {
  android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();android.content.SharedPreferences prefs=c.getSharedPreferences("cloud_translation",0);String old=prefs.getString("credential",null),model=prefs.getString("model",null);
  try{prefs.edit().clear().commit();assertEquals("",TranslationSettings.credential(c));try{TranslationEngine.preflight(c,"online");fail();}catch(java.io.IOException expected){}TranslationEngine.preflight(c,"local");TranslationSettings.save(c,"test-placeholder-not-a-live-key","gemini-3.8-flash");assertEquals("test-placeholder-not-a-live-key",TranslationSettings.credential(c));assertFalse(prefs.getString("credential","").contains("test-placeholder"));prefs.edit().clear().commit();assertEquals("",TranslationSettings.credential(c));}finally{prefs.edit().clear().putString("credential",old).putString("model",model).commit();}
 }
}
