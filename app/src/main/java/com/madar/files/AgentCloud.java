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
  JSONObject input=new JSONObject().put("source",safeSource).put("availableTools",new JSONArray(AgentTools.tools(source.optString("kind")))).put("conversation",LocalAgent.history(history)).put("userRequest",user);
  JSONArray promptParts=new JSONArray().put(new JSONObject().put("text",input.toString()));if(visual!=null)for(int i=0;i<visual.length();i++)promptParts.put(visual.getJSONObject(i));
  JSONObject body=new JSONObject().put("systemInstruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",prompt))))
   .put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",promptParts)))
   .put("generationConfig",new JSONObject().put("temperature",0).put("responseMimeType","application/json").put("maxOutputTokens",4096).put("responseSchema",schema(AgentTools.schema(source.optString("kind")))));
  JSONObject response=GeminiClient.generate(c,body,cancel);JSONObject candidate=response.getJSONArray("candidates").getJSONObject(0);StringBuilder result=new StringBuilder();JSONArray parts=candidate.getJSONObject("content").getJSONArray("parts");for(int i=0;i<parts.length();i++)result.append(parts.getJSONObject(i).optString("text"));
  if(result.length()>12000)throw new IOException("اختصر طلب المساعد إلى خطوات أقل");return AgentTools.validate(new JSONObject(result.toString()),source.optString("kind"));
 }
}
