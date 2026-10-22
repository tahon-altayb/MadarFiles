package com.madar.files;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.nio.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OnlineSpeechTest {
 private JSONObject segment(double start,double end,String source,String text)throws Exception{return new JSONObject().put("start",start).put("end",end).put("source",source).put("text",text).put("language","ja");}
 private JSONObject response(JSONObject... segments)throws Exception{JSONArray array=new JSONArray();for(JSONObject s:segments)array.put(s);return new JSONObject().put("segments",array);}
 @Test public void namesAndLiteralDeathRemainDifferentAndAudioTimesAreOffset()throws Exception {
  OnlineSpeech.Result result=OnlineSpeech.parse(response(segment(2,3,"信！","شين!"),segment(4,6,"死ぬな！","لا تمت!")),10,42,45,90,.25,"ar");
  assertEquals(1,result.original.length());assertEquals("死ぬな！",result.original.getJSONObject(0).getString("text"));assertEquals("لا تمت!",result.translated.getJSONObject(0).getString("text"));assertEquals(46.25,result.original.getJSONObject(0).getDouble("start"),0);
 }
 @Test public void boundaryPhraseHasOneOwnerAndKeepsWholeWord()throws Exception {
  JSONObject left=response(segment(43,47,"信！","شين!"));JSONObject right=response(segment(1,5,"信！","شين!"));
  assertEquals(0,OnlineSpeech.parse(left,48,0,0,45,0,"ar").original.length());
  OnlineSpeech.Result result=OnlineSpeech.parse(right,51,42,45,90,0,"ar");assertEquals(1,result.original.length());assertEquals(43,result.original.getJSONObject(0).getDouble("start"),0);assertEquals(47,result.original.getJSONObject(0).getDouble("end"),0);
 }
 @Test public void rejectsInvalidIncompleteAndOutOfOrderAudio()throws Exception {
  JSONObject[] invalid={response(segment(-1,2,"信","شين")),response(segment(1,12,"信","شين")),response(segment(3,2,"信","شين")),response(segment(1,2,"信","")),response(segment(3,4,"a","a"),segment(1,2,"b","b")),response(segment(1,2,"a","a").put("start","1"))};
  for(JSONObject item:invalid){try{OnlineSpeech.parse(item,10,0,0,10,0,"ar");fail(item.toString());}catch(java.io.IOException expected){}}
  try{OnlineTranslation.result(new JSONObject("{candidates:[{finishReason:MAX_TOKENS}]}"));fail();}catch(java.io.IOException expected){}
 }
 @Test public void wavAndRequestContainActualAudioAndBoundedContext()throws Exception {
  byte[] wav=OnlineSpeech.wav(new float[]{-1,0,1,Float.NaN});ByteBuffer b=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);assertEquals(52,wav.length);assertEquals(16000,b.getInt(24));assertEquals(8,b.getInt(40));assertEquals(-32767,b.getShort(44));assertEquals(32767,b.getShort(48));assertEquals(0,b.getShort(50));
  JSONObject request=OnlineSpeech.request(wav,.001,"ja","ar","",new JSONArray(),new JSONArray());JSONObject audio=request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getJSONObject("inlineData");assertEquals("audio/wav",audio.getString("mimeType"));assertArrayEquals(wav,android.util.Base64.decode(audio.getString("data"),android.util.Base64.NO_WRAP));
 }
 @Test public void properNameSpellingsCarryAcrossWindowsWithoutOverwriting()throws Exception {
  JSONArray names=OnlineSpeech.mergeNames(new JSONArray("[{source:'信',target:'شين'}]"),new JSONArray("[{source:'信',target:'سين'},{source:'王騎',target:'أوكي'}]"));assertEquals(2,names.length());assertEquals("شين",names.getJSONObject(0).getString("target"));
  OnlineSpeech.Result result=OnlineSpeech.parse(response(segment(1,2,"信！","شين!")),10,0,0,10,0,"original");assertEquals("信！",result.translated.getJSONObject(0).getString("text"));
 }
 @Test public void localContinuationKeepsNegationTogetherAndPreservesSource()throws Exception {
  JSONArray cues=new JSONArray("[{start:1,end:3,text:'次は',language:'ja'},{start:3,end:5,text:'立てない。',language:'ja'},{start:5,end:6,text:'Run!',language:'en'}]");JSONArray grouped=LocalTranslation.group(cues,"auto");assertEquals(2,grouped.length());assertEquals("次は立てない。",grouped.getJSONObject(0).getString("text"));assertEquals(5,grouped.getJSONObject(0).getDouble("end"),0);assertEquals(3,cues.length());assertEquals("次は",cues.getJSONObject(0).getString("text"));
 }
 @Test public void longCaptionsAreFullyRenderedWithoutEllipsis(){
  android.text.TextPaint paint=new android.text.TextPaint();paint.setTextSize(36);String text="هذا نص طويل يجب إظهاره كاملًا دون حذف بقية الكلمات. ".repeat(12);
  android.text.StaticLayout layout=CaptionOverlay.fullLayout(text,paint,400,320);assertEquals(text.length(),layout.getLineEnd(layout.getLineCount()-1));assertTrue(layout.getHeight()<=320);for(int i=0;i<layout.getLineCount();i++)assertEquals(0,layout.getEllipsisCount(i));
 }

}
