package com.madar.files;

import android.content.Context;
import org.json.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SDXL and Wan read English prompts. The owner writes his idea in Arabic (often Saudi dialect), so a
 * text model rewrites it once as a faithful, visual English prompt: every stated detail kept, nothing
 * added that changes the meaning, no text or logos requested inside the picture.
 */
final class GeneratePrompt {
 static final String SYSTEM="You write prompts for open text-to-image (SDXL) and text-to-video (Wan 2.2) models. "
  +"Rewrite the user's idea (Arabic, Saudi/Gulf dialect, or mixed) as ONE vivid English prompt of 25-80 words: subject, action, setting, lighting, camera framing"
  +" and, for video, the motion and camera movement over a few seconds. Keep every explicit detail (who, what, colours, clothing, place, time of day, mood, style)."
  +" Do not invent brand names, readable text, captions, logos or extra people the user did not ask for. Arabic or Saudi cultural details stay accurate"
  +" (for example thobe and shemagh, abaya, dallah coffee pot, desert dunes, Najdi mud architecture). Never add sexual content, gore, real public figures or minors in unsafe contexts."
  +" Also return a short negative prompt of things to avoid that the user mentioned (\"without X\"), or an empty string.";

 static boolean english(String value){return value!=null&&value.matches("(?s)[\\p{ASCII}]{3,1500}")&&value.matches("(?s).*[A-Za-z]{3,}.*");}

 static String clean(String value){return value==null?"":value.replaceAll("[\\p{Cntrl}&&[^\\n]]"," ").replaceAll("\\s+"," ").trim();}

 /** Returns {prompt, negative}. A ready English prompt from the planner is used as is. */
 static JSONObject resolve(Context c,String idea,String ready,String mode,AtomicBoolean cancel)throws Exception{
  ready=clean(ready);idea=clean(idea);
  if(english(ready))return new JSONObject().put("prompt",ready.length()>1500?ready.substring(0,1500):ready).put("negative","");
  if(idea.length()<2)throw new IOException("صف اللي تبي أولّده");
  if(english(idea))return new JSONObject().put("prompt",idea).put("negative","");
  String user="Mode: "+("video".equals(mode)?"a 5-second video clip":"a single image")+"\nIdea: "+(idea.length()>1500?idea.substring(0,1500):idea);
  JSONObject answer=null;Exception last=null;
  if(GroqClient.configured(c))try{answer=GroqClient.json(c,SYSTEM,user,new JSONObject("{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"},\"negative\":{\"type\":\"string\"}},\"required\":[\"prompt\",\"negative\"]}"),700,cancel);}catch(java.util.concurrent.CancellationException stop){throw stop;}catch(Exception e){last=e;}
  if(answer==null&&GeminiClient.configured(c))try{
   JSONObject body=new JSONObject().put("systemInstruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",SYSTEM))))
    .put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text",user)))))
    .put("generationConfig",new JSONObject().put("maxOutputTokens",700).put("responseMimeType","application/json").put("responseSchema",new JSONObject("{\"type\":\"OBJECT\",\"properties\":{\"prompt\":{\"type\":\"STRING\"},\"negative\":{\"type\":\"STRING\"}},\"required\":[\"prompt\",\"negative\"]}")));
   JSONObject response=GeminiClient.generate(c,body,cancel);
   answer=new JSONObject(response.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text"));
  }catch(java.util.concurrent.CancellationException stop){throw stop;}catch(Exception e){last=e;}
  if(answer==null)throw new IOException("تعذر تحويل فكرتك لوصف التوليد"+(last==null?"؛ أضف مفتاح Groq من الإعدادات":"، أعد المحاولة"));
  String prompt=clean(answer.optString("prompt")),negative=clean(answer.optString("negative"));
  if(!english(prompt))throw new IOException("تعذر تجهيز وصف التوليد، أعد صياغة الفكرة");
  return new JSONObject().put("prompt",prompt.length()>1500?prompt.substring(0,1500):prompt).put("negative",english(negative)?(negative.length()>600?negative.substring(0,600):negative):"");
 }
 private GeneratePrompt(){}
}
