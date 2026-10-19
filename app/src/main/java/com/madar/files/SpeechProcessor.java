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
  if(!language.matches("ar|en|ja|fr|de|es|zh"))throw new IOException("لغة غير مدعومة");AudioPipeline.Decoded decoded=null;long engine=0;
  try{decoded=AudioPipeline.decode(c,input,0,cancel,progress);progress.update(10,"تحميل نموذج التفريغ المحلي");engine=open(OfflineAssets.file(c,"tiny-encoder.int8.onnx").getPath(),OfflineAssets.file(c,"tiny-decoder.int8.onnx").getPath(),OfflineAssets.file(c,"tiny-tokens.txt").getPath(),language);JSONArray cues=new JSONArray();long frames=decoded.pcm.length()/4;int chunk=20*16000;long total=Math.round(frames*16000.0/decoded.sampleRate);
   try(RandomAccessFile in=new RandomAccessFile(decoded.pcm,"r")){for(long start=0;start<total;start+=chunk){MusicSeparator.check(cancel);int count=(int)Math.min(chunk,total-start);long first=(long)(start*decoded.sampleRate/16000.0),last=Math.min(frames-1,(long)Math.ceil((start+count)*decoded.sampleRate/16000.0));byte[] bytes=new byte[(int)(last-first+1)*4];in.seek(first*4);in.readFully(bytes);float[] samples=new float[count];double energy=0;for(int i=0;i<count;i++){int p=(int)Math.min(bytes.length/4-1,Math.floor((start+i)*decoded.sampleRate/16000.0-first));short l=(short)((bytes[p*4]&255)|(bytes[p*4+1]<<8)),r=(short)((bytes[p*4+2]&255)|(bytes[p*4+3]<<8));samples[i]=(l+r)/65536f;energy+=samples[i]*samples[i];}
    if(energy/Math.max(1,count)>0.000001){String[] results=recognize(engine,samples);for(int i=0;i+2<results.length;i+=3){String text=results[i+2].trim();if(text.isEmpty())continue;double from=Math.max(0,Double.parseDouble(results[i])),length=Double.parseDouble(results[i+1]);double begin=start/16000.0+from,end=Math.min(total/16000.0,start/16000.0+Math.min(count/16000.0,from+length));if(end>begin)cues.put(new JSONObject().put("start",begin).put("end",end).put("text",text));}}
    progress.update(10+(int)(80*Math.min(total,start+chunk)/Math.max(1,total)),"تفريغ الكلام · "+Math.round(100.0*Math.min(total,start+chunk)/Math.max(1,total))+"%");}}
   return cues;
  }finally{if(decoded!=null)decoded.pcm.delete();if(engine!=0)close(engine);}
 }
 static File srt(Context c,JSONArray cues)throws Exception{File out=FilesUtil.output(c,"srt");try(Writer w=new OutputStreamWriter(new FileOutputStream(out),java.nio.charset.StandardCharsets.UTF_8)){for(int i=0;i<cues.length();i++){JSONObject cue=cues.getJSONObject(i);w.write((i+1)+"\n"+stamp(cue.getDouble("start"))+" --> "+stamp(cue.getDouble("end"))+"\n"+cue.getString("text").replace("\r","")+"\n\n");}}catch(Exception e){out.delete();throw e;}return out;}
 private static String stamp(double seconds){long ms=Math.round(seconds*1000);return String.format(Locale.US,"%02d:%02d:%02d,%03d",ms/3600000,ms/60000%60,ms/1000%60,ms%1000);}
}
