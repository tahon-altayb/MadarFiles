package com.madar.files;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** User-supplied exact names only. Ambiguous words are never globally rewritten. */
final class SubtitleNames {
 final Map<String,String> entries=new LinkedHashMap<>();
 SubtitleNames(String input)throws IOException {
  if(input.length()>2000)throw new IOException("قاموس الأسماء أطول من الحد");
  for(String line:input.split("\\R")){if(line.trim().isEmpty())continue;String[] pair=line.split("=",-1);if(pair.length!=2||pair[0].trim().isEmpty()||pair[1].trim().isEmpty()||pair[0].length()>80||pair[1].length()>80)throw new IOException("اكتب كل اسم بهذا الشكل: الاسم الأصلي = الاسم المترجم");String from=pair[0].trim();if(entries.put(from,pair[1].trim())!=null)throw new IOException("اسم أصلي مكرر في القاموس");if(entries.size()>30)throw new IOException("الحد 30 اسمًا في القاموس");}
 }
 final class Protected {
  final String text;final Map<String,String> tokens;final Map<String,Integer> counts;
  Protected(String text,Map<String,String> tokens,Map<String,Integer> counts){this.text=text;this.tokens=tokens;this.counts=counts;}
  String restore(String translation)throws IOException {
   for(String token:tokens.keySet()){if(count(translation,token)!=counts.get(token))throw new IOException("لم يحافظ المحرك المحلي على الأسماء؛ احتُفظ بالأصل للمراجعة");translation=translation.replace(token,tokens.get(token));}
   if(translation.contains("ZXMADAR"))throw new IOException("رمز اسم غير صالح في الترجمة");return translation;
  }
 }
 Protected protect(String text)throws IOException {
  if(text.contains("ZXMADAR"))throw new IOException("رمز محجوز في نص الترجمة");
  List<String> sources=new ArrayList<>(entries.keySet());sources.sort((a,b)->Integer.compare(b.length(),a.length()));
  Map<String,String> tokens=new LinkedHashMap<>();Map<String,Integer> counts=new LinkedHashMap<>();
  if(sources.isEmpty())return new Protected(text,tokens,counts);
  String pattern=sources.stream().map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"));Matcher matcher=Pattern.compile(pattern).matcher(text);StringBuffer masked=new StringBuffer();
  while(matcher.find()){
   String name=matcher.group();int first=name.codePointAt(0),last=name.codePointBefore(name.length());
   // Do not replace Shin inside an English word, or シン inside シンガー.
   if((matcher.start()>0&&sameWord(text.codePointBefore(matcher.start()),first))||(matcher.end()<text.length()&&sameWord(text.codePointAt(matcher.end()),last)))continue;
   String token="ZXMADAR"+sources.indexOf(name)+"QXZ";tokens.put(token,entries.get(name));counts.put(token,counts.getOrDefault(token,0)+1);matcher.appendReplacement(masked,Matcher.quoteReplacement(token));
  }
  matcher.appendTail(masked);return new Protected(masked.toString(),tokens,counts);
 }
 private static boolean sameWord(int adjacent,int edge){Character.UnicodeScript a=Character.UnicodeScript.of(adjacent),b=Character.UnicodeScript.of(edge);return Character.isLetterOrDigit(adjacent)&&a==b&&(a==Character.UnicodeScript.LATIN||a==Character.UnicodeScript.KATAKANA||a==Character.UnicodeScript.ARABIC);}
 private static int count(String text,String term){int count=0;for(int at=0;(at=text.indexOf(term,at))>=0;at+=term.length())count++;return count;}
}
