package com.madar.files;
import com.google.mlkit.nl.translate.*;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.android.gms.tasks.Tasks;
import org.json.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
final class LocalTranslation {
 private static <T> T await(com.google.android.gms.tasks.Task<T> task,AtomicBoolean cancel,long timeout)throws Exception{long deadline=android.os.SystemClock.elapsedRealtime()+timeout;while(true){if(cancel.get())throw new CancellationException();long left=deadline-android.os.SystemClock.elapsedRealtime();if(left<=0)throw new TimeoutException("انتهت مهلة تنزيل النموذج أو الترجمة");try{return Tasks.await(task,Math.min(250,left),TimeUnit.MILLISECONDS);}catch(TimeoutException e){if(android.os.SystemClock.elapsedRealtime()>=deadline)throw e;}}}
 static JSONArray translate(JSONArray cues,String source,String target,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception{
  return translate(cues,source,target,"",cancel,progress);
 }
 static JSONArray translate(JSONArray cues,String source,String target,String names,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception{
  SubtitleNames glossary=new SubtitleNames(names);cues=group(cues,source);
  String to=TranslateLanguage.fromLanguageTag(target);if(to==null)throw new IllegalArgumentException("لغة الترجمة غير مدعومة");
  java.util.Map<String,Translator> engines=new java.util.HashMap<>();
  try{
   JSONArray out=new JSONArray();
   for(int i=0;i<cues.length();i++){
    if(cancel.get())throw new CancellationException();JSONObject item=cues.getJSONObject(i);String language=source.equals("auto")?item.optString("language",""):source;
    String from=TranslateLanguage.fromLanguageTag(language);if(from==null)throw new IllegalArgumentException("لم تُحدّد لغة الكلام بوضوح؛ اختر لغة النص الأصلي قبل ترجمته");
    String text=item.getString("text");
    if(!language.equals(target)){
     Translator translator=engines.get(language);
     if(translator==null){translator=Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build());engines.put(language,translator);progress.update(5,"تجهيز الترجمة المحلية الأساسية · "+language+" إلى "+target);await(translator.downloadModelIfNeeded(new DownloadConditions.Builder().build()),cancel,300000);}
     SubtitleNames.Protected protectedText=glossary.protect(text);
     text=protectedText.restore(await(translator.translate(protectedText.text),cancel,60000));
    }
    if(text==null||text.trim().isEmpty())throw new java.io.IOException("لم يُرجع المحرك المحلي نصًا لأحد الأسطر؛ احتُفظ بالتفريغ الأصلي");
    out.put(new JSONObject(item.toString()).put("text",text));progress.update(10+(int)(85L*(i+1)/Math.max(1,cues.length())),"ترجمة محلية أساسية · "+(i+1)+" / "+cues.length());
   }
   return out;
  }finally{for(Translator translator:engines.values())translator.close();}
 }
 static JSONArray group(JSONArray cues,String source)throws Exception {
  JSONArray out=new JSONArray();JSONObject current=null;
  for(int i=0;i<cues.length();i++){
   JSONObject cue=cues.getJSONObject(i);String language=source.equals("auto")?cue.optString("language",""):source;
   String previous=current==null?"":current.getString("text");
   if(current!=null&&(source.equals("auto")?current.optString("language"):source).equals(language)&&cue.getDouble("start")>=current.getDouble("start")&&cue.getDouble("start")-current.getDouble("end")<=.35&&cue.getDouble("end")-current.getDouble("start")<=12&&previous.length()+cue.getString("text").length()<600&&!previous.matches("(?s).*[。！？!?…]$")){
    current.put("text",previous+(language.equals("ja")?"":" ")+cue.getString("text")).put("end",Math.max(current.getDouble("end"),cue.getDouble("end")));
   }else{current=new JSONObject(cue.toString());if(source.equals("auto")||cue.has("language"))current.put("language",language);out.put(current);}
  }
  return out;
 }
}
