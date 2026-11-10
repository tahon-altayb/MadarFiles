package com.madar.files;

import android.content.Context;
import org.json.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Before a film starts, the assistant looks the business up with Groq's built-in web search and decides
 * whether it still needs an answer or two for a strong scene. The owner sees what was found and unticks
 * what is wrong, so nothing from the web reaches the film without him. Never blocks the film: without
 * Groq, or on any failure, the result is empty and the film starts with what the owner gave.
 */
final class MotionResearch {
 /** The researcher's and the art director's instructions live in one asset, shared with the test that runs them on Groq. */
 static String[] rules(Context c)throws IOException{try(java.io.InputStream in=c.getAssets().open("agent-motion-research.txt");java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){FilesUtil.copy(in,out);String[] parts=out.toString("UTF-8").split("=== DECIDE ===",2);return new String[]{parts[0].trim(),parts.length>1?parts[1].trim():""};}}
 static String text(JSONObject response)throws JSONException{return response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content","").trim();}

 /** Search notes for the business, or "" (no name and no link, nothing found, or the search failed). */
 static String search(Context c,String name,String kind,String owner,AtomicBoolean cancel){
  java.util.regex.Matcher link=java.util.regex.Pattern.compile("https?://[^\\s«»\"']{6,300}").matcher(owner);String url=link.find()?link.group():"";
  if(name.trim().length()<2&&url.isEmpty())return "";
  try{
   String ask="Business name: "+(name.trim().isEmpty()?"(unknown, read the link)":name.trim())+"\nKind: "+kind+(url.isEmpty()?"":"\nIts link (open it first): "+url)+"\nWhat the owner wrote (for the city and details):\n"+(owner.length()>1500?owner.substring(0,1500):owner);
   // The smaller model searches (its own free limits), so the writer's minute is not spent on reading pages.
   JSONObject body=new JSONObject().put("model",GroqClient.SPARE).put("temperature",0).put("max_completion_tokens",1500).put("reasoning_effort","low")
    .put("tools",new JSONArray().put(new JSONObject().put("type","browser_search"))).put("tool_choice","required")
    .put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content",rules(c)[0])).put(new JSONObject().put("role","user").put("content",ask)));
   String notes=text(GroqClient.post(GroqClient.key(c),body,cancel));
   return notes.contains("NOT_FOUND")||notes.length()<20?"":(notes.length()>4000?notes.substring(0,4000):notes);
  }catch(java.util.concurrent.CancellationException stop){throw stop;}
  catch(Exception failed){return "";}
 }

 /** {facts:[..], questions:[{question,options:[..]}]} for the chat; empty lists when nothing is needed or anything fails. */
 static JSONObject run(Context c,JSONObject request,AtomicBoolean cancel){
  JSONObject out=new JSONObject();
  try{
   out.put("type","motionResearch").put("token",request.optString("token")).put("facts",new JSONArray()).put("questions",new JSONArray());
   if(!GroqClient.configured(c))return out;
   String owner=request.optString("text"),name=request.optString("name"),kind=request.optString("kind");if(owner.length()>6000)owner=owner.substring(0,6000);
   String notes=search(c,name,kind,owner,cancel);
   JSONObject schema=new JSONObject("{\"type\":\"object\",\"properties\":{\"facts\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"questions\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\"},\"options\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"question\",\"options\"]}}},\"required\":[\"facts\",\"questions\"]}");
   JSONObject answer=GroqClient.json(c,rules(c)[1],new JSONObject().put("owner_text",owner).put("research_notes",notes).toString(),schema,1500,cancel);
   JSONArray facts=new JSONArray(),questions=new JSONArray(),rawFacts=answer.optJSONArray("facts"),rawQuestions=answer.optJSONArray("questions");
   if(rawFacts!=null&&!notes.isEmpty())for(int i=0;i<rawFacts.length()&&facts.length()<6;i++){String fact=rawFacts.optString(i).replaceAll("\\s+"," ").trim();if(fact.length()>3&&fact.length()<=90&&fact.matches(".*[\\u0621-\\u064a].*"))facts.put(fact);}
   if(rawQuestions!=null)for(int i=0;i<rawQuestions.length()&&questions.length()<3;i++){
    JSONObject q=rawQuestions.optJSONObject(i);if(q==null)continue;String question=q.optString("question").replaceAll("\\s+"," ").trim();JSONArray options=new JSONArray(),raw=q.optJSONArray("options");
    if(raw!=null)for(int j=0;j<raw.length()&&options.length()<5;j++){String option=raw.optString(j).replaceAll("\\s+"," ").trim();if(!option.isEmpty()&&option.length()<=50)options.put(option);}
    if(question.length()>5&&question.length()<=120&&question.matches(".*[\\u0621-\\u064a].*"))questions.put(new JSONObject().put("question",question).put("options",options));
   }
   return out.put("facts",facts).put("questions",questions);
  }catch(java.util.concurrent.CancellationException stop){return out;}
  catch(Exception failed){return out;}
 }
 private MotionResearch(){}
}
