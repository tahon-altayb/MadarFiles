package com.madar.files;

import android.content.Context;
import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The advert's words come from much larger text models than the one on Colab: the owner's own Groq model
 * writes them on his Colab session (writer.json, with the page and picture facts); without Groq, Gemini
 * writes them here before the job leaves the phone. The Colab director keeps this draft when it passes its checks
 * and only picks pictures, designs the frames and repairs what the checks reject. Without Groq, or
 * when its answer is unusable, the film is written on Colab as before.
 */
final class MotionCopywriter {
 /** The writer's instructions live in an asset shared with the end-to-end test, so CI checks the very same words. */
 static String instructions(Context c)throws java.io.IOException{try(java.io.InputStream in=c.getAssets().open("agent-motion-copywriter.txt");java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){FilesUtil.copy(in,out);return out.toString("UTF-8");}}
 /** The copywriter's part; the chief editor's part (after «=== EDITOR ===») is used on Colab after Groq's draft. */
 static String system(Context c)throws java.io.IOException{String all=instructions(c);int cut=all.indexOf("=== EDITOR ===");return (cut<0?all:all.substring(0,cut)).trim();}

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
   .put("pictures_attached",brief.optJSONArray("images")==null?0:brief.getJSONArray("images").length()).put("page_facts",pages(brief)).put("picture_facts",pictures(brief));
 }
 /** What the link's page said, as the phone read it (empty when nothing was read). */
 static JSONArray pages(JSONObject brief){JSONArray out=new JSONArray(),reads=brief.optJSONArray("sourceReads");if(reads!=null)for(int i=0;i<reads.length()&&out.length()<3;i++){String t=reads.optJSONObject(i)==null?"":reads.optJSONObject(i).optString("text");if(!t.trim().isEmpty())out.put(t.length()>2500?t.substring(0,2500):t);}return out;}
 /** What each attached picture shows, as Gemini catalogued it on the phone (empty without it). */
 static JSONArray pictures(JSONObject brief)throws JSONException{JSONArray out=new JSONArray(),facts=brief.optJSONArray("referenceFacts");if(facts!=null)for(int i=0;i<facts.length();i++){JSONObject z=facts.optJSONObject(i);if(z==null||z.optString("kind").matches("unrelated|platform_icon"))continue;String d=z.optString("description");out.put(new JSONObject().put("picture",z.optInt("index")).put("kind",z.optString("kind")).put("shows",d.length()>300?d.substring(0,300):d));}return out;}
 /** Groq writes on Colab for a first film of a brief with a contract (not a revision or a recorded voice). */
 static boolean colab(Context c,JSONObject project){JSONObject brief=project.optJSONObject("creativeRequest");return GroqClient.configured(c)&&brief!=null&&brief.optJSONObject("previous")==null&&brief.optJSONObject("contract")!=null&&!project.optString("voiceMode").equals("recording");}
 /** The owner's own key for his own Colab session, the model and the instructions. */
 static JSONObject writer(Context c)throws Exception{return new JSONObject().put("groq_key",GroqClient.key(c)).put("model",GroqClient.MODEL).put("system",instructions(c));}

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
   // With Groq the words are written on Colab, where the page and picture facts are known (writer.json).
   boolean gemini=!GroqClient.configured(c)&&GeminiClient.configured(c)&&!AgentDiagnostics.recentlyDown(c);
   if(!gemini||brief.optJSONObject("previous")!=null||project.optString("voiceMode").equals("recording")||brief.optJSONObject("contract")==null){brief.remove("draftStory");return;}
   JSONObject request=input(project);String key=MotionProject.hashText(request.toString());
   if(key.equals(brief.optString("draftStoryKey"))&&brief.optJSONObject("draftStory")!=null)return;
   progress.update(4,"Gemini يكتب نصوص المشاهد");
   JSONObject body=new JSONObject().put("systemInstruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",system(c)))))
    .put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text",request.toString())))))
    .put("generationConfig",new JSONObject().put("maxOutputTokens",3000).put("responseMimeType","application/json").put("responseSchema",new JSONObject("{\"type\":\"OBJECT\",\"properties\":{\"title\":{\"type\":\"STRING\"},\"scenes\":{\"type\":\"ARRAY\",\"items\":{\"type\":\"OBJECT\",\"properties\":{\"title\":{\"type\":\"STRING\"},\"caption\":{\"type\":\"STRING\"},\"narration\":{\"type\":\"STRING\"}},\"required\":[\"title\",\"caption\",\"narration\"]}}},\"required\":[\"title\",\"scenes\"]}")));
   JSONObject answer=new JSONObject(GeminiClient.generate(c,body,cancel).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text"));
   JSONObject draft=validate(answer,request.getInt("scene_count"));if(draft!=null)draft.put("writer","gemini");
   if(draft==null){brief.remove("draftStory");diagnostic.record("MOTION_DRAFT_REJECTED");return;}
   brief.put("draftStory",draft).put("draftStoryKey",key);MotionProject.save(c,project);diagnostic.record("MOTION_DRAFT_GEMINI");
  }catch(java.util.concurrent.CancellationException stop){throw stop;}
  catch(Exception failed){try{project.getJSONObject("creativeRequest").remove("draftStory");}catch(JSONException ignored){}diagnostic.record("MOTION_DRAFT_FAILED");}
 }
 private MotionCopywriter(){}
}
