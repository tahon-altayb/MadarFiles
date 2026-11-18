package com.madar.files;

import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Reuses the existing authenticated Colab transport; one retained visual model, no Gemini. */
final class CensorCloudJob {
 static void prepare(ColabMusic.Session session,MusicSeparator.Progress progress)throws Exception{
  session.awaitReady();if(session.visionReady)return;
  ColabRuntime runtime=session.runtime;String root="madar_"+UUID.randomUUID().toString().replace("-","");session.visionRoot=root;
  runtime.jupyter("PUT","api/contents/"+root,new JSONObject().put("type","directory"),1024*1024);
  runtime.put(root+"/music_worker.py",ColabMusic.asset(session.context,"censor_worker.py"),"text");
  runtime.put(root+"/music_launch.py",ColabMusic.asset(session.context,"music_launch.py"),"text");
  for(String script:new String[]{"censor_install.py","censor_engine.py","censor_tracking.py","censor_profile.py"})runtime.put(root+"/"+script,ColabMusic.asset(session.context,script),"text");
  runtime.put(root+"/progress.json",new JSONObject().put("stage","bootstrap").put("progress",27).put("heartbeat",0).toString(),"text");
  session.diagnostic.record("CENSOR_PREPARE");
  MusicStartup startup=new MusicStartup(runtime,root,session.cancel,(p,m)->progress.update(p,m.replace("الفصل","الحجب").replace("UVR","MiVOLO")),session.diagnostic);
  session.channel=runtime.openKernel(session.kernel,session.diagnostic::record);
  try{session.channel.execute("SERVICE_FOLDER = "+JSONObject.quote(root)+"\n"+ColabMusic.asset(session.context,"censor_warmup.py"),startup::poll);startup.awaitReady();session.visionReady=true;session.diagnostic.record("CENSOR_READY");}
  finally{session.channel.close();session.channel=null;}
 }
 static byte[] run(ColabMusic.Session session,File video,String source,long decodeStartMs,long startMs,long endMs,MusicSeparator.Progress progress)throws Exception{
  ColabProbe.check(session.cancel);prepare(session,progress);ColabRuntime runtime=session.runtime;ColabDiagnostics diagnostic=session.diagnostic;
  String folder=session.visionRoot+"_b"+(session.visionBatch++);runtime.jupyter("PUT","api/contents/"+folder,new JSONObject().put("type","directory"),1024*1024);
  if(video.length()<=0||video.length()>20000000)throw new IOException("Colab · CENSOR_INPUT_LENGTH");
  byte[] bytes=Files.readAllBytes(video.toPath());long upload=android.os.SystemClock.elapsedRealtime();diagnostic.record("CENSOR_UPLOAD");
  runtime.put(folder+"/input.mp4",Base64.encodeToString(bytes,Base64.NO_WRAP),"base64");
  runtime.put(folder+"/request.json",new JSONObject().put("protocol",1).put("fingerprint",CensorTimeline.FINGERPRINT).put("source",source).put("bytes",bytes.length).put("sha256",ColabMusic.sha(bytes)).put("decode_start_us",decodeStartMs*1000).put("core_start_us",startMs*1000).put("core_end_us",endMs*1000).toString(),"text");
  diagnostic.measure("CENSOR_UPLOAD",android.os.SystemClock.elapsedRealtime()-upload,bytes.length,0);
  MusicMailbox.submit(runtime,session.visionRoot,folder);diagnostic.record("CENSOR_SUBMITTED");
  MusicMailbox.await(runtime,session.visionRoot,folder,session.cancel,diagnostic::record,state->{String stage=state.optString("stage");if(ColabDiagnostics.stageKnown(stage))diagnostic.record("STAGE_"+stage.toUpperCase(Locale.ROOT));progress.update(state.optInt("progress",40),"تحليل المشهد وتتبع الحجب · "+startMs/1000+" — "+endMs/1000+" ثانية");});
  JSONObject manifest=ColabRuntime.parseJson(runtime.getText(folder+"/result.json",65536));long size=manifest.optLong("bytes",-1);
  if(manifest.optInt("protocol")!=1||!folder.equals(manifest.optString("job"))||!source.equals(manifest.optString("source"))||!CensorTimeline.FINGERPRINT.equals(manifest.optString("fingerprint"))||!"cuda".equals(manifest.optString("provider"))||manifest.optLong("core_start_us",-1)!=startMs*1000||manifest.optLong("core_end_us",-1)!=endMs*1000||size<=0||size>CensorTimeline.MAX_BYTES)throw new IOException("Colab · CENSOR_SCHEMA");
  long fetched=android.os.SystemClock.elapsedRealtime();byte[] result=runtime.getBinary(folder+"/timeline.json",8*1024*1024);
  if(result.length!=size||!ColabMusic.sha(result).equals(manifest.optString("sha256")))throw new IOException("Colab · CENSOR_OUTPUT_HASH");
  JSONObject payload=new JSONObject(new String(result,StandardCharsets.UTF_8));CensorTimeline.parse(payload,source,startMs*1000,endMs*1000);
  diagnostic.measure("CENSOR_GPU",payload.optLong("gpu_ms",0),0,payload.getJSONArray("frames").length());diagnostic.measure("CENSOR_FETCH",android.os.SystemClock.elapsedRealtime()-fetched,result.length,payload.getJSONArray("frames").length());
  diagnostic.record("CENSOR_RESULT_VERIFIED");return result;
 }
 private CensorCloudJob(){}
}
