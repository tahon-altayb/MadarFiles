package com.madar.files;

import android.content.Context;
import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes the advert's words with the owner's own Groq model (a much larger text model than the one on
 * Colab) before the job leaves the phone. The Colab director keeps this draft when it passes its checks
 * and only picks pictures, designs the frames and repairs what the checks reject. Without Groq, or
 * when its answer is unusable, the film is written on Colab as before.
 */
final class MotionCopywriter {
 static final String SYSTEM=String.join("\n",
  "You are a senior Arabic advertising copywriter for short motion-graphics adverts of Saudi shops, stores, clinics, gyms and apps.",
  "Write the words of ONE advert from the owner's brief. Output JSON only: {\"title\":string,\"scenes\":[{\"title\":string,\"caption\":string,\"narration\":string}]} with EXACTLY the requested number of scenes.",
  "Structure:",
  "- Scene 1 opens the advert with the business's exact name (its logo is shown there) and one promise taken from the brief.",
  "- Each middle scene tells ONE different fact from the brief, the most persuasive first. Never repeat a fact, never greet, never introduce the business again in the middle.",
  "- The last scene is the closing: its title is the owner's first requested closing phrase VERBATIM; its caption holds his other requested phrases and the address or phone VERBATIM, joined by « · ». If he requested none, close with a short call to visit or order that fits the business.",
  "Writing craft:",
  "- title: 2 to 5 words, a punchy complete headline that a viewer reads in one second. No trailing verb or preposition waiting for its object, no full stop, no colon. The business name only in scene 1.",
  "- caption: one complete short sentence of 5 to 12 words that adds a concrete detail beyond its title. Never repeat the title's words as the caption's opening.",
  "- narration: one natural spoken sentence of the requested length, correct Modern Standard Arabic with a warm Saudi tone, correct grammar and spelling, no repeated words.",
  "- Plain Arabic letters only: no English or Latin words, no emojis, no hashtags, no quotation marks around the text, no optional vowel marks.",
  "Truth:",
  "- Use ONLY facts the owner wrote (products, services, hours, delivery, guarantees, places, names, numbers). Never invent products, prices, numbers, years, awards, certifications or superlatives such as معتمد، الأول، رقم 1، الأفضل في المملكة، منذ سنوات.",
  "- Never write anything the owner said not to write, and apply his corrections exactly (for example a word he said is not a flavour stays out).",
  "- Never use production words: مشهد، فيديو، لقطة، كاميرا، موشن، شعار، إعلان، تصميم.",
  "- Keep the owner's exact spelling of the business name.");

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
   if(!GroqClient.configured(c)||brief.optJSONObject("previous")!=null||project.optString("voiceMode").equals("recording")||brief.optJSONObject("contract")==null){brief.remove("draftStory");return;}
   JSONObject request=input(project);String key=MotionProject.hashText(request.toString());
   if(key.equals(brief.optString("draftStoryKey"))&&brief.optJSONObject("draftStory")!=null)return;
   progress.update(4,"Groq يكتب نصوص المشاهد");
   JSONObject schema=new JSONObject("{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"scenes\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"caption\":{\"type\":\"string\"},\"narration\":{\"type\":\"string\"}},\"required\":[\"title\",\"caption\",\"narration\"]}}},\"required\":[\"title\",\"scenes\"]}");
   JSONObject draft=validate(GroqClient.json(c,SYSTEM,request.toString(),schema,3000,cancel),request.getInt("scene_count"));
   if(draft==null){brief.remove("draftStory");diagnostic.record("MOTION_DRAFT_REJECTED");return;}
   brief.put("draftStory",draft).put("draftStoryKey",key);MotionProject.save(c,project);diagnostic.record("MOTION_DRAFT_GROQ");
  }catch(java.util.concurrent.CancellationException stop){throw stop;}
  catch(Exception failed){try{project.getJSONObject("creativeRequest").remove("draftStory");}catch(JSONException ignored){}diagnostic.record("MOTION_DRAFT_FAILED");}
 }
 private MotionCopywriter(){}
}
