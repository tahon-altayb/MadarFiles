package com.madar.files;

import android.content.Context;
import java.io.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Prepare ASR input with the same bundled vocal model; never alter the source media. */
final class SpeechAudio {
 static final String FALLBACK="تعذر فصل الموسيقى؛ استُخدم الصوت الأصلي للتفريغ. راجع وضوح الكلام.";
 interface Separator {File run(AudioPipeline.Decoded audio)throws Exception;}
 static AudioPipeline.Decoded decode(Context context,File input,long limitUs,boolean separate,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception{
  AudioPipeline.Decoded original=AudioPipeline.decode(context,input,limitUs,cancel,progress);
  if(!separate)return original;
  try{return prepare(original,cancel,progress,audio->MusicSeparator.separate(context,audio,cancel,
    (percent,message)->progress.update(8+percent*12/100,"توضيح الكلام قبل التفريغ · "+message)));}
  catch(Exception|Error error){original.pcm.delete();throw error;}
 }
 // Ownership transfers only after a complete, time-aligned output has been validated.
 static AudioPipeline.Decoded prepare(AudioPipeline.Decoded original,AtomicBoolean cancel,MusicSeparator.Progress progress,Separator separator)throws Exception{
  File clean=null;
  try{
   MusicSeparator.check(cancel);clean=separator.run(original);MusicSeparator.check(cancel);
   long expected=Math.round(original.pcm.length()/4*44100.0/original.sampleRate);
   if(clean==null||clean.equals(original.pcm)||!clean.isFile()||expected<=0||clean.length()!=expected*4)throw new IOException("صيغة صوت الفصل أو مدته غير مطابقة");
   AudioPipeline.Decoded result=new AudioPipeline.Decoded(clean,44100,original.offsetUs);
   original.pcm.delete();clean=null;return result;
  }catch(CancellationException stopped){throw stopped;}
  catch(Exception|OutOfMemoryError|UnsatisfiedLinkError failed){
   MusicSeparator.check(cancel);progress.update(9,FALLBACK);return original;
  }finally{if(clean!=null&&!clean.equals(original.pcm))clean.delete();}
 }
}
