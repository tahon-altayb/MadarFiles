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
 @Test public void explicitDocumentCommandsHaveStableRoutesAndQuestionsNeverExecute()throws Exception{
  for(String request:new String[]{"حول الملف إلى وورد مع تنسيقه","ابي docx يحافظ على الجداول","convert this PDF to Word","صدر وورد قابل للتحرير","وورد بنفس التخطيط"})assertEquals(request,"layoutWord",AgentTools.explicitDocument(request,"pdf").getString("tool"));
  assertEquals("word",AgentTools.explicitDocument("حول النص فقط إلى وورد","pdf").getString("tool"));
  for(String request:new String[]{"لا تحول الملف لوورد","لاتحول الملف لوورد","كيف احول الملف لوورد؟","هل تحويل الملف لوورد يحذف النص؟","ترجم الملف ثم حوله لوورد","حوله وورد من الصفحة 2","حوله وورد بجيميناي","حوّل الورد إلى صورة"})assertNull(request,AgentTools.explicitDocument(request,"pdf"));
  assertNull(AgentTools.explicitDocument("حوله وورد","image"));
 }
}
