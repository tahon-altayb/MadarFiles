package com.madar.files;
import java.util.*;
/** Vote on speech-rich contexts instead of changing language on each short shout. */
final class SpeechLanguage {
 static final class Sample{final float[] audio;final int voiced;Sample(float[] a,int v){audio=a;voiced=v;}}
 static void consider(List<Sample> samples,float[] audio,int[] regions){
  int voiced=0;for(int i=0;i+1<regions.length;i+=2)voiced+=Math.max(0,regions[i+1]-regions[i]);
  if(voiced<Math.min(32000,audio.length/2))return;
  samples.add(new Sample(audio,voiced));samples.sort((a,b)->Integer.compare(b.voiced,a.voiced));if(samples.size()>3)samples.remove(3);
 }
 static String decide(List<String> languages,List<Integer> weights){
  Map<String,Long> votes=new HashMap<>();long total=0,best=0;String choice="";
  for(int i=0;i<languages.size();i++){String language=languages.get(i);long weight=weights.get(i);total+=weight;if(language==null||!language.matches("ar|en|ja|fr|de|es|zh"))continue;long score=votes.getOrDefault(language,0L)+weight;votes.put(language,score);if(score>best){best=score;choice=language;}}
  return total>0&&best>=total*.6?choice:"";
 }
}
