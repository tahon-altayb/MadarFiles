package com.madar.files;
import android.content.Context;
import org.json.*;
import java.util.*;

/** Bounded retrieval of user-approved interpretations. This is memory, not model training. */
final class AgentExamples {
 static JSONArray all(Context c){try{return new JSONArray(c.getSharedPreferences("agent_examples",0).getString("approved","[]"));}catch(Exception invalid){return new JSONArray();}}
 static boolean secret(String value){return value.matches("(?is).*(AIza[A-Za-z0-9_-]{15,}|sk-[A-Za-z0-9]{12,}|(?:token|password|api.?key)\\s*[:=]|كلمة المرور\\s*[:：]).*");}
 static synchronized void approve(Context c,JSONObject project)throws Exception{
  if(!c.getSharedPreferences("local_agent",0).getBoolean("learn",true))return;
  JSONObject brief=project.optJSONObject("creativeRequest");if(brief==null||!brief.optBoolean("executiveVerified")||brief.optJSONObject("clientContract")==null)return;
  String request=brief.optString("text");if(request.length()>1800)request=request.substring(request.length()-1800);JSONObject contract=brief.getJSONObject("clientContract");if(secret(request)||secret(contract.toString()))return;
  JSONArray old=all(c),next=new JSONArray();for(int i=Math.max(0,old.length()-11);i<old.length();i++)if(!old.getJSONObject(i).optString("project").equals(project.getString("id")))next.put(old.getJSONObject(i));
  next.put(new JSONObject().put("project",project.getString("id")).put("request",request).put("contract",contract).put("authority","user_approved").put("usage","Interpretation example only; names, addresses and product facts are not facts about a new project."));c.getSharedPreferences("agent_examples",0).edit().putString("approved",next.toString()).apply();
 }
 static synchronized void reject(Context c,String project)throws Exception{JSONArray next=new JSONArray(),old=all(c);for(int i=0;i<old.length();i++)if(!old.getJSONObject(i).optString("project").equals(project))next.put(old.getJSONObject(i));c.getSharedPreferences("agent_examples",0).edit().putString("approved",next.toString()).apply();}
 static JSONArray relevant(Context c,String request)throws Exception{
  JSONArray out=new JSONArray();if(!c.getSharedPreferences("local_agent",0).getBoolean("learn",true))return out;
  Set<String> words=new HashSet<>(Arrays.asList(AgentExecutive.normalized(request).split("[^\\p{L}\\p{N}]+")));List<JSONObject> rows=new ArrayList<>();JSONArray old=all(c);for(int i=0;i<old.length();i++)rows.add(old.getJSONObject(i));
  rows.sort((a,b)->Integer.compare(score(b,words),score(a,words)));for(JSONObject row:rows){if(out.length()==3||score(row,words)==0)break;JSONObject example=new JSONObject(row.toString());example.remove("project");out.put(example);}return out;
 }
 private static int score(JSONObject row,Set<String> words){int total=0;for(String word:AgentExecutive.normalized(row.optString("request")).split("[^\\p{L}\\p{N}]+"))if(word.length()>2&&words.contains(word))total++;return total;}
 static void forget(Context c){c.getSharedPreferences("agent_examples",0).edit().clear().apply();}
}
