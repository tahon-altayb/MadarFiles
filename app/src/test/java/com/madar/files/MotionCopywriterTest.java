package com.madar.files;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class MotionCopywriterTest {
 private static JSONObject scene(String t,String c,String n)throws Exception{return new JSONObject().put("title",t).put("caption",c).put("narration",n);}
 @Test public void keepsAGoodDraftAndCleansIt()throws Exception{
  JSONObject answer=new JSONObject().put("title","العبور للاتصالات").put("scenes",new JSONArray()
   .put(scene("العبور للاتصالات.","كل اللي يحتاجه جوالك في مكان واحد","أهلًا بكم في العبور للاتصالات، كل اللي يحتاجه جوالك عندنا."))
   .put(scene("ساعات ذكية بضمان سنة","تابع يومك وصحتك من معصمك","ساعاتنا الذكية تجيك بضمان سنة كامل."))
   .put(scene("زورونا في فرعنا","الرياض – حي الملز – شارع الجامعة","ننتظركم في فرعنا بحي الملز.")));
  JSONObject draft=MotionCopywriter.validate(answer,3);assertNotNull(draft);
  assertEquals("العبور للاتصالات",draft.getJSONArray("scenes").getJSONObject(0).getString("title"));assertEquals("groq",draft.getString("writer"));
 }
 @Test public void rejectsWrongCountsLatinWordsAndRepeatedHeadlines()throws Exception{
  JSONArray two=new JSONArray().put(scene("أ ب","ج د","نص عربي طويل بما يكفي")).put(scene("هـ و","ز ح","نص عربي طويل آخر هنا"));
  assertNull(MotionCopywriter.validate(new JSONObject().put("scenes",two),3));
  JSONArray latin=new JSONArray().put(scene("Smart watch","ج د","نص عربي طويل بما يكفي"));assertNull(MotionCopywriter.validate(new JSONObject().put("scenes",latin),1));
  JSONArray same=new JSONArray().put(scene("فطائر طازجة","ج د","نص عربي طويل بما يكفي")).put(scene("فطائر طازجة","ز ح","نص عربي طويل آخر هنا"));assertNull(MotionCopywriter.validate(new JSONObject().put("scenes",same),2));
 }
 @Test public void requestCarriesTheOwnersContract()throws Exception{
  JSONObject contract=new JSONObject().put("sceneCount",6).put("durationSec",30).put("businessName","قوة").put("required",new JSONArray().put("سجل الحين")).put("forbidden",new JSONArray().put("أسعار")).put("address","الرياض");
  JSONObject p=new JSONObject().put("voiceMode","none").put("creativeRequest",new JSONObject().put("text","سوي موشن لنادي قوة").put("images",new JSONArray().put("a")).put("contract",contract));
  JSONObject in=MotionCopywriter.input(p);assertEquals(6,in.getInt("scene_count"));assertEquals("قوة",in.getString("business_name"));assertEquals("سجل الحين",in.getJSONArray("closing_phrases").getString(0));assertEquals("أسعار",in.getJSONArray("never_write").getString(0));assertEquals(1,in.getInt("pictures_attached"));assertEquals("8-13",in.getString("narration_words"));
 }
}
