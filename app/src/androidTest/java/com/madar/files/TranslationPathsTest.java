package com.madar.files;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
/** Basic meaning and timing checks, not a claim of dialect translation accuracy. */
@RunWith(AndroidJUnit4.class)
public class TranslationPathsTest {
 @Test public void japaneseEnglishAndArabicPathsKeepRainAndUmbrellaMeaning()throws Exception{
  for(String from:new String[]{"ja","en","ar"}){String input=from.equals("ja")?"今日は雨が降っています。傘を持って行ってください。":from.equals("en")?"It is raining today. Please take an umbrella.":"الجو ممطر اليوم. خذ مظلة معك.";String to=from.equals("ar")?"ja":"ar";JSONArray cues=new JSONArray().put(new JSONObject().put("start",1.25).put("end",5.5).put("text",input));JSONArray out=LocalTranslation.translate(cues,from,to,new AtomicBoolean(),(p,m)->{});String text=out.getJSONObject(0).getString("text");System.out.println("MADAR_TRANSLATION "+from+"->"+to+": "+text);assertEquals(1.25,out.getJSONObject(0).getDouble("start"),.0001);assertEquals(5.5,out.getJSONObject(0).getDouble("end"),.0001);if(to.equals("ar")){assertTrue("Rain meaning: "+text,text.contains("مطر"));assertTrue("Umbrella meaning: "+text,text.contains("مظلة")||text.contains("شمسية"));}else{assertTrue("Rain meaning: "+text,text.contains("雨"));assertTrue("Umbrella meaning: "+text,text.contains("傘"));}}
  JSONArray original=new JSONArray().put(new JSONObject().put("start",0).put("end",1).put("text","هذا نص عربي كما هو"));assertEquals(original.toString(),LocalTranslation.translate(original,"ar","ar",new AtomicBoolean(),(p,m)->{}).toString());
 }
}
