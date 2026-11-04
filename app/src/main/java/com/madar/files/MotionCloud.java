package com.madar.files;
import android.content.Context;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
/** Owns one bounded Colab task and always attempts cleanup, including on cancellation. */
final class MotionCloud {
 static final int CHUNK=750000;
 interface Progress{void update(int percent,String message)throws Exception;}
 static String stage(String key){switch(key){case "sources":return "قراءة معلومات الرابط والصور";case "director_download":return "تحميل محرك التخطيط وملفات النموذج";case "director_load":return "تحميل نموذج التخطيط إلى الذاكرة";case "director_cuda":return "فهم الفكرة والصور بالتسريع المتاح";case "director_cpu":return "فهم الفكرة والصور وتخطيط الإعلان";case "director_plan":return "كتابة تسلسل المشاهد";case "director_review":return "مراجعة النصوص والمعلومات";case "director":return "فهم الفكرة والصور وتخطيط الإعلان";case "design":return "تصميم المشاهد والحركات";case "speech_install":return "تجهيز محرك الصوت المعتمد";case "speech_download":return "تحميل ملفات الصوت المعتمد";case "speech_load":return "تحميل نموذج الصوت المعتمد";case "speech_probe":return "فحص الصوت قبل تخطيط المشاهد";case "speech_infer":return "توليد جمل التعليق الرجالي المعتمد";case "speech":return "توليد التعليق الرجالي المعتمد";case "audio":return "تجهيز التسجيل";case "install":return "تجهيز محرك الموشن والمزامنة";case "align":return "قياس توقيت الكلمات من التسجيل";case "render":return "تصدير الموشن بالصوت والصور";case "verify":return "التحقق من الملفات";case "done":return "استرجاع النتيجة";default:return "بدء محرك الاستوديو";}}
 static String failure(String code){if(code.startsWith("SPEECH_"))return "تعذر إكمال تجهيز أو توليد الصوت الرجالي المعتمد. المدخلات محفوظة، وتقرير هذه المحاولة يحدد خطوة الصوت التي توقفت";switch(code){case "DIRECTOR_CONTEXT":return "المراجع أطول مما يستطيع هذا التشغيل قراءته معًا. المدخلات محفوظة؛ اختصر الوصف أو قلّل الصور والروابط ثم أعد المحاولة";case "SOURCE_UNAVAILABLE":case "SOURCE_EMPTY":case "SOURCE_TYPE":case "SOURCE_SIZE":return "تعذرت قراءة الصفحة. أرسل وصفك أو الصور واكتب «بدون الرابط» لأكمل منها";case "SOURCE_URL":case "SOURCE_HOST":return "أرسل رابط صفحة عامة متاحًا، أو اكتب «بدون الرابط» مع وصف الفكرة";case "RECORDING_NO_WORDS":return "لم أستطع تمييز كلام واضح في التسجيل. أرفق تسجيلًا أوضح أو اختر الصوت المعتمد";case "STORY_INVALID":case "DIRECTOR_JSON":case "COMPOSITION_INVALID":return "لم ينجح تخطيط هذا الطلب؛ مدخلاتك محفوظة. جرّب توضيح الفكرة أو تعديل المراجع";case "OUTPUT_FORMAT":return "لم يجتز الفيديو فحص التصدير؛ المشروع محفوظ ويمكن إعادة المحاولة";default:return "توقفت معالجة الموشن قبل اكتمالها؛ المدخلات والمشروع محفوظان ويمكن إعادة المحاولة";}}
 static boolean retryStart(String code){return code!=null&&(code.contains("WS_")||code.contains("WORKER_NOT_STARTED"));}
 static String message(Exception error){
  String code=String.valueOf(error.getMessage());
  if(retryStart(code))return "تعذر بدء اتصال الموشن مع Colab بعد إعادة المحاولة؛ طلبك ومرفقاتك محفوظة. يتوفر تقرير هذه المحاولة في «حالة آخر معالجة Colab»";
  if(code.contains("MOTION_HEARTBEAT"))return "انقطع تحديث عامل الموشن؛ احتُفظ بطلبك ومرفقاتك. راجع تقرير هذه المحاولة في إعدادات Colab";
  if(code.contains("MOTION_"))return "توقف عامل الموشن قبل اكتماله؛ طلبك ومرفقاتك محفوظة. يتوفر تقرير هذه المحاولة في إعدادات Colab";
  if(code.matches("Colab · [A-Z0-9_]+"))return "تعذر استكمال اتصال معالجة الموشن؛ طلبك ومرفقاتك محفوظة. راجع تقرير هذه المحاولة في إعدادات Colab";
  return error instanceof IOException?error.getMessage():"تعذرت معالجة الموشن؛ طلبك ومرفقاتك محفوظة";
 }
 static final class WorkerProgress {
  final String folder;final ColabDiagnostics diagnostic;final Progress progress;long beat=-1,lastBeat=System.nanoTime();String stageKey="",detailKey="",engineKey="";
  WorkerProgress(String folder,ColabDiagnostics diagnostic,Progress progress){this.folder=folder;this.diagnostic=diagnostic;this.progress=progress;}
  boolean started(JSONObject state)throws Exception{accept(state);return true;}
  boolean accept(JSONObject state)throws Exception{
   if(!folder.equals(state.optString("job")))throw new IOException("Colab · MOTION_JOB_PATH");
   String key=state.optString("stage");
   if(!Arrays.asList("bootstrap","sources","director","director_download","director_load","director_cuda","director_cpu","director_plan","director_review","design","speech","speech_install","speech_download","speech_load","speech_probe","speech_infer","audio","install","align","render","verify","done","failed").contains(key))throw new IOException("Colab · RESPONSE_INVALID_JSON");
   long heartbeat=state.optLong("heartbeat",-1);if(heartbeat<0)throw new IOException("Colab · RESPONSE_INVALID_JSON");
   if(heartbeat!=beat){beat=heartbeat;lastBeat=System.nanoTime();}
   if(System.nanoTime()-lastBeat>TimeUnit.MINUTES.toNanos(2))throw new IOException("Colab · MOTION_HEARTBEAT");
   if(!stageKey.equals(key)){diagnostic.record("MOTION_STAGE_"+key.toUpperCase(Locale.ROOT));stageKey=key;}String detail=state.optString("detail");if(!detail.equals(detailKey)&&detail.matches("uv|python|torch|packages|validate")){diagnostic.record("SPEECH_SETUP_"+detail.toUpperCase(Locale.ROOT));detailKey=detail;}String engine=state.optString("engine");if(!engine.equals(engineKey)&&engine.matches("cuda|cpu")){diagnostic.record("MOTION_ENGINE_"+engine.toUpperCase(Locale.ROOT));engineKey=engine;}
   if(key.equals("failed")){String failedAt=state.optString("failureStage");if(Arrays.asList("speech_install","speech_download","speech_load","speech_probe","speech_infer","align","render","director","design").contains(failedAt))diagnostic.record("MOTION_STAGE_"+failedAt.toUpperCase(Locale.ROOT));diagnostic.record("MOTION_WORKER_FAILED");diagnostic.issue(new IOException(state.optString("code")));throw new IOException(failure(state.optString("code","WORKER_FAILED")),new IOException("Colab · "+state.optString("code","WORKER_FAILED")));}
   String label=stage(key);int scene=state.optInt("scene"),total=state.optInt("total");if(scene>0&&total>=scene&&total<=8)label+=" · "+scene+" / "+total;progress.update(Math.max(18,Math.min(99,state.optInt("progress",18))),label);return key.equals("done");
  }
 }
 static JSONObject run(Context c,JSONObject project,String mode,ColabCredentials.Grant grant,AtomicBoolean cancel,Progress progress)throws Exception{
  ColabDiagnostics diagnostic=new ColabDiagnostics(c,"MOTION_"+mode.toUpperCase(Locale.ROOT));
  try{JSONObject result=execute(c,project,mode,grant,cancel,progress,diagnostic);diagnostic.record("DONE");return result;}
  catch(Exception error){diagnostic.failed(error);if(error instanceof java.util.concurrent.CancellationException)throw error;throw new IOException(message(error),error);}
 }
 private static JSONObject execute(Context c,JSONObject project,String mode,ColabCredentials.Grant grant,AtomicBoolean cancel,Progress progress,ColabDiagnostics diagnostic)throws Exception{
  String id=project.getString("id");MotionProject.validate(project,mode.equals("render"));Map<String,File> files=new LinkedHashMap<>();
  if(mode.equals("align")&&project.getString("voiceMode").equals("google")){JSONArray scenes=project.getJSONArray("scenes");for(int i=0;i<scenes.length();i++){MusicSeparator.check(cancel);progress.update(2+i*8/scenes.length(),"قوقل يولّد تعليق المشهد "+(i+1)+" / "+scenes.length());files.put("speech_"+i+".wav",MotionSpeech.synthesize(c,project,scenes.getJSONObject(i),cancel));}}
  if(mode.equals("align")&&project.getString("voiceMode").equals("recording"))files.put("recording.media",MotionProject.asset(c,id,"recording.media"));
  if(mode.equals("compose")){JSONObject brief=project.getJSONObject("creativeRequest");JSONArray images=brief.getJSONArray("images");for(int i=0;i<images.length();i++){String name=images.getString(i);files.put(name,MotionProject.asset(c,id,name));}if(project.getString("voiceMode").equals("recording"))files.put("recording.media",MotionProject.asset(c,id,"recording.media"));}
  if(mode.equals("compose")&&project.optString("motionCheckpoint").equals("voiced"))for(int i=0;i<project.getJSONArray("scenes").length();i++){String name="speech_"+i+".wav";files.put(name,MotionProject.asset(c,id,name));}
  if(mode.equals("render")){if(!project.getString("voiceMode").equals("none"))files.put("voice.wav",MotionProject.asset(c,id,"voice.wav"));for(String image:MotionCreative.assets(project))files.put(image,MotionProject.asset(c,id,image));}
  if(grant==null)throw new IOException("أعد الاتصال بـ Colab من الاستوديو");
  try(ColabRuntime runtime=new ColabRuntime(c,grant,cancel)){
   String checkpointFolder=null;
   try{
    diagnostic.record("RUNTIME_CREATE");runtime.create((p,m)->{try{progress.update(p,m);}catch(Exception e){throw new RuntimeException(e);}});long until=System.nanoTime()+TimeUnit.MINUTES.toNanos(3);
    while(true){MusicSeparator.check(cancel);try{runtime.jupyter("GET","api/kernelspecs",null,1024*1024);break;}catch(ColabRuntime.HttpFailure e){if(e.status!=404&&e.status<500&&e.status!=429)throw e;if(System.nanoTime()>until)throw new IOException("تأخر بدء Colab؛ أعد المحاولة");Thread.sleep(1500);}catch(ColabRuntime.NetworkFailure e){if(System.nanoTime()>until)throw e;Thread.sleep(1500);}}
    diagnostic.record("JUPYTER_READY");String folder="madar_"+MotionProject.id();checkpointFolder=folder;runtime.jupyter("PUT","api/contents/"+folder,new JSONObject().put("type","directory"),1024*1024);
    diagnostic.record("KERNEL_CREATE");JSONObject kernel=runtime.jupyter("POST","api/kernels",new JSONObject().put("name","python3").put("path",folder),1024*1024);String kernelId=kernel.getString("id");if(!kernelId.matches("[a-zA-Z0-9-]{1,100}"))throw new IOException("نواة Colab غير صالحة");
    {
     diagnostic.record("MOTION_UPLOAD");
     for(String name:new String[]{"core.js","product.js","creative.js","Root.tsx","render.cjs","package.json","worker.py","director.py","director_runtime.py","speech.py","speech_runtime.py","sources.py","male-reference.b64","male-reference.txt","Cairo.ttf"}){MusicSeparator.check(cancel);try(InputStream in=c.getAssets().open("motion/"+name);ByteArrayOutputStream out=new ByteArrayOutputStream()){FilesUtil.copy(in,out);runtime.put(folder+"/"+name,name.endsWith(".ttf")?Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP):out.toString("UTF-8"),name.endsWith(".ttf")?"base64":"text");}}
     JSONArray manifest=new JSONArray();long total=0;for(Map.Entry<String,File> entry:files.entrySet()){File f=entry.getValue();if(!f.isFile()||f.length()<1||f.length()>32L*1024*1024||(total+=f.length())>96L*1024*1024)throw new IOException("الصوت أو الصور كبيرة جدًا؛ اختر ملفات أصغر");int count=(int)((f.length()+CHUNK-1)/CHUNK);manifest.put(new JSONObject().put("name",entry.getKey()).put("size",f.length()).put("sha256",MotionProject.hash(f)).put("chunks",count));try(InputStream in=new FileInputStream(f)){for(int n=0;n<count;n++){MusicSeparator.check(cancel);byte[] bytes=new byte[(int)Math.min(CHUNK,f.length()-(long)n*CHUNK)];int read=0;while(read<bytes.length){int countRead=in.read(bytes,read,bytes.length-read);if(countRead<0)throw new IOException("ملف مصدر ناقص");read+=countRead;}runtime.put(folder+"/"+entry.getKey()+".part_"+n,Base64.encodeToString(bytes,Base64.NO_WRAP),"base64");progress.update(18,"رفع ملفات المشروع · "+entry.getKey().split("\\.")[0]);}}}
     runtime.put(folder+"/request.json",new JSONObject().put("protocol",1).put("mode",mode).put("project",project).put("files",manifest).toString(),"text");
     diagnostic.record("UPLOAD_COMPLETE");
     String bootstrap;try(InputStream in=c.getAssets().open("motion/bootstrap.py");ByteArrayOutputStream out=new ByteArrayOutputStream()){FilesUtil.copy(in,out);bootstrap=out.toString("UTF-8");}
     runtime.put(folder+"/bootstrap.py",bootstrap,"text");
     String code="JOB_FOLDER = "+JSONObject.quote(folder)+"\n"+bootstrap;
     long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(41);
     WorkerProgress watch=new WorkerProgress(folder,diagnostic,progress);
     // Upload first; only then open a fresh channel. A detached, locked launcher
     // permits one safe re-submission if the channel loses its acknowledgement.
     for(int attempt=0;;attempt++){
      try(ColabKernel.Session channel=runtime.openKernel(kernelId,diagnostic::record)){
       channel.execute(code,()->watch.started(ColabRuntime.parseJson(runtime.getText(folder+"/status.json",65536))));break;
      }catch(IOException error){if(attempt>=1||!retryStart(error.getMessage()))throw error;diagnostic.issue(error);diagnostic.record("MOTION_START_RETRY");progress.update(18,"استعادة بدء الموشن؛ لم يُنشأ طلب جديد");}
     }
     long lastRest=System.nanoTime(),missingSince=lastRest;
     while(true){
      MusicSeparator.check(cancel);if(System.nanoTime()>deadline)throw new IOException("Colab · MOTION_PROCESS_TIMEOUT");
      try{if(watch.accept(ColabRuntime.parseJson(runtime.getText(folder+"/status.json",65536))))break;lastRest=System.nanoTime();}
      catch(ColabRuntime.HttpFailure error){if(error.status==404){if(System.nanoTime()-missingSince>TimeUnit.MINUTES.toNanos(2))throw new IOException("Colab · WORKER_NOT_STARTED");}else if(error.status==429||error.status>=500){if(System.nanoTime()-lastRest>TimeUnit.MINUTES.toNanos(2))throw error;diagnostic.record("PROGRESS_RETRY");}else throw error;}
      catch(ColabRuntime.NetworkFailure error){if(System.nanoTime()-lastRest>TimeUnit.MINUTES.toNanos(2))throw error;diagnostic.record("PROGRESS_RETRY");}
      Thread.sleep(2000);
     }
     diagnostic.record("FETCH_RESULT");
     JSONObject result=ColabRuntime.parseJson(runtime.getText(folder+"/result.json",2*1024*1024));if(result.getInt("protocol")!=1||!mode.equals(result.getString("mode")))throw new IOException("نتيجة استوديو غير صالحة");JSONObject updated=MotionProject.validate(result.getJSONObject("project"),mode.equals("render"));if(!id.equals(updated.getString("id")))throw new IOException("المشروع غير مطابق");
     JSONObject meta=result.optJSONObject("file");if(meta!=null){long size=meta.getLong("size");int chunks=meta.getInt("chunks");String hash=meta.getString("sha256");if(size<1||size>128L*1024*1024||chunks!=(size+CHUNK-1)/CHUNK||!hash.matches("[a-f0-9]{64}"))throw new IOException("حجم نتيجة غير صالح");File output=mode.equals("align")?MotionProject.asset(c,id,"voice.wav"):FilesUtil.output(c,"mp4");File temp=new File(output.getPath()+".partial");try{try(OutputStream out=new FileOutputStream(temp)){for(int n=0;n<chunks;n++){MusicSeparator.check(cancel);byte[] bytes=runtime.getBinary(folder+"/result_"+n+".bin",2*1024*1024);if(bytes.length!=Math.min(CHUNK,size-(long)n*CHUNK))throw new IOException("نتيجة صوت أو فيديو ناقصة");out.write(bytes);progress.update(98,"تحميل النتيجة · "+(n+1)+" / "+chunks);}}if(temp.length()!=size||!hash.equals(MotionProject.hash(temp)))throw new IOException("لم تتطابق بصمة النتيجة");MusicSeparator.check(cancel);if(!temp.renameTo(output))throw new IOException("تعذر تثبيت النتيجة");if(!mode.equals("align"))updated.put("result",FilesUtil.info(output));else updated.put("audio","voice.wav").put("audioRevision",System.currentTimeMillis());}finally{temp.delete();}}
     JSONArray extras=result.optJSONArray("assets");if(extras!=null){if(extras.length()>9)throw new IOException("ملفات نتيجة كثيرة");long totalExtras=0;Set<String> seen=new HashSet<>();for(int i=0;i<extras.length();i++){JSONObject extra=extras.getJSONObject(i);String name=extra.getString("name");if(!name.matches("voice\\.wav|asset_[a-f0-9]{32}\\.jpg")||!seen.add(name))throw new IOException("ملف نتيجة غير صالح");long size=extra.getLong("size");int count=extra.getInt("chunks");String hash=extra.getString("sha256");if(size<1||size>32L*1024*1024||(totalExtras+=size)>96L*1024*1024||count!=(size+CHUNK-1)/CHUNK||!hash.matches("[a-f0-9]{64}"))throw new IOException("حجم ملف نتيجة غير صالح");File dest=MotionProject.asset(c,id,name),temp=new File(dest.getPath()+".partial");try{try(OutputStream out=new FileOutputStream(temp)){for(int n=0;n<count;n++){MusicSeparator.check(cancel);byte[] bytes=runtime.getBinary(folder+"/asset_"+i+"_"+n+".bin",2*1024*1024);if(bytes.length!=Math.min(CHUNK,size-(long)n*CHUNK))throw new IOException("ملف نتيجة ناقص");out.write(bytes);}}if(!hash.equals(MotionProject.hash(temp))||!temp.renameTo(dest))throw new IOException("تعذر التحقق من ملف النتيجة");}finally{temp.delete();}if(name.equals("voice.wav"))updated.put("audio","voice.wav").put("audioRevision",System.currentTimeMillis());}}
     if(!project.optString("agentThread").equals(updated.optString("agentThread")))throw new IOException("المحادثة غير مطابقة");
     MotionProject.save(c,updated);return updated;
    }
   }catch(Exception error){if(mode.equals("compose")&&!cancel.get()&&checkpointFolder!=null)try{MotionCheckpoint.recover(c,runtime,checkpointFolder,project,cancel);diagnostic.record("MOTION_CHECKPOINT_SAVED");}catch(Exception ignored){diagnostic.record("MOTION_CHECKPOINT_UNAVAILABLE");}throw error;
   }finally{try{progress.update(99,"إغلاق جلسة الاستوديو على Colab");}catch(Exception ignored){}boolean cleaned=runtime.cleanup();diagnostic.record(cleaned?"CLEANUP_OK":"CLEANUP_PENDING");if(!cleaned)c.getSharedPreferences("motion-studio",0).edit().putString("cleanupWarning","لم يُؤكَّد إغلاق جلسة Colab؛ يمكنك إنهاؤها من إعدادات الاتصال").apply();else c.getSharedPreferences("motion-studio",0).edit().remove("cleanupWarning").apply();}
  }
 }
}
