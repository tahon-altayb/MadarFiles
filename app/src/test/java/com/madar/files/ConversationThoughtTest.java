package com.madar.files;

import static org.junit.Assert.*;
import org.junit.Test;

/** The chat brain thinks for real only where it pays; greetings and clear commands are answered at once. */
public class ConversationThoughtTest {
 @Test public void clearRequestsAnswerAtOnce(){
  for(String quick:new String[]{"هلو","السلام عليكم","حولها jpg","أزل الموسيقى من الفيديو","قص من 2 إلى 8 ثوان","سوي موشن لمحلي"})assertFalse(quick,LocalAgent.needsThought(quick));
 }
 @Test public void opinionsPlansAndLongRequestsThink(){
  for(String deep:new String[]{"وش رأيك أخلي الإعلان 15 ثانية ولا 30؟","ايش الأفضل للمحل، عمودي ولا أفقي؟","ليش الصوت طلع واطي؟","اقترح لي خطة لإعلان افتتاح الفرع","قص البداية ثم حسّن الصوت وبعدين صدّر المقطع"})assertTrue(deep,LocalAgent.needsThought(deep));
  StringBuilder longText=new StringBuilder();for(int i=0;i<30;i++)longText.append("أبي الإعلان يكون مرتب ");assertTrue(LocalAgent.needsThought(longText.toString()));
 }
 /** Groq's free tier refused the chat with 413 in 1.9.60: the newest turns that fit are sent, and a long file description is shortened. */
 @Test public void theRequestFitsTheFreeLimit()throws Exception{
  StringBuilder report=new StringBuilder();for(int i=0;i<60;i++)report.append("تم تجهيز الفيلم وهذا تقرير طويل ");
  org.json.JSONArray turns=new org.json.JSONArray();for(int i=0;i<12;i++)turns.put(new org.json.JSONObject().put("role",i%2==0?"user":"assistant").put("text",i==10?"عدل الجملة الأولى":report.toString()));
  org.json.JSONArray fit=LocalAgent.fitting(turns,900);
  assertTrue(fit.length()>=1&&fit.length()<=8);assertTrue(LocalAgent.tokens(fit.toString())<=950);assertEquals(0,LocalAgent.fitting(turns,0).length());
  assertEquals("the newest turns, in order","عدل الجملة الأولى",LocalAgent.fitting(turns,100000).getJSONObject(6).getString("text"));
  for(int i=0;i<fit.length();i++)assertTrue(fit.getJSONObject(i).getString("text").length()<=401);
  org.json.JSONArray scenes=new org.json.JSONArray();for(int i=0;i<12;i++)scenes.put(new org.json.JSONObject().put("title","مشهد "+i).put("narration",report.toString()));
  org.json.JSONObject source=new org.json.JSONObject().put("kind","motion").put("name","film.mp4").put("scenes",scenes).put("brief",report.toString());
  org.json.JSONObject small=LocalAgent.compact(source,300);
  assertEquals("motion",small.getString("kind"));assertTrue(LocalAgent.tokens(small.toString())<1500);assertTrue(LocalAgent.tokens(source.toString())>5000);
  assertEquals(50,LocalAgent.tokens("ا".repeat(100)));assertEquals(100,LocalAgent.tokens("a".repeat(350)));
 }
}
