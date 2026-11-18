package com.madar.files;
import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** A completed remote job is retained until its verified output is saved locally. */
final class MotionTransfer {
 static boolean pending(JSONObject p){return p.optJSONObject("motionTransfer")!=null;}
 static JSONObject state(JSONObject p)throws Exception{
  JSONObject t=p.getJSONObject("motionTransfer");
  if(!t.getString("folder").matches("madar_[a-f0-9]{32}")||!Arrays.asList("align","render","compose").contains(t.getString("mode")))throw new IOException("Colab · MOTION_TRANSFER_INVALID");
  return t;
 }
 static void mark(Context c,JSONObject p,String folder,String mode)throws Exception{
  p.put("motionTransfer",new JSONObject().put("folder",folder).put("mode",mode).put("sourceTask",p.optString("agentTask")));state(p);MotionProject.save(c,p);
 }
 static void interrupted(Context c,JSONObject status)throws Exception{
  status.put("status","error").put("message","توقفت المعالجة قبل اكتمالها؛ يمكنك إعادة الطلب والمشروع محفوظ");
  JSONObject project;try{project=MotionProject.load(c,status.optString("project"));}catch(Exception missing){return;}
  if(pending(project)&&project.optString("agentThread").equals(status.optString("thread"))&&project.optString("agentTask").equals(status.optString("task")))status.put("checkpoint",true).put("transfer",true).put("message","اكتملت معالجة الفيديو، وتوقف تنزيله عند إغلاق التطبيق. اكتب «أعد الاتصال» لاستكمال التنزيل دون إعادة إنشائه");
 }
 static JSONObject fetch(Context c,ColabRuntime runtime,JSONObject original,AtomicBoolean cancel,MotionCloud.Progress progress,ColabDiagnostics diagnostic)throws Exception{
  JSONObject transfer=state(original);String folder=transfer.getString("folder"),mode=transfer.getString("mode"),id=original.getString("id");
  diagnostic.record("MOTION_FETCH_MANIFEST");
  JSONObject result=ColabRuntime.parseJson(runtime.getText(folder+"/result.json",2*1024*1024));
  if(result.getInt("protocol")!=1||!mode.equals(result.getString("mode")))throw new IOException("Colab · MOTION_TRANSFER_INVALID");
  JSONObject updated=MotionProject.validate(result.getJSONObject("project"),mode.equals("render"));
  for(String key:new String[]{"id","agentThread","agentRequestKey","voiceMode"})if(!original.optString(key).equals(updated.optString(key)))throw new IOException("Colab · MOTION_TRANSFER_INVALID");
  if(!transfer.optString("sourceTask").equals(updated.optString("agentTask")))throw new IOException("Colab · MOTION_TRANSFER_INVALID");
  JSONObject meta=result.optJSONObject("file");
  if(meta!=null){
   long size=meta.getLong("size");int chunks=meta.getInt("chunks");String hash=meta.getString("sha256");validateMeta(size,chunks,hash,128L*1024*1024);
   diagnostic.record("MOTION_FETCH_VIDEO");File output=null;JSONObject saved=transfer.optJSONObject("downloaded");
   if(saved!=null)try{File f=mode.equals("align")?MotionProject.asset(c,id,"voice.wav"):FilesUtil.resolve(c,saved.getString("name"));if(f.isFile()&&f.length()==size&&hash.equals(MotionProject.hash(f)))output=f;}catch(IOException ignored){}
   if(output==null){
    output=mode.equals("align")?MotionProject.asset(c,id,"voice.wav"):FilesUtil.output(c,"mp4");
    copy(runtime,folder+"/result_",chunks,size,hash,output,cancel,progress);
    transfer.put("downloaded",new JSONObject().put("name",output.getName()));MotionProject.save(c,original);
   }
   if(!mode.equals("align"))updated.put("result",FilesUtil.info(output));else updated.put("audio","voice.wav").put("audioRevision",System.currentTimeMillis());
  }
  JSONArray extras=result.optJSONArray("assets");
  if(extras!=null){
   diagnostic.record("MOTION_FETCH_ASSETS");if(extras.length()>9)throw new IOException("Colab · MOTION_TRANSFER_INVALID");long total=0;Set<String> seen=new HashSet<>();
   for(int i=0;i<extras.length();i++){
    ColabProbe.check(cancel);JSONObject extra=extras.getJSONObject(i);String name=extra.getString("name");
    if(!name.matches("voice\\.wav|asset_[a-f0-9]{32}\\.jpg")||!seen.add(name))throw new IOException("Colab · MOTION_TRANSFER_INVALID");
    long size=extra.getLong("size");int count=extra.getInt("chunks");String hash=extra.getString("sha256");validateMeta(size,count,hash,32L*1024*1024);if((total+=size)>96L*1024*1024)throw new IOException("Colab · MOTION_TRANSFER_INVALID");
    File dest=MotionProject.asset(c,id,name);
    if(!dest.isFile()||dest.length()!=size||!hash.equals(MotionProject.hash(dest)))copy(runtime,folder+"/asset_"+i+"_",count,size,hash,dest,cancel,progress);
    if(name.equals("voice.wav"))updated.put("audio","voice.wav").put("audioRevision",System.currentTimeMillis());
   }
  }
  ColabProbe.check(cancel);updated.put("agentTask",original.optString("agentTask"));updated.remove("motionTransfer");
  MotionProject.save(c,updated);original.remove("motionTransfer");diagnostic.record("MOTION_RESULT_VERIFIED");return updated;
 }
 private static void validateMeta(long size,int chunks,String hash,long maximum)throws IOException{
  if(size<1||size>maximum||chunks!=(size+MotionCloud.CHUNK-1)/MotionCloud.CHUNK||!hash.matches("[a-f0-9]{64}"))throw new IOException("Colab · MOTION_TRANSFER_INVALID");
 }
 private static void copy(ColabRuntime runtime,String prefix,int chunks,long size,String hash,File output,AtomicBoolean cancel,MotionCloud.Progress progress)throws Exception{
  File temp=new File(output.getPath()+".partial");
  try{
   try(OutputStream out=new FileOutputStream(temp)){
    for(int n=0;n<chunks;n++){ColabProbe.check(cancel);byte[] bytes=runtime.getBinary(prefix+n+".bin",2*1024*1024);if(bytes.length!=Math.min(MotionCloud.CHUNK,size-(long)n*MotionCloud.CHUNK))throw new IOException("Colab · MOTION_OUTPUT_LENGTH");out.write(bytes);progress.update(98,"تحميل النتيجة · "+(n+1)+" / "+chunks);}
   }
   if(temp.length()!=size||!hash.equals(MotionProject.hash(temp)))throw new IOException("Colab · MOTION_OUTPUT_HASH");
   ColabProbe.check(cancel);if(!temp.renameTo(output))throw new IOException("تعذر حفظ النتيجة؛ أعد المحاولة لتنزيلها");
  }finally{temp.delete();}
 }
 private MotionTransfer(){}
}
