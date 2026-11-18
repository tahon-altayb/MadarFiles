package com.madar.files;

import org.json.*;
import java.text.Normalizer;
import java.util.*;

/**
 * «عدّل العبارة X إلى Y» on a finished advert: finds the on-screen text the owner quoted (spelling,
 * hamza and a leading «و» do not matter) and replaces only that text, so the rest of the film
 * stays exactly as approved and only the render runs again. Pure logic; no Android types.
 */
final class MotionTextEdit {
 static final class Match {int scene=-1,element=-1;double score;String before="",after="";boolean heading;}

 static String key(String value){
  String s=Normalizer.normalize(String.valueOf(value),Normalizer.Form.NFKC).replaceAll("[\\u064b-\\u065f\\u0670\\u0640]","").replaceAll("[أإآٱ]","ا").replace('ى','ي').replace('ة','ه').toLowerCase(Locale.ROOT);
  return s.replaceAll("[^\\p{L}\\p{N}]+"," ").trim();
 }
 static List<String> words(String value){List<String> out=new ArrayList<>();for(String w:key(value).split(" ")){if(w.isEmpty())continue;if(w.length()>3&&w.startsWith("و"))w=w.substring(1);out.add(w);}return out;}

 /** How well a shown text matches what the owner quoted: 1 for the same words, high when one contains the other. */
 static double score(String quoted,String shown){
  List<String> a=words(quoted),b=words(shown);if(a.isEmpty()||b.isEmpty())return 0;
  Set<String> left=new HashSet<>(a),right=new HashSet<>(b);int common=0;for(String w:left)if(right.contains(w))common++;
  double dice=2.0*common/(left.size()+right.size()),covered=(double)common/left.size();
  return covered>=.999&&left.size()>=2?Math.max(dice,.85):dice;
 }

 static Match locate(JSONArray scenes,String from)throws JSONException{
  Match best=new Match();
  for(int i=0;i<scenes.length();i++){JSONObject scene=scenes.getJSONObject(i);JSONObject composition=scene.optJSONObject("composition");if(composition==null)continue;JSONArray elements=composition.optJSONArray("elements");if(elements==null)continue;
   for(int n=0;n<elements.length();n++){JSONObject e=elements.getJSONObject(n);if(!Arrays.asList("text","metric").contains(e.optString("kind")))continue;double s=score(from,e.optString("text"));if(s>best.score){best.score=s;best.scene=i;best.element=n;best.before=e.optString("text");best.heading=e.optString("text").equals(scene.optString("title"))||"heading".equals(e.optString("textRole"));}}
  }
  return best.score>=.6?best:null;
 }

 /** The new text: the quoted part replaced (or removed when «to» is empty), else the whole line. */
 static String rewrite(String shown,String from,String to){
  String result;
  if(shown.contains(from))result=shown.replace(from,to);
  else if(to.isEmpty()){
   // «شيل اللحوم المشوية»: drop those words (and a joining «و») wherever they sit in the line.
   List<String> gone=words(from);StringBuilder out=new StringBuilder();for(String w:shown.split("\\s+")){String k=key(w);if(k.length()>3&&k.startsWith("و"))k=k.substring(1);if(gone.contains(k))continue;if(out.length()>0)out.append(' ');out.append(w);}result=out.toString();
  }else result=to;
  result=result.replaceAll("\\s+"," ").replaceAll("\\s+([،,.!؟])","$1").replaceAll("^(?:[\\s،,]*و\\s+)+|(?:\\s+و[\\s،,]*)+$","").replaceAll("^[\\s،,]+|[\\s،,]+$","").trim();
  return result;
 }

 /** Applies the edit to a copy of the scenes; null when the text is not found or would break the scene. */
 static Match apply(JSONArray scenes,String from,String to,boolean spoken)throws JSONException{
  from=from.trim();to=to.trim();if(from.length()<2||from.length()>200||to.length()>100)return null;
  Match m=locate(scenes,from);if(m==null)return null;
  JSONObject scene=scenes.getJSONObject(m.scene);JSONObject element=scene.getJSONObject("composition").getJSONArray("elements").getJSONObject(m.element);
  String after=rewrite(m.before,from,to);if(after.isEmpty()||after.length()>100||after.equals(m.before))return null;
  // A spoken advert says the narration aloud: changing it needs a new voice, so that stays a full revision.
  if(spoken&&score(from,scene.optString("narration"))>=.6)return null;
  element.put("text",after);m.after=after;
  if(m.heading)scene.put("title",after.length()>80?after.substring(0,80):after);
  if(!spoken){String narration=scene.optString("narration");if(narration.equals(m.before))scene.put("narration",after);else if(narration.contains(m.before))scene.put("narration",narration.replace(m.before,after));}
  return m;
 }
 private MotionTextEdit(){}
}
