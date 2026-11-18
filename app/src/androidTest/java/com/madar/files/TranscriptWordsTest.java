package com.madar.files;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class TranscriptWordsTest {
 @Test public void retainedWordTimingUsesAbsoluteSourceClock()throws Exception{
  JSONArray words=new JSONArray("[{\"start\":1,\"end\":1.5,\"text\":\"سلام\"}]");
  assertEquals(11,TranscriptWords.words(words,1,2,10).getJSONObject(0).getDouble("start"),.0001);
  JSONArray cues=new JSONArray().put(new JSONObject().put("start",1).put("end",2).put("text","سلام").put("words",words));
  assertEquals(1,TranscriptWords.validateCues(cues).getJSONObject(0).getJSONArray("words").length());
  for(String bad:new String[]{"[{\"start\":0,\"end\":1.5,\"text\":\"x\"}]","[{\"start\":1,\"end\":3,\"text\":\"x\"}]","[{\"start\":1,\"end\":1.8,\"text\":\"x\"},{\"start\":1.5,\"end\":2,\"text\":\"y\"}]"}){try{TranscriptWords.words(new JSONArray(bad),1,2,0);fail("Invalid word clock accepted");}catch(java.io.IOException expected){}}
 }
}
