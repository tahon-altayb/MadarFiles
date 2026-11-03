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
 static String stage(String key){switch(key){case "audio":return "تجهيز التسجيل";case "install":return "تجهيز محرك الموشن والمزامنة";case "align":return "قياس توقيت الكلمات من التسجيل";case "render":return "تصدير الموشن بالصوت والصور";case "verify":return "التحقق من الملفات";case "done":return "استرجاع النتيجة";default:return "بدء محرك الاستوديو";}}
 static JSONObject run(Context c,JSONObject project,String mode,ColabCredentials.Grant grant,AtomicBoolean cancel,Progress progress)throws Exception{
  String id=project.getString("id");MotionProject.validate(project,mode.equals("render"));Map<String,File> files=new LinkedHashMap<>();
  if(mode.equals("align")&&project.getString("voiceMode").equals("google")){JSONArray scenes=project.getJSONArray("scenes");for(int i=0;i<scenes.length();i++){MusicSeparator.check(cancel);progress.update(2+i*8/scenes.length(),"قوقل يولّد تعليق المشهد "+(i+1)+" / "+scenes.length());files.put("speech_"+i+".wav",MotionSpeech.synthesize(c,project,scenes.getJSONObject(i),cancel));}}
  if(mode.equals("align")&&project.getString("voiceMode").equals("recording"))files.put("recording.media",MotionProject.asset(c,id,"recording.media"));
  if(mode.equals("render")){if(!project.getString("voiceMode").equals("none"))files.put("voice.wav",MotionProject.asset(c,id,"voice.wav"));JSONArray scenes=project.getJSONArray("scenes");for(int i=0;i<scenes.length();i++){String image=scenes.getJSONObject(i).getString("image");if(!image.isEmpty())files.put(image,MotionProject.asset(c,id,image));}}
  if(grant==null)throw new IOException("أعد الاتصال بـ Colab من الاستوديو");
  try(ColabRuntime runtime=new ColabRuntime(c,grant,cancel)){
   try{
    runtime.create((p,m)->{try{progress.update(p,m);}catch(Exception e){throw new RuntimeException(e);}});long until=System.nanoTime()+TimeUnit.MINUTES.toNanos(3);
    while(true){MusicSeparator.check(cancel);try{runtime.jupyter("GET","api/kernelspecs",null,1024*1024);break;}catch(ColabRuntime.HttpFailure e){if(e.status!=404&&e.status<500&&e.status!=429)throw e;if(System.nanoTime()>until)throw new IOException("تأخر بدء Colab؛ أعد المحاولة");Thread.sleep(1500);}catch(ColabRuntime.NetworkFailure e){if(System.nanoTime()>until)throw e;Thread.sleep(1500);}}
    String folder="madar_"+MotionProject.id();runtime.jupyter("PUT","api/contents/"+folder,new JSONObject().put("type","directory"),1024*1024);
    JSONObject kernel=runtime.jupyter("POST","api/kernels",new JSONObject().put("name","python3").put("path",folder),1024*1024);String kernelId=kernel.getString("id");if(!kernelId.matches("[a-zA-Z0-9-]{1,100}"))throw new IOException("نواة Colab غير صالحة");
    try(ColabKernel.Session channel=runtime.openKernel(kernelId,code->{})){
     for(String name:new String[]{"core.js","product.js","Root.tsx","render.cjs","package.json","worker.py","Cairo.ttf"}){MusicSeparator.check(cancel);try(InputStream in=c.getAssets().open("motion/"+name);ByteArrayOutputStream out=new ByteArrayOutputStream()){FilesUtil.copy(in,out);runtime.put(folder+"/"+name,name.endsWith(".ttf")?Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP):out.toString("UTF-8"),name.endsWith(".ttf")?"base64":"text");}}
     JSONArray manifest=new JSONArray();long total=0;for(Map.Entry<String,File> entry:files.entrySet()){File f=entry.getValue();if(!f.isFile()||f.length()<1||f.length()>32L*1024*1024||(total+=f.length())>96L*1024*1024)throw new IOException("الصوت أو الصور كبيرة جدًا؛ اختر ملفات أصغر");int count=(int)((f.length()+CHUNK-1)/CHUNK);manifest.put(new JSONObject().put("name",entry.getKey()).put("size",f.length()).put("sha256",MotionProject.hash(f)).put("chunks",count));try(InputStream in=new FileInputStream(f)){for(int n=0;n<count;n++){MusicSeparator.check(cancel);byte[] bytes=new byte[(int)Math.min(CHUNK,f.length()-(long)n*CHUNK)];int read=0;while(read<bytes.length){int countRead=in.read(bytes,read,bytes.length-read);if(countRead<0)throw new IOException("ملف مصدر ناقص");read+=countRead;}runtime.put(folder+"/"+entry.getKey()+".part_"+n,Base64.encodeToString(bytes,Base64.NO_WRAP),"base64");progress.update(18,"رفع ملفات المشروع · "+entry.getKey().split("\\.")[0]);}}}
     runtime.put(folder+"/request.json",new JSONObject().put("protocol",1).put("mode",mode).put("project",project).put("files",manifest).toString(),"text");
     String code="import pathlib, subprocess, sys, os\nname="+JSONObject.quote(folder)+"\np=next(p for p in [pathlib.Path.cwd(),pathlib.Path.cwd()/name,pathlib.Path('/content')/name] if p.name==name and (p/'request.json').is_file())\nos.chdir(p)\nsubprocess.run([sys.executable, 'worker.py'], check=True, timeout=2400)\n";
     long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(41);
     channel.execute(code,()->{MusicSeparator.check(cancel);if(System.nanoTime()>deadline)throw new IOException("انتهت مهلة الاستوديو؛ احتُفظ بالمشروع");JSONObject state=ColabRuntime.parseJson(runtime.getText(folder+"/progress.json",65536));if(state.optString("stage").equals("failed"))throw new IOException("تعذرت معالجة الموشن · "+(state.optString("code", "").matches("[A-Z_]{1,40}")?state.optString("code"):"WORKER_FAILED"));progress.update(Math.max(18,Math.min(99,state.optInt("progress",18))),stage(state.optString("stage")));return state.optString("stage").equals("done");});
     JSONObject result=ColabRuntime.parseJson(runtime.getText(folder+"/result.json",2*1024*1024));if(result.getInt("protocol")!=1||!mode.equals(result.getString("mode")))throw new IOException("نتيجة استوديو غير صالحة");JSONObject updated=MotionProject.validate(result.getJSONObject("project"),mode.equals("render"));if(!id.equals(updated.getString("id")))throw new IOException("المشروع غير مطابق");
     JSONObject meta=result.optJSONObject("file");if(meta!=null){long size=meta.getLong("size");int chunks=meta.getInt("chunks");String hash=meta.getString("sha256");if(size<1||size>128L*1024*1024||chunks!=(size+CHUNK-1)/CHUNK||!hash.matches("[a-f0-9]{64}"))throw new IOException("حجم نتيجة غير صالح");File output=mode.equals("align")?MotionProject.asset(c,id,"voice.wav"):FilesUtil.output(c,"mp4");File temp=new File(output.getPath()+".partial");try{try(OutputStream out=new FileOutputStream(temp)){for(int n=0;n<chunks;n++){MusicSeparator.check(cancel);byte[] bytes=runtime.getBinary(folder+"/result_"+n+".bin",2*1024*1024);if(bytes.length!=Math.min(CHUNK,size-(long)n*CHUNK))throw new IOException("نتيجة صوت أو فيديو ناقصة");out.write(bytes);progress.update(98,"تحميل النتيجة · "+(n+1)+" / "+chunks);}}if(temp.length()!=size||!hash.equals(MotionProject.hash(temp)))throw new IOException("لم تتطابق بصمة النتيجة");MusicSeparator.check(cancel);if(!temp.renameTo(output))throw new IOException("تعذر تثبيت النتيجة");if(mode.equals("render"))updated.put("result",FilesUtil.info(output));else updated.put("audio","voice.wav").put("audioRevision",System.currentTimeMillis());}finally{temp.delete();}}
     MotionProject.save(c,updated);return updated;
    }
   }finally{try{progress.update(99,"إغلاق جلسة الاستوديو على Colab");}catch(Exception ignored){}if(!runtime.cleanup())c.getSharedPreferences("motion-studio",0).edit().putString("cleanupWarning","لم يُؤكَّد إغلاق جلسة Colab؛ يمكنك إنهاؤها من إعدادات الاتصال").apply();else c.getSharedPreferences("motion-studio",0).edit().remove("cleanupWarning").apply();}
  }
 }
}
