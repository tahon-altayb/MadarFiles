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
  JSONArray scenes=old.getJSONArray("scenes");for(int i=0;i<scenes.length();i++){JSONObject scene=scenes.getJSONObject(i);if(!scene.optString("graphic").equals("creative"))return null;JSONArray elements=scene.getJSONObject("composition").getJSONArray("elements");int texts=0,media=0;for(int n=0;n<elements.length();n++){String kind=elements.getJSONObject(n).getString("kind");if(Arrays.asList("text","metric").contains(kind))texts++;if(Arrays.asList("image","device").contains(kind))media++;}if(texts!=2||media>1)return null;}
  JSONObject p=new JSONObject(old.toString());String id=MotionProject.id();p.put("id",id).put("agentTask",request.optString("task")).put("agentMode","render").put("agentRequestKey",MotionCheckpoint.requestKey(request)).put("revisionParent",old.getString("id"));p.remove("result");p.remove("approved");p.remove("motionCheckpoint");
  for(String name:MotionCreative.assets(old))copy(c,old.getString("id"),id,name);
  if(!p.getString("voiceMode").equals("none"))copy(c,old.getString("id"),id,"voice.wav");
  boolean logo=request.optString("assetRole").equals("logo");JSONObject oldMap=old.optJSONObject("creativeRequest")==null?null:old.getJSONObject("creativeRequest").optJSONObject("referenceMap");JSONObject map=new JSONObject();JSONArray refs=request.optJSONArray("attachments"),images=new JSONArray();Set<String> seen=new HashSet<>();if(refs!=null)for(int i=0;i<refs.length();i++){String ref=refs.getString(i);if(oldMap!=null&&oldMap.has(ref)&&(!logo||!ref.equals(request.optString("selectedAttachment"))))continue;if(!seen.add(ref)||!AssistantFiles.info(c,ref).optString("kind").equals("image"))continue;if(images.length()>=8)throw new IOException("اختر حتى ثماني صور للمراجعة");String name=MotionImages.store(c,id,AssistantFiles.resolve(c,ref));images.put(name);map.put(ref,name);}
  if(logo&&MotionCreative.assets(old).size()+images.length()>8)throw new IOException("المشروع يحتوي ثماني صور؛ استبعد مرجعًا قبل إضافة شعار جديد");if(images.length()==0)throw new IOException("ما وصلتني صور جديدة غير المستخدمة في الإعلان؛ أرفق الصور الجديدة ثم اكتب طلبك");
  p.put("visualRevisionAssets",images).put("visualRevisionRole",request.optString("assetRole").equals("logo")?"logo":"photos");
  JSONObject brief=p.optJSONObject("creativeRequest");if(brief!=null){brief.put("lastVisualRequest",request.optString("prompt"));if(!logo){
   // New pictures join the film: the logo stays first, then the new ones, then earlier pictures (all on disk).
   String logoAsset=brief.optJSONObject("contract")==null?"":brief.getJSONObject("contract").optString("logoAsset");JSONArray prior=brief.optJSONArray("images");Set<String> kept=new LinkedHashSet<>();
   if(!logoAsset.isEmpty()&&MotionProject.asset(c,id,logoAsset).isFile())kept.add(logoAsset);for(int i=0;i<images.length();i++)kept.add(images.getString(i));
   if(prior!=null)for(int i=0;i<prior.length()&&kept.size()<8;i++){String name=prior.getString(i);if(!name.matches("asset_[a-f0-9]{32}\\.jpg"))continue;File target=MotionProject.asset(c,id,name);if(!target.isFile()&&MotionProject.asset(c,old.getString("id"),name).isFile())copy(c,old.getString("id"),id,name);if(target.isFile())kept.add(name);}
   JSONArray all=new JSONArray();for(String name:kept)if(all.length()<8)all.put(name);JSONObject merged=oldMap==null?new JSONObject():new JSONObject(oldMap.toString());for(Iterator<String> it=map.keys();it.hasNext();){String key=it.next();merged.put(key,map.get(key));}
   brief.put("images",all).put("referenceMap",merged);if(!logoAsset.isEmpty())p.put("visualRevisionLogo",logoAsset);
  }else{JSONArray prior=brief.optJSONArray("images");if(prior==null)prior=new JSONArray();for(int i=0;i<images.length();i++)prior.put(images.get(i));brief.put("images",prior);if(oldMap==null)oldMap=new JSONObject();for(Iterator<String> it=map.keys();it.hasNext();){String key=it.next();oldMap.put(key,map.get(key));}brief.put("referenceMap",oldMap);}}
  AgentMotion.settings(p,request);MotionProject.save(c,p);return p;
 }
 private static void copy(Context c,String from,String to,String name)throws Exception{try(InputStream in=new FileInputStream(MotionProject.asset(c,from,name));OutputStream out=new FileOutputStream(MotionProject.asset(c,to,name))){FilesUtil.copy(in,out);}}
 private MotionRevision(){}
}
