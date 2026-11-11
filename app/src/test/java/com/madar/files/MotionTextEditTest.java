package com.madar.files;

import static org.junit.Assert.*;
import org.json.*;
import org.junit.Test;

public class MotionTextEditTest {
 static JSONArray film()throws Exception{
  JSONArray scenes=new JSONArray();
  scenes.put(scene("شاي على الجمر","شاهي جمر يُحضّر أمامك"));
  scenes.put(scene("وجبة خفيفة","تناول وجبة خفيفة من بطاطسنا المقرمشة واللحوم المشوية"));
  scenes.put(scene("زورونا في فرعنا","شارع الجال - مقابل مربع الزينة الجديدة"));
  return scenes;
 }
 static JSONObject scene(String title,String caption)throws Exception{
  JSONArray e=new JSONArray().put(new JSONObject().put("kind","text").put("text",title).put("textRole","heading").put("size",104))
   .put(new JSONObject().put("kind","icon").put("icon","tea")).put(new JSONObject().put("kind","text").put("text",caption).put("size",56));
  return new JSONObject().put("title",title).put("narration",caption).put("graphic","creative").put("composition",new JSONObject().put("elements",e));
 }
 @Test public void replacesOnlyTheQuotedText()throws Exception{
  JSONArray s=film();
  MotionTextEdit.Match m=MotionTextEdit.apply(s,"تناول وجبة خفيفة من بطاطسنا المقرمشة واللحوم المشوية","تناول وجبة خفيفة من بطاطسنا المقرمشة",false);
  assertNotNull(m);assertEquals(1,m.scene);
  assertEquals("تناول وجبة خفيفة من بطاطسنا المقرمشة",s.getJSONObject(1).getJSONObject("composition").getJSONArray("elements").getJSONObject(2).getString("text"));
  assertEquals("وجبة خفيفة",s.getJSONObject(1).getString("title"));
  assertEquals("شاهي جمر يُحضّر أمامك",s.getJSONObject(0).getJSONObject("composition").getJSONArray("elements").getJSONObject(2).getString("text"));
 }
 @Test public void toleratesSpellingAndRemovesWords()throws Exception{
  JSONArray s=film();
  MotionTextEdit.Match m=MotionTextEdit.apply(s,"اللحوم المشويه","",false);
  assertNotNull(m);assertEquals("تناول وجبة خفيفة من بطاطسنا المقرمشة",m.after);
  JSONArray t=film();MotionTextEdit.Match h=MotionTextEdit.apply(t,"شاي علي الجمر","شاهي على الجمر",false);
  assertNotNull(h);assertTrue(h.heading);assertEquals("شاهي على الجمر",t.getJSONObject(0).getString("title"));
 }
 @Test public void unknownTextOrSpokenNarrationFallsBack()throws Exception{
  assertNull(MotionTextEdit.apply(film(),"خصم خمسين بالمية على الكيك","عرض",false));
  assertNull(MotionTextEdit.apply(film(),"تناول وجبة خفيفة من بطاطسنا المقرمشة واللحوم المشوية","تناول وجبة خفيفة",true));
 }
}
