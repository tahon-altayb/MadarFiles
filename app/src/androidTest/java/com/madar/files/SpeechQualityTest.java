package com.madar.files;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
/** Real human speech and exact public references; only the full native release suite runs this. */
@RunWith(AndroidJUnit4.class)
public class SpeechQualityTest {
 @Test public void highQualityModelTranscribesArabicJapaneseAndEnglishOnAndroid()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();JSONObject references;try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("speech-references.json")){references=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getJSONObject("references");}
  for(String language:new String[]{"ar","ja","en"}){File input=File.createTempFile("speech-reference-",".wav",c.getCacheDir());try{try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("speech-reference-"+language+".wav");OutputStream out=new FileOutputStream(input)){FilesUtil.copy(in,out);}JSONArray cues=SpeechProcessor.transcribe(c,input,"auto","maximum",new AtomicBoolean(),(p,m)->{});StringBuilder text=new StringBuilder();for(int i=0;i<cues.length();i++){JSONObject cue=cues.getJSONObject(i);assertTrue(cue.getDouble("end")>cue.getDouble("start"));assertEquals("Detected spoken language",language,cue.getString("language"));text.append(cue.getString("text")).append(' ');}List<String> expected=units(references.getString(language),language),actual=units(text.toString(),language);double error=distance(expected,actual)/(double)Math.max(1,expected.size());System.out.println("MADAR_ASR "+language+" error="+error+" text="+text);assertTrue("Reference mismatch "+language+" error="+error+" text="+text,error<=.35);}finally{input.delete();}}
 }
 private static List<String> units(String text,String language){String normalized=java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\p{M}","").replaceAll("[أإآ]","ا").replaceAll("[^\\p{L}\\p{N}\\s]"," ").trim();if(language.equals("ja")){List<String> chars=new ArrayList<>();for(char c:normalized.replaceAll("\\s","").toCharArray())chars.add(String.valueOf(c));return chars;}return normalized.isEmpty()?Collections.emptyList():Arrays.asList(normalized.split("\\s+"));}
 private static int distance(List<String> a,List<String> b){int[] row=new int[b.size()+1];for(int j=0;j<row.length;j++)row[j]=j;for(int i=1;i<=a.size();i++){int[] next=new int[row.length];next[0]=i;for(int j=1;j<=b.size();j++)next[j]=Math.min(row[j]+1,Math.min(next[j-1]+1,row[j-1]+(a.get(i-1).equals(b.get(j-1))?0:1)));row=next;}return row[b.size()];}
}
