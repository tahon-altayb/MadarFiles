package com.madar.files;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Generates one image (SDXL on T4) or one 5-second clip (Wan 2.2 TI2V-5B on L4/A100) in the owner's
 * own Colab session and downloads it with a verified SHA-256. The session is closed afterwards.
 */
final class GenerateCloud {
 interface Progress {void update(int percent,String message)throws Exception;}
 static final List<String> STYLES=Arrays.asList("anime","realistic","cartoon","cinematic");
 static final List<String> ASPECTS=Arrays.asList("9:16","16:9","1:1");

 /** Bounded, validated request: English prompt, known style/aspect, 1-5 seconds for video. */
 static JSONObject request(JSONObject in)throws Exception{
  String mode=in.optString("mode");if(!Arrays.asList("image","video").contains(mode))throw new IOException("نوع التوليد غير صالح");
  String prompt=in.optString("prompt").trim();if(prompt.length()<3||prompt.length()>1500||prompt.matches("(?s).*[\\p{Cntrl}&&[^\\n]].*"))throw new IOException("اكتب وصفًا أوضح للتوليد");
  String style=STYLES.contains(in.optString("style"))?in.optString("style"):"realistic",aspect=ASPECTS.contains(in.optString("aspect"))?in.optString("aspect"):"9:16";
  double seconds=in.optDouble("seconds",5);if(!(seconds>=1&&seconds<=5))seconds=5;
  return new JSONObject().put("protocol",1).put("mode",mode).put("prompt",prompt).put("negative",in.optString("negative","").length()>600?in.optString("negative").substring(0,600):in.optString("negative","")).put("style",style).put("aspect",aspect).put("seconds",seconds).put("seed",new Random().nextInt(Integer.MAX_VALUE));
 }

 static String asset(Context c,String name)throws IOException{try(InputStream in=c.getAssets().open("colab/"+name);ByteArrayOutputStream out=new ByteArrayOutputStream()){FilesUtil.copy(in,out);return out.toString("UTF-8");}}

 static String message(String stage,int percent,boolean video){
  switch(stage){
   case "bootstrap":return "تجهيز جلسة التوليد";
   case "install":return "تثبيت أدوات التوليد على Colab";
   case "model":return video?"تنزيل نموذج الفيديو (حوالي 20 جيجا) — أول مرة تاخذ وقت":"تنزيل نموذج الصور";
   case "generate":return video?"توليد المقطع على كرت Colab":"توليد الصورة على كرت Colab";
   case "encode":return "تجهيز ملف الفيديو";
   case "verify":case "done":return "تجهيز النتيجة للتنزيل";
   default:return "التوليد يشتغل";
  }
 }

 static File run(Context c,JSONObject input,ColabCredentials.Grant grant,AtomicBoolean cancel,Progress progress)throws Exception{
  JSONObject job=request(input);boolean video=job.getString("mode").equals("video");
  if(grant==null)throw new IOException("اتصل بحساب Colab أولًا");
  ColabRuntime runtime=new ColabRuntime(c.getApplicationContext(),grant,cancel);ColabDiagnostics diagnostic=new ColabDiagnostics(c);runtime.authEvents=diagnostic::record;runtime.transferEvents=diagnostic::record;runtime.allocationEvents=diagnostic::allocation;
  try{
   progress.update(5,video?"طلب كرت L4 للفيديو":"طلب كرت T4 للصور");
   runtime.create((p,m)->{try{progress.update(Math.min(14,p),m);}catch(Exception e){throw new RuntimeException(e);}},video?new String[]{"L4","A100"}:new String[]{"T4","L4","A100"});
   long until=System.nanoTime()+TimeUnit.MINUTES.toNanos(3);
   while(true){ColabProbe.check(cancel);try{runtime.jupyter("GET","api/kernelspecs",null,1024*1024);break;}catch(ColabRuntime.HttpFailure e){if(e.status!=404&&e.status<500&&e.status!=429)throw e;if(System.nanoTime()>until)throw new IOException("تأخر بدء Colab؛ أعد المحاولة");Thread.sleep(1500);}catch(ColabRuntime.NetworkFailure e){if(System.nanoTime()>until)throw e;Thread.sleep(1500);}}
   String folder="madar_"+MotionProject.id();runtime.jupyter("PUT","api/contents/"+folder,new JSONObject().put("type","directory"),1024*1024);
   runtime.put(folder+"/generate.py",asset(c,"generate.py"),"text");runtime.put(folder+"/request.json",job.toString(),"text");String bootstrap=asset(c,"generate_bootstrap.py");runtime.put(folder+"/bootstrap.py",bootstrap,"text");
   JSONObject kernel=runtime.jupyter("POST","api/kernels",new JSONObject().put("name","python3").put("path",folder),1024*1024);String kernelId=kernel.getString("id");if(!kernelId.matches("[a-zA-Z0-9-]{1,100}"))throw new IOException("نواة Colab غير صالحة");
   String code="JOB_FOLDER = "+JSONObject.quote(folder)+"\n"+bootstrap;
   try(ColabKernel.Session channel=runtime.openKernel(kernelId,diagnostic::record)){channel.execute(code,()->{try{return runtime.getText(folder+"/status.json",65536)!=null;}catch(ColabRuntime.HttpFailure e){if(e.status==404)return false;throw e;}});}
   long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(62),lastRest=System.nanoTime(),missingSince=lastRest,lastBeatAt=lastRest;long beat=-1;
   while(true){
    ColabProbe.check(cancel);if(System.nanoTime()>deadline)throw new IOException("التوليد أخذ وقت أطول من ساعة؛ أوقفته");
    try{
     JSONObject state=ColabRuntime.parseJson(runtime.getText(folder+"/status.json",65536));lastRest=System.nanoTime();
     if(!folder.equals(state.optString("job")))throw new IOException("Colab · GEN_JOB_PATH");
     long heartbeat=state.optLong("heartbeat",-1);if(heartbeat!=beat){beat=heartbeat;lastBeatAt=System.nanoTime();}else if(System.nanoTime()-lastBeatAt>TimeUnit.MINUTES.toNanos(3))throw new IOException("توقفت جلسة التوليد عن الاستجابة");
     String stage=state.optString("stage");int percent=Math.max(0,Math.min(99,state.optInt("progress")));
     if(stage.equals("failed"))throw new IOException(failure(state.optString("code")));
     if(stage.equals("done"))break;
     progress.update(15+percent*80/100,message(stage,percent,video));
    }catch(ColabRuntime.HttpFailure error){if(error.status==404){if(System.nanoTime()-missingSince>TimeUnit.MINUTES.toNanos(3))throw new IOException("Colab · GEN_NOT_STARTED");}else if(error.status==429||error.status>=500){if(System.nanoTime()-lastRest>TimeUnit.MINUTES.toNanos(3))throw error;}else throw error;}
    catch(ColabRuntime.NetworkFailure error){if(System.nanoTime()-lastRest>TimeUnit.MINUTES.toNanos(3))throw error;}
    Thread.sleep(2000);
   }
   return fetch(c,runtime,folder,video,cancel,progress);
  }finally{runtime.cleanup();runtime.close();}
 }

