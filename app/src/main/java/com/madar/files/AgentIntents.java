package com.madar.files;

import org.json.*;
import java.util.*;
import java.util.regex.*;

/** Fast, conservative routing for unambiguous requests; no model or network needed. */
final class AgentIntents {
 static String normalize(String s){StringBuilder out=new StringBuilder();for(char c:java.text.Normalizer.normalize(s,java.text.Normalizer.Form.NFKC).toCharArray()){int digit=Character.digit(c,10);out.append(digit>=0?(char)('0'+digit):c);}return out.toString().toLowerCase(Locale.ROOT).replaceAll("[\\u064b-\\u065f\\u0670]","").replaceAll("[أإآ]","ا").replace('ة','ه').replace('ى','ي').trim();}
 static JSONObject plan(String tool,String... pairs)throws Exception{JSONArray fields=new JSONArray();for(int i=0;i<pairs.length;i+=2)fields.put(new JSONObject().put("name",pairs[i]).put("value",pairs[i+1]));return new JSONObject().put("tool",tool).put("fields",fields).put("message","");}
 static boolean has(String s,String pattern){return Pattern.compile(pattern).matcher(s).find();}
 static JSONObject resolve(String user,JSONObject source,JSONArray previous)throws Exception{
  String t=normalize(user),kind=source.optString("kind");
  // An answer about the whole file inherits the last concrete request, not model errors.
  if(t.matches("(?:كامل المقطع|المقطع كامل|كله|كامله|كامل الفيديو|الفيديو كامل|كامل الملف|من البدايه للنهايه|من البدايه الي النهايه)[.!؟ ]*")&&previous!=null){for(int i=previous.length()-1;i>=0;i--){JSONObject turn=previous.getJSONObject(i);if(turn.optString("role").equals("user")&&!normalize(turn.optString("text")).equals(t)){JSONObject p=resolve(turn.optString("text"),source,null);if(p!=null)return p;}}}
  if(t.matches("(?:السلام عليكم(?: ورحمه الله(?: وبركاته)?)?|سلام|مرحبا|هلا)[.!؟ ]*"))return plan("chat").put("message","وعليكم السلام، أرفق ملفك وقل لي وش تبي أعدّل عليه.");
  if(has(t,"^(كيف|ليش|لماذا|هل|اشرح|وش|ما هو|ماهي|ما هي|ماذا)|(?:^|\\s)(لا|مو|ليس|not|don't)(?:\\s|$)|ما\\s*(ابي|ابغي|ابغى|اريد)|^(لات|لا ت)"))return null;
  // Leave multi-step edits to the structured planner, instead of silently dropping steps.
  if(has(t,"وبعد|بعدين|بعدها|\\bthen\\b|ثم|(?:و|\\s)(حسن|حسّن|قص|اضغط|حول|اكتب|حط|اضف|ضف|ترجم)"))return null;
  if(has(t,"qr|كيو\\s*ار")){if(Arrays.asList("image","video","pdf").contains(kind)&&!has(t,"https?://"))return plan("qr","qrType",kind);Matcher link=Pattern.compile("https?://[^\\s]+",Pattern.CASE_INSENSITIVE).matcher(user);if(link.find())return plan("qr","qrType","link","qrValue",link.group());}
  if(has(t,"https?://")&&has(t,"حمل|تحميل|نزل|تنزيل|download")){return plan("downloadLink","linkUrl",LinkDownloads.url(user),"linkMode",has(t,"صوت فقط|الصوت فقط|mp3")?"audio":"video");}
  if(kind.equals("video")||kind.equals("audio")){
   if(has(t,"موسيقي|موسيقى|الموسيقي|الموسيقى|music|الاغاني|الالحان")&&has(t,"ازل|ازاله|احذف|حذف|شيل|شيلها|بدون|فصل|remove|separate"))return plan("music","musicMode",source.optLong("duration")>60000?"colab":"local","musicOutput",kind.equals("video")?"video":"audio");
   if(has(t,"كتم|اكتم|صامت|بدون صوت|mute")&&!has(t,"[0-9]"))return kind.equals("video")?plan("mute"):plan("sound","volume","0");
   if(kind.equals("video")&&has(t,"استخرج|استخراج|طلع|حول")&&has(t,"الصوت|صوت فقط|audio"))return plan("audio");
   if(has(t,"قص|احتفظ|trim")&&!has(t,"احذف|حذف")){Matcher m=Pattern.compile("(?:من\\s*)?(\\d+(?:\\.\\d+)?)\\s*(?:ثانيه|ثواني|ثوان|s)?\\s*(?:الي|الى|حتي|لين|to|[-–])\\s*(\\d+(?:\\.\\d+)?)").matcher(t);if(m.find())return plan("trim","start",m.group(1),"end",m.group(2));}
  }
  if(kind.equals("image")){
   if(has(t,"عزل|اعزل|ازل الخلفيه|ازاله الخلفيه|شيل الخلفيه|احذف الخلفيه|remove background"))return plan("removebg","cutModel","general","cutBackground","transparent");
   if(has(t,"استخرج|استخراج|اسحب|سحب|طلع|ocr")&&has(t,"النص|نص|الكلام|الكتابه|text|ocr"))return plan("ocr");
  }
  if((kind.equals("image")||kind.equals("pdf"))&&has(t,"ترجم|ترجمه|translate"))return plan("translateDocument");
  if(Arrays.asList("image","video","pdf").contains(kind)&&has(t,"اضغط|ضغط|صغر حجم|compress")&&!has(t,"[0-9]"))return plan(kind.equals("pdf")?"compressPdf":"compress");
  if(has(t,"حول|تحويل|convert|خليها|خله")){Matcher f=Pattern.compile("(?<![a-z])(jpg|jpeg|png|webp|mp4|mp3|wav|m4a|pdf|word|docx)(?![a-z])").matcher(t);if(f.find()){String format=f.group(1);if(kind.equals("pdf")&&(format.equals("word")||format.equals("docx")))return plan("layoutWord");if(!format.equals("word")&&!format.equals("docx"))return plan("convert","format",format.equals("jpeg")?"jpg":format);}}
  return AgentTools.explicitDocument(user,kind);
 }
}
