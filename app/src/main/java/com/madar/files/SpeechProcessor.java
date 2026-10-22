package com.madar.files;
import android.content.Context;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
final class SpeechProcessor {
 static{System.loadLibrary("madar_audio");}
 private static native long open(String encoder,String decoder,String tokens,String language);
 private static native String[] recognize(long handle,float[] audio);
 private static native void close(long handle);
 static JSONArray transcribe(Context c,File input,String language,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception{
  return transcribe(c,input,language,"fast",cancel,progress);
 }
 static JSONArray transcribe(Context c,File input,String language,String quality,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception{
  return transcribe(c,input,language,quality,0,cancel,progress);
 }
 static JSONArray transcribe(Context c,File input,String language,String quality,long limitUs,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception{
  if(!language.matches("auto|ar|en|ja|fr|de|es|zh"))throw new IOException("لغة غير مدعومة");AudioPipeline.Decoded decoded=null;long engine=0;
  try{File[] models=!quality.equals("fast")?SpeechModels.get(c,quality,cancel,progress):new File[]{OfflineAssets.file(c,"tiny-encoder.int8.onnx"),OfflineAssets.file(c,"tiny-decoder.int8.onnx"),OfflineAssets.file(c,"tiny-tokens.txt")};decoded=AudioPipeline.decode(c,input,limitUs,cancel,progress);progress.update(10,"تحميل نموذج التفريغ وكاشف الكلام");File vocabulary=SpeechTokens.safeVocabulary(c,models[2]);engine=open(models[0].getPath(),models[1].getPath(),vocabulary.getPath(),language.equals("auto")?"":language);JSONArray cues=new JSONArray();long frames=decoded.pcm.length()/4;int chunk=20*16000;long total=Math.round(frames*16000.0/decoded.sampleRate);
   SpeechResampler resampler=new SpeechResampler(decoded.sampleRate);
   try(RandomAccessFile in=new RandomAccessFile(decoded.pcm,"r");SpeechActivity activity=new SpeechActivity(c)){
    String spokenLanguage=language;
    if(language.equals("auto")){
     progress.update(10,"اكتشاف لغة الكلام من عدة مواضع واضحة");java.util.List<SpeechLanguage.Sample> candidates=new java.util.ArrayList<>();
     for(long at=0;at<total;at+=chunk){MusicSeparator.check(cancel);float[] audio=resampler.read(in,frames,at,(int)Math.min(chunk,total-at));SpeechLanguage.consider(candidates,audio,activity.regions(audio));}
     java.util.List<String> languages=new java.util.ArrayList<>();java.util.List<Integer> weights=new java.util.ArrayList<>();
     for(SpeechLanguage.Sample sample:candidates){MusicSeparator.check(cancel);String[] guess=recognize(engine,sample.audio);languages.add(guess.length>0?guess[0]:"");weights.add(sample.voiced);}
     spokenLanguage=SpeechLanguage.decide(languages,weights);
     if(spokenLanguage.isEmpty())throw new IOException("لم تتضح لغة الكلام؛ اختر لغة المقطع يدويًا وأعد المحاولة");
     close(engine);engine=0;engine=open(models[0].getPath(),models[1].getPath(),vocabulary.getPath(),spokenLanguage);
     progress.update(12,"لغة الكلام المكتشفة · "+spokenLanguage);
    }
    if(total>Integer.MAX_VALUE)throw new IOException("المقطع أطول من حد التفريغ المدعوم");
    activity.restart();java.util.List<Integer> intervals=new java.util.ArrayList<>();
    for(long at=0;at<total;at+=chunk){
     MusicSeparator.check(cancel);int count=(int)Math.min(chunk,total-at);
     int[] spans=activity.accept(resampler.read(in,frames,at,count),at+count>=total);
     for(int value:spans)intervals.add(value);
     progress.update(12+(int)(8*(at+count)/Math.max(1,total)),"تحديد الكلام مع الحفاظ على اتصال الجمل");
    }
    int[] spans=new int[intervals.size()];for(int i=0;i<spans.length;i++)spans[i]=intervals.get(i);
    for(int[] region:SpeechRegions.continuous(spans,(int)total)){
     MusicSeparator.check(cancel);float[] spoken=resampler.read(in,frames,region[0],region[1]-region[0]);
     float[] inference=spokenLanguage.equals("ar")?arabicPadding(spoken):spoken;
     String[] results=recognize(engine,inference);
     String detected=results.length>0?results[0]:"";if(detected.isEmpty())detected=spokenLanguage;
     double offset=region[0]/16000.0,limit=region[1]/16000.0;
     for(int i=1;i+2<results.length;i+=3){
      String text=results[i+2].trim();if(text.isEmpty())continue;
      double from=Double.parseDouble(results[i]),length=Double.parseDouble(results[i+1]);if(!Double.isFinite(from)||!Double.isFinite(length))continue;
      double begin=Math.max(offset,offset+from),end=Math.min(limit,offset+from+length);
      if(end>begin)cues.put(new JSONObject().put("start",begin).put("end",end).put("text",text).put("language",detected));
     }
     progress.update(20+(int)(70L*region[1]/Math.max(1,total)),"تفريغ الكلام · "+Math.round(100.0*region[1]/Math.max(1,total))+"%");
    }
   }
   return cues;
  }finally{if(decoded!=null)decoded.pcm.delete();if(engine!=0)close(engine);}
 }
 private static float[] arabicPadding(float[] samples){return java.util.Arrays.copyOf(samples,Math.min(27*16000,samples.length+Math.max(3*16000,samples.length/3)));}
 static String language(JSONArray cues,String fallback){String found="";for(int i=0;i<cues.length();i++){String value=cues.optJSONObject(i).optString("language",fallback);if(value.isEmpty())continue;if(!found.isEmpty()&&!found.equals(value))return "auto";found=value;}return found.isEmpty()?fallback:found;}
 static File srt(Context c,JSONArray cues)throws Exception{File out=FilesUtil.output(c,"srt");try(Writer w=new OutputStreamWriter(new FileOutputStream(out),java.nio.charset.StandardCharsets.UTF_8)){for(int i=0;i<cues.length();i++){JSONObject cue=cues.getJSONObject(i);double start=cue.getDouble("start"),end=cue.getDouble("end");if(!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<=start||cue.getString("text").length()>10000)throw new IOException("سطر ترجمة أو توقيت غير صالح");w.write((i+1)+"\n"+stamp(cue.getDouble("start"))+" --> "+stamp(cue.getDouble("end"))+"\n"+cue.getString("text").replace("\r","")+"\n\n");}}catch(Exception e){out.delete();throw e;}return out;}
 private static String stamp(double seconds){long ms=Math.round(seconds*1000);return String.format(Locale.US,"%02d:%02d:%02d,%03d",ms/3600000,ms/60000%60,ms/1000%60,ms%1000);}
}