 static String failure(String code){
  switch(code){
   case "GEN_VIDEO_NEEDS_L4":case "GEN_VIDEO_GPU":return "الفيديو يحتاج كرت L4 أو A100، والجلسة جت بكرت أضعف";
   case "GEN_OUT_OF_MEMORY":return "ذاكرة الكرت ما كفت؛ جرّب مقاس أصغر أو مدة أقصر";
   case "GEN_INSTALL":return "تعذر تثبيت أدوات التوليد على Colab؛ أعد المحاولة";
   case "GEN_PROMPT":return "الوصف قصير أو طويل جدًا";
   case "GEN_PROCESS_TIMEOUT":return "التوليد أخذ وقت أطول من المسموح؛ جرّب مدة أقصر";
   default:return "تعذر التوليد على Colab ("+(code.matches("[A-Z0-9_]{1,40}")?code:"GEN_FAILED")+")";
  }
 }

 static File fetch(Context c,ColabRuntime runtime,String folder,boolean video,AtomicBoolean cancel,Progress progress)throws Exception{
  JSONObject manifest=ColabRuntime.parseJson(runtime.getText(folder+"/result.json",65536));
  String kind=manifest.optString("kind"),mime=manifest.optString("mime");long size=manifest.optLong("size",-1);int chunks=manifest.optInt("chunks",-1);String sha=manifest.optString("sha256");
  if(!kind.equals(video?"video":"image")||!mime.equals(video?"video/mp4":"image/png")||size<1||size>200L*1024*1024||chunks!=(int)((size+1_499_999)/1_500_000)||!sha.matches("[a-f0-9]{64}"))throw new IOException("Colab · GEN_RESULT_SCHEMA");
  File out=FilesUtil.output(c,video?"mp4":"png");boolean ok=false;MessageDigest digest=MessageDigest.getInstance("SHA-256");
  try(OutputStream stream=new BufferedOutputStream(new FileOutputStream(out))){
   for(int n=0;n<chunks;n++){ColabProbe.check(cancel);byte[] bytes=runtime.getBinary(folder+"/result_"+n+".bin",2*1024*1024);long expected=Math.min(1_500_000L,size-(long)n*1_500_000L);if(bytes.length!=expected)throw new IOException("Colab · GEN_OUTPUT_LENGTH");digest.update(bytes);stream.write(bytes);progress.update(96+n*3/Math.max(1,chunks),"تنزيل "+(video?"المقطع":"الصورة")+" · "+(n+1)+" / "+chunks);}
  }
  StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
  try{if(!hex.toString().equals(sha)||out.length()!=size)throw new IOException("Colab · GEN_OUTPUT_HASH");ok=true;return out;}finally{if(!ok)out.delete();}
 }
 private GenerateCloud(){}
}
