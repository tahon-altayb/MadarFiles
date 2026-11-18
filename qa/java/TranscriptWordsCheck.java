package com.madar.files;
import org.json.*;
public final class TranscriptWordsCheck {
 public static void main(String[] args)throws Exception{
  JSONArray words=new JSONArray("[{\"start\":1,\"end\":1.5,\"text\":\"سلام\"}]");
  if(TranscriptWords.words(words,1,2,10).getJSONObject(0).getDouble("start")!=11)throw new AssertionError("Offset lost");
  for(String invalid:new String[]{"[{\"start\":0,\"end\":1.5,\"text\":\"x\"}]","[{\"start\":1,\"end\":3,\"text\":\"x\"}]","[{\"start\":1,\"end\":1.8,\"text\":\"x\"},{\"start\":1.5,\"end\":2,\"text\":\"y\"}]"}){boolean rejected=false;try{TranscriptWords.words(new JSONArray(invalid),1,2,0);}catch(Exception e){rejected=true;}if(!rejected)throw new AssertionError("Bad timing accepted");}
  JSONArray cues=new JSONArray().put(new JSONObject().put("start",1).put("end",2).put("text","سلام").put("words",words));if(TranscriptWords.validateCues(cues).getJSONObject(0).getJSONArray("words").length()!=1)throw new AssertionError("Words lost");
  System.out.println("PASS Java word bounds, overlap rejection, offset conversion and persistence validation");
 }
}
