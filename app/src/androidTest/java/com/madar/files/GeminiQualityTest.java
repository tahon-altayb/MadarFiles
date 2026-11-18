package com.madar.files;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GeminiQualityTest {
 private JSONObject data(JSONObject request)throws Exception{return new JSONObject(request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"));}
 @Test public void confirmedNamesAndGuessesAreSeparateAndContextIsNotAnInstruction()throws Exception {
  JSONObject request=OnlineSpeech.request(OnlineSpeech.wav(new float[]{0}),.001,"ja","ar","信 = شين",new JSONArray("[{source:'信',target:'شين'}]"),new JSONArray(),"Kingdom",new JSONArray("[{source:'宝剣',target:'سيف'}]"));
  JSONObject body=data(request);assertEquals("Kingdom",body.getString("workContext"));assertEquals("信",body.getJSONArray("userConfirmedNames").getJSONObject(0).getString("source"));assertEquals("宝剣",body.getJSONArray("proposedNames").getJSONObject(0).getString("source"));assertEquals(1,body.getJSONArray("userConfirmedNames").length());
  String instruction=request.getJSONObject("systemInstruction").toString();assertTrue(instruction.contains("unverified"));assertTrue(instruction.contains("inner monologue"));
 }
 @Test public void secondListeningKeepsAudioSchemaAndDoesNotMutateFirstRequest()throws Exception {
  byte[] audio=OnlineSpeech.wav(new float[]{.1f,.2f});JSONObject first=OnlineSpeech.request(audio,.001,"ja","ar","",new JSONArray(),new JSONArray());String before=first.toString();JSONObject draft=new JSONObject("{segments:[],names:[]}");
  JSONObject review=OnlineSpeech.reviewRequest(first,draft);assertEquals(before,first.toString());JSONArray parts=review.getJSONArray("contents").getJSONObject(0).getJSONArray("parts");assertEquals(3,parts.length());assertEquals(first.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).toString(),parts.getJSONObject(1).toString());assertEquals(draft.toString(),new JSONObject(parts.getJSONObject(2).getString("text")).getJSONObject("candidateDraft").toString());assertTrue(review.getJSONObject("systemInstruction").toString().contains("fallible drafts"));
 }
 @Test public void optionalMalformedNameProposalsCannotDiscardValidSpeech()throws Exception {
  JSONArray proposed=OnlineSpeech.mergeNames(new JSONArray(),new JSONArray("[null,4,{source:'x',target:3},{source:'x=y',target:'bad'},{source:'信',target:'شين'},{source:'信',target:'سين'}]"));assertEquals(1,proposed.length());assertEquals("شين",proposed.getJSONObject(0).getString("target"));
  JSONArray many=new JSONArray();for(int i=0;i<100;i++)many.put(new JSONObject().put("source","n"+i).put("target","t"+i));assertEquals(30,OnlineSpeech.mergeNames(new JSONArray(),many).length());
 }
 @Test public void speakerMetadataSurvivesWithoutChangingTimingOrInventingIdentity()throws Exception {
  JSONObject response=new JSONObject("{segments:[{start:1,end:2,source:'余力はない',text:'لم تتبق لدي قوة',language:ja,speaker:'speaker2'}]}");OnlineSpeech.Result parsed=OnlineSpeech.parse(response,10,0,0,10,0,"ar");assertEquals("audio-0.0-speaker2",parsed.original.getJSONObject(0).getString("speaker"));assertEquals("audio-0.0-speaker2",parsed.translated.getJSONObject(0).getString("speaker"));assertEquals(1,parsed.original.getJSONObject(0).getDouble("start"),0);
 }
 @Test public void textReviewCarriesExactNamesContextAndSpeaker()throws Exception {
  JSONArray dialogue=new JSONArray("[{id:7,text:'信',language:ja,speaker:'speaker2',translate:true}]");JSONObject request=OnlineTranslation.request(dialogue,"ar","信 = شين","Kingdom");JSONObject body=data(request);assertEquals("信 = شين",body.getString("nameGlossary"));assertEquals("Kingdom",body.getString("workContext"));assertEquals("speaker2",body.getJSONArray("dialogue").getJSONObject(0).getString("speaker"));assertNotNull(GeminiClient.schema(request.getJSONObject("generationConfig").getJSONObject("responseSchema")));
 }
 @Test public void firebaseAdapterKeepsMultimodalContentAndSchema()throws Exception {
  JSONObject request=OnlineSpeech.request(OnlineSpeech.wav(new float[]{0}),.001,"ja","ar","",new JSONArray(),new JSONArray());assertNotNull(GeminiClient.content(request.getJSONArray("contents").getJSONObject(0)));assertNotNull(GeminiClient.schema(request.getJSONObject("generationConfig").getJSONObject("responseSchema")));
  try{GeminiClient.schema(new JSONObject("{type:UNSUPPORTED}"));fail();}catch(java.io.IOException expected){}
 }
 @Test public void cancellationFailsBeforeCredentialsOrFirebaseNetwork()throws Exception {
  try{GeminiClient.generate(InstrumentationRegistry.getInstrumentation().getTargetContext(),new JSONObject(),new AtomicBoolean(true));fail();}catch(java.util.concurrent.CancellationException expected){}
 }
}
