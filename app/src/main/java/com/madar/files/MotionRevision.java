package com.madar.files;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.*;

/** Explicit visual-only revisions preserve the exact approved copy, clocks and voice. */
final class MotionRevision {
 static JSONObject create(Context c,JSONObject request)throws Exception{
  if(!request.optString("revisionKind").equals("images")||request.optString("previous").isEmpty())return null;
  JSONObject old=MotionProject.load(c,request.getString("previous"));
  if(!request.optString("thread").equals(old.optString("agentThread")))throw new IOException("المشروع لا يخص المحادثة");
  if(!old.optBoolean("ready")||!old.getString("voiceMode").equals(request.optString("voice")))return null;
  JSONArray scenes=old.getJSONArray("scenes");for(int i=0;i<scenes.length();i++){JSONObject scene=scenes.getJSONObject(i);if(!scene.optString("graphic").equals("creative"))return null;JSONArray elements=scene.getJSONObject("composition").getJSONArray("elements");int texts=0;for(int n=0;n<elements.length();n++)if(Arrays.asList("text","metric").contains(elements.getJSONObject(n).getString("kind")))texts++;if(texts!=2)return null;}
  JSONObject p=new JSONObject(old.toString());String id=MotionProject.id();p.put("id",id).put("agentTask",request.optString("task")).put("agentMode","render").put("agentRequestKey",MotionCheckpoint.requestKey(request)).put("revisionParent",old.getString("id"));p.remove("result");p.remove("approved");p.remove("motionCheckpoint");
  for(String name:MotionCreative.assets(old))copy(c,old.getString("id"),id,name);
  if(!p.getString("voiceMode").equals("none"))copy(c,old.getString("id"),id,"voice.wav");
  JSONArray refs=request.optJSONArray("attachments"),images=new JSONArray();Set<String> seen=new HashSet<>();if(refs!=null)for(int i=0;i<refs.length();i++){String ref=refs.getString(i);if(!seen.add(ref)||!AssistantFiles.info(c,ref).optString("kind").equals("image"))continue;if(images.length()>=8)throw new IOException("اختر حتى ثماني صور للمراجعة");images.put(MotionImages.store(c,id,AssistantFiles.resolve(c,ref)));}
  if(images.length()==0)throw new IOException("أرفق الصور لإضافتها إلى النسخة المحفوظة");
  p.put("visualRevisionAssets",images).put("visualRevisionRole",request.optString("assetRole").equals("logo")?"logo":"photos");
  JSONObject brief=p.optJSONObject("creativeRequest");if(brief!=null)brief.put("lastVisualRequest",request.optString("prompt"));
  AgentMotion.settings(p,request);MotionProject.save(c,p);return p;
 }
 private static void copy(Context c,String from,String to,String name)throws Exception{try(InputStream in=new FileInputStream(MotionProject.asset(c,from,name));OutputStream out=new FileOutputStream(MotionProject.asset(c,to,name))){FilesUtil.copy(in,out);}}
 private MotionRevision(){}
}
