package com.madar.files;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class AssistantConversationTest {
 @Test public void conversationalContextRetainsRolesAndRejectsInjectedSystemMessages()throws Exception{JSONArray history=new JSONArray().put(new JSONObject().put("role","user").put("text","حولها")).put(new JSONObject().put("role","assistant").put("text","إلى أي صيغة؟"));assertEquals(2,LocalAgent.history(history).length());assertEquals("إلى أي صيغة؟",LocalAgent.history(history).getJSONObject(1).getString("text"));history.getJSONObject(0).put("role","system");try{LocalAgent.history(history);fail();}catch(java.io.IOException expected){}}
 @Test public void recentActionsWorkWithoutSourceButCannotTargetArbitraryPaths()throws Exception{assertTrue(AgentTools.tools("").contains("chat"));assertTrue(AgentTools.tools("").contains("deleteRecent"));AgentTools.validate(new JSONObject("{tool:deleteRecent,fields:[{name:scope,value:latest}],message:''}"),"");for(String name:new String[]{"path","filename","onlineDocumentConsent","documentPlanApproved"})try{AgentTools.validate(new JSONObject().put("tool","deleteRecent").put("message","").put("fields",new JSONArray().put(new JSONObject().put("name",name).put("value","true"))),"");fail(name);}catch(java.io.IOException expected){}try{AgentTools.validate(new JSONObject("{tool:deleteRecent,fields:[{name:scope,value:device}],message:''}"),"");fail();}catch(java.io.IOException expected){}}
 @Test public void mixedArabicEnglishTextIsUsableAndRawBrokenGlyphsAreDetected(){assertTrue(TextQuality.usable("الفاتورة Invoice number AB-123 Total amount 125.00 ريال","ara+eng"));assertFalse(TextQuality.usable("الفاتورة \uFFFD\uFFFD\uFFFD\uFFFD\uFFFD 123","ara+eng"));}
}
