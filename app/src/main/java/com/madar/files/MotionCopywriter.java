package com.madar.files;

import android.content.Context;
import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes the advert's words with the owner's own Groq model (or Gemini when Groq is not connected), much
 * larger text models than the one on Colab, before the job leaves the phone. The Colab director keeps this draft when it passes its checks
 * and only picks pictures, designs the frames and repairs what the checks reject. Without Groq, or
 * when its answer is unusable, the film is written on Colab as before.
 */
final class MotionCopywriter {
 /** The writer's instructions live in an asset shared with the end-to-end test, so CI checks the very same words. */
 static String system(Context c)throws java.io.IOException{try(java.io.InputStream in=c.getAssets().open("agent-motion-copywriter.txt");java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){FilesUtil.copy(in,out);return out.toString("UTF-8");}}

 static String plain(String v){return v==null?"":v.replaceAll("[\\u064b-\\u065f\\u0670\\u0640]","").replaceAll("\\s+"," ").trim();}

 /** The request for the model, from the brief and the chat's contract. */
 static JSONObject input(JSONObject project)throws JSONException{
  JSONObject brief=project.getJSONObject("creativeRequest"),contract=brief.optJSONObject("contract");if(contract==null)contract=new JSONObject();
  int scenes=Math.max(3,Math.min(10,contract.optInt("sceneCount",6)));double seconds=contract.optDouble("durationSec",30);
  int per=(int)Math.round(seconds/scenes),low=Math.max(5,per*2-2),high=Math.max(low+3,per*2+3);
  return new JSONObject().put("owner_brief",brief.optString("text")).put("scene_count",scenes).put("narration_words",low+"-"+high)
   .put("business_name",contract.optString("businessName")).put("closing_phrases",contract.optJSONArray("required")==null?new JSONArray():contract.getJSONArray("required"))
   .put("never_write",contract.optJSONArray("forbidden")==null?new JSONArray():contract.getJSONArray("forbidden")).put("corrections",contract.optJSONArray("corrections")==null?new JSONArray():contract.getJSONArray("corrections"))
   .put("address",contract.optString("address")).put("phone",contract.optString("phone")).put("visit_branch",contract.optBoolean("visitBranch")).put("no_website",contract.optBoolean("noWebsite"))
   .put("pictures_attached",brief.optJSONArray("images")==null?0:brief.getJSONArray("images").length());
 }

 /** A usable draft, cleaned; null when the answer does not fit the plan (the Colab writer is used then). */
 static JSONObject validate(JSONObject answer,int count){
  try{
   JSONArray in=answer.getJSONArray("scenes");if(in.length()!=count)return null;JSONArray out=new JSONArray();Set<String> titles=new HashSet<>();
   for(int i=0;i<in.length();i++){
    JSONObject z=in.getJSONObject(i);String title=plain(z.optString("title")).replaceAll("[.:،,]+$",""),caption=plain(z.optString("caption")),narration=plain(z.optString("narration"));
    if(!title.matches(".*[\\u0621-\\u064a].*")||title.length()>45||title.split(" ").length>7)return null;
    if(!narration.matches(".*[\\u0621-\\u064a].*")||narration.length()<8||narration.length()>220||caption.length()>95)return null;
    if((title+" "+caption).matches(".*[A-Za-z]{3,}.*")||!titles.add(title))return null;
    out.put(new JSONObject().put("title",title).put("caption",caption).put("narration",narration));
   }
   return new JSONObject().put("title",plain(answer.optString("title")).length()<=80?plain(answer.optString("title")):"").put("scenes",out).put("writer","groq");
  }catch(JSONException e){return null;}
 }

 /** Adds brief.draftStory for a first compose; never stops the film when Groq is missing or fails. */
 static void attach(Context c,JSONObject project,AtomicBoolean cancel,MotionCloud.Progress progress,ColabDiagnostics diagnostic){
  try{
   JSONObject brief=project.getJSONObject("creativeRequest");
   boolean groq=GroqClient.configured(c),gemini=!groq&&GeminiClient.configured(c)&&!AgentDiagnostics.recentlyDown(c);
   if(!(groq||gemini)||brief.optJSONObject("previous")!=null||project.optString("voiceMode").equals("recording")||brief.optJSONObject("contract")==null){brief.remove("draftStory");return;}
   JSONObject request=input(project);String key=MotionProject.hashText(request.toString());
   if(key.equals(brief.optString("draftStoryKey"))&&brief.optJSONObject("draftStory")!=null)return;
   progress.update(4,(groq?"Groq":"Gemini")+" يكتب نصوص المشاهد");
   JSONObject schema=new JSONObject("{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"scenes\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"caption\":{\"type\":\"string\"},\"narration\":{\"type\":\"string\"}},\"required\":[\"title\",\"caption\",\"narration\"]}}},\"required\":[\"title\",\"scenes\"]}");
   JSONObject answer;
   if(groq)answer=GroqClient.json(c,system(c),request.toString(),schema,3000,cancel);
   else{
    // Without Groq, Gemini (when it answers) writes the same draft through the app's own Firebase or key.
    JSONObject body=new JSONObject().put("systemInstruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",system(c)))))
     .put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text",request.toString())))))
     .put("generationConfig",new JSONObject().put("maxOutputTokens",3000).put("responseMimeType","application/json").put("responseSchema",new JSONObject("{\"type\":\"OBJECT\",\"properties\":{\"title\":{\"type\":\"STRING\"},\"scenes\":{\"type\":\"ARRAY\",\"items\":{\"type\":\"OBJECT\",\"properties\":{\"title\":{\"type\":\"STRING\"},\"caption\":{\"type\":\"STRING\"},\"narration\":{\"type\":\"STRING\"}},\"required\":[\"title\",\"caption\",\"narration\"]}}},\"required\":[\"title\",\"scenes\"]}")));
    answer=new JSONObject(GeminiClient.generate(c,body,cancel).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text"));
   }
   JSONObject draft=validate(answer,request.getInt("scene_count"));if(draft!=null)draft.put("writer",groq?"groq":"gemini");
   if(draft==null){brief.remove("draftStory");diagnostic.record("MOTION_DRAFT_REJECTED");return;}
   brief.put("draftStory",draft).put("draftStoryKey",key);MotionProject.save(c,project);diagnostic.record(groq?"MOTION_DRAFT_GROQ":"MOTION_DRAFT_GEMINI");
  }catch(java.util.concurrent.CancellationException stop){throw stop;}
  catch(Exception failed){try{project.getJSONObject("creativeRequest").remove("draftStory");}catch(JSONException ignored){}diagnostic.record("MOTION_DRAFT_FAILED");}
 }
 private MotionCopywriter(){}
}
