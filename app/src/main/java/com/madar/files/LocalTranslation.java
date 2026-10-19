package com.madar.files;
import com.google.mlkit.nl.translate.*;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.android.gms.tasks.Tasks;
import org.json.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
final class LocalTranslation {
 static JSONArray translate(JSONArray cues,String source,String target,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception{
  if(source.equals(target))return cues;String from=TranslateLanguage.fromLanguageTag(source),to=TranslateLanguage.fromLanguageTag(target);if(from==null||to==null)throw new IllegalArgumentException("لغة الترجمة غير مدعومة");
  try(Translator translator=Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())){
   progress.update(5,"تجهيز نموذج الترجمة؛ أول استخدام يحتاج تنزيله عبر الإنترنت. النص يعالج محليًا");Tasks.await(translator.downloadModelIfNeeded(new DownloadConditions.Builder().build()),5,TimeUnit.MINUTES);JSONArray out=new JSONArray();
   for(int i=0;i<cues.length();i++){MusicSeparator.check(cancel);JSONObject item=cues.getJSONObject(i);String text=Tasks.await(translator.translate(item.getString("text")),60,TimeUnit.SECONDS);out.put(new JSONObject(item.toString()).put("text",text));progress.update(10+(int)(85L*(i+1)/Math.max(1,cues.length())),"ترجمة محلية · "+(i+1)+" / "+cues.length());}return out;
  }
 }
}
