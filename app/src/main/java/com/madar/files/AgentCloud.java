package com.madar.files;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses the existing managed Gemini connection. Only bounded text and metadata are sent. */
final class AgentCloud {
 static JSONObject schema(JSONObject local)throws Exception{JSONObject out=new JSONObject();for(Iterator<String> it=local.keys();it.hasNext();){String k=it.next();Object v=local.get(k);if(k.equals("additionalProperties")||k.equals("maxItems"))continue;if(k.equals("type"))v=((String)v).toUpperCase(java.util.Locale.ROOT);else if(v instanceof JSONObject){if(k.equals("properties")){JSONObject properties=new JSONObject();JSONObject values=(JSONObject)v;for(Iterator<String> p=values.keys();p.hasNext();){String n=p.next();properties.put(n,schema(values.getJSONObject(n)));}v=properties;}else v=schema((JSONObject)v);}out.put(k,v);}return out;}
 static JSONObject plan(Context c,String user,JSONObject source,JSONArray history,AtomicBoolean cancel)throws Exception{
  String prompt;try(InputStream in=c.getAssets().open("agent-prompt.txt");ByteArrayOutputStream out=new ByteArrayOutputStream()){FilesUtil.copy(in,out);prompt=out.toString("UTF-8");}
  JSONObject safeSource=new JSONObject(source.toString());JSONArray visual=safeSource.optJSONArray("_visual");safeSource.remove("_visual");
  JSONObject input=new JSONObject().put("source",safeSource).put("availableTools",new JSONArray(AgentTools.tools(source.optString("kind")))).put("conversation",LocalAgent.history(history)).put("userRequest",user).put("savedSettings",LocalAgent.memory(c)).put("teacherExamples",AgentExamples.relevant(c,user));
  if(user.contains("https://")&&!user.matches("(?is).*(تحميل|حمل|حمّل|تنزيل|download).*") )input.put("sourceReads",AgentSources.read(user,cancel));
  return decide(prompt,input,visual,source.optString("kind"),cancel,(body,stop)->GeminiClient.generate(c,body,stop));
 }
 static JSONObject decide(String prompt,JSONObject input,JSONArray visual,String kind,AtomicBoolean cancel,AgentExecutive.Transport transport)throws Exception{
  JSONObject allowed=AgentTools.schema(kind);JSONObject draft=AgentExecutive.invoke(prompt,input,visual,allowed,4096,cancel,transport);
  input.put("untrustedProposal",draft).put("reviewTask","Re-read the exact latest user request, relevant conversation, actual visual samples and available tools. Check negation, pronouns, required inputs, multi-step order and field values. Repair the proposal before any execution. Ask ONE specific question if required evidence is missing. Do not convert production-only statements to public output, reuse example-specific facts, claim to browse unread pages, or claim completion. Return the complete final action; no execution has started.");
  JSONObject checked=AgentExecutive.invoke(prompt,input,visual,allowed,4096,cancel,transport);return AgentTools.validate(checked,kind).put("agentReviewed",true);
 }
}
