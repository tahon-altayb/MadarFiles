package com.madar.files;
import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** Verify and retain completed direction before disposing a failed cloud task. */
final class MotionCheckpoint {
 static String requestKey(JSONObject request)throws Exception{
  String value=new JSONArray().put(request.getString("prompt")).put(request.optString("voice","silma")).put(request.optJSONArray("attachments")==null?new JSONArray():request.getJSONArray("attachments")).put(request.optString("previous","")).toString();
  byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder key=new StringBuilder();for(byte b:bytes)key.append(String.format(Locale.ROOT,"%02x",b&255));return key.toString();
 }
 static void recover(Context c,ColabRuntime runtime,String folder,JSONObject original,AtomicBoolean cancel)throws Exception{
  if(folder==null)return;
  JSONObject checkpoint=ColabRuntime.parseJson(runtime.getText(folder+"/checkpoint.json",2*1024*1024));
  accept(c,checkpoint,original,cancel,(i,n)->runtime.getBinary(folder+"/checkpoint_asset_"+i+"_"+n+".bin",2*1024*1024));
 }
 interface Read{byte[] part(int asset,int part)throws Exception;}
 static void accept(Context c,JSONObject checkpoint,JSONObject original,AtomicBoolean cancel,Read read)throws Exception{
  if(checkpoint.getInt("protocol")!=1)throw new IOException("MOTION_CHECKPOINT_INVALID");
  JSONObject p=MotionProject.validate(checkpoint.getJSONObject("project"),false);
  String id=original.getString("id");
  for(String k:new String[]{"id","agentThread","agentTask","voiceMode","agentRequestKey"})if(!original.optString(k).equals(p.optString(k)))throw new IOException("MOTION_CHECKPOINT_INVALID");
  if(p.optString("agentRequestKey").isEmpty()||p.optJSONObject("direction")==null||!p.getJSONObject("direction").optString("layoutMode").equals("validated_scene_graph"))throw new IOException("MOTION_CHECKPOINT_INVALID");
  JSONArray assets=checkpoint.getJSONArray("assets");if(assets.length()>28)throw new IOException("MOTION_CHECKPOINT_INVALID");long total=0;Set<String> seen=new HashSet<>();
  for(int i=0;i<assets.length();i++){
   ColabProbe.check(cancel);JSONObject meta=assets.getJSONObject(i);String name=meta.getString("name"),hash=meta.getString("sha256");long size=meta.getLong("size");int count=meta.getInt("chunks");
   if(!name.matches("asset_[a-f0-9]{32}\\.jpg|speech_(?:[0-9]|1[0-9])\\.wav")||!seen.add(name)||!hash.matches("[a-f0-9]{64}")||size<1||size>32L*1024*1024||(total+=size)>96L*1024*1024||count!=(size+MotionCloud.CHUNK-1)/MotionCloud.CHUNK)throw new IOException("MOTION_CHECKPOINT_INVALID");
   File target=MotionProject.asset(c,id,name);
   if(target.isFile()){if(target.length()!=size||!MotionProject.hash(target).equals(hash))throw new IOException("MOTION_CHECKPOINT_INVALID");continue;}
   File temporary=new File(target.getPath()+".partial");
   try{
    try(OutputStream out=new FileOutputStream(temporary)){
     for(int n=0;n<count;n++){ColabProbe.check(cancel);byte[] b=read.part(i,n);if(b.length!=Math.min(MotionCloud.CHUNK,size-(long)n*MotionCloud.CHUNK))throw new IOException("MOTION_CHECKPOINT_INVALID");out.write(b);}
    }
    if(temporary.length()!=size||!MotionProject.hash(temporary).equals(hash)||!temporary.renameTo(target))throw new IOException("MOTION_CHECKPOINT_INVALID");
   }finally{temporary.delete();}
  }
  for(String name:MotionCreative.assets(p))if(!MotionProject.asset(c,id,name).isFile())throw new IOException("MOTION_CHECKPOINT_INVALID");
  String phase=checkpoint.optString("phase","directed");if(!phase.equals("directed")&&!phase.equals("voiced"))throw new IOException("MOTION_CHECKPOINT_INVALID");
  if(phase.equals("voiced")){if(!p.optString("voiceMode").equals("silma"))throw new IOException("MOTION_CHECKPOINT_INVALID");for(int i=0;i<p.getJSONArray("scenes").length();i++)if(!seen.contains("speech_"+i+".wav")||!MotionProject.asset(c,id,"speech_"+i+".wav").isFile())throw new IOException("MOTION_CHECKPOINT_INVALID");}
  p.remove("result");p.put("motionCheckpoint",phase);MotionProject.save(c,p);
 }
 static JSONObject resume(Context c,JSONObject request,String thread)throws Exception{
  String id=request.optString("resumeProject");JSONObject p=MotionProject.load(c,id);
  if(p.optBoolean("preflightPending")){
   if(!thread.equals(p.optString("agentThread"))||!requestKey(request).equals(p.optString("agentRequestKey")))throw new IOException("لا يطابق الاستكمال طلب الموشن المحفوظ");
   p.put("agentTask",request.optString("task",""));MotionProject.save(c,p);return p;
  }
  if(MotionTransfer.pending(p)){if(!thread.equals(p.optString("agentThread"))||!requestKey(request).equals(p.optString("agentRequestKey")))throw new IOException("لا يطابق الاستكمال طلب الفيديو المحفوظ");MotionTransfer.state(p);p.put("agentTask",request.optString("task",""));MotionProject.save(c,p);return p;}
  if(!thread.equals(p.optString("agentThread"))||!Arrays.asList("directed","voiced").contains(p.optString("motionCheckpoint"))||!requestKey(request).equals(p.optString("agentRequestKey")))throw new IOException("لا يطابق الاستكمال الطلب المحفوظ؛ أعد إرسال طلبك الأصلي");
  for(String name:MotionCreative.assets(p))if(!MotionProject.asset(c,id,name).isFile())throw new IOException("أحد مراجع المشروع المحفوظ غير متاح؛ أرفقه وأعد الطلب");
  p.put("agentTask",request.optString("task",""));MotionProject.save(c,p);return p;
 }
}
