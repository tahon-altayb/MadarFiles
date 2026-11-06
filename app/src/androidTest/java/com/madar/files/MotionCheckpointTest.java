package com.madar.files;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MotionCheckpointTest {
 private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private JSONObject request()throws Exception{return new JSONObject().put("thread","checkpointTest").put("task","retry").put("prompt","إعلان عن صناعة الكتاب بدون موسيقى").put("voice","silma").put("attachments",new JSONArray()).put("previous","");}
 private JSONObject project(JSONObject request)throws Exception{JSONObject p=MotionProject.create(context);p.put("agentThread",request.getString("thread")).put("agentTask","failed").put("agentRequestKey",MotionCheckpoint.requestKey(request)).put("voiceMode","silma").put("direction",new JSONObject().put("layoutMode","validated_scene_graph"));MotionProject.save(context,p);return p;}
 private JSONObject envelope(JSONObject p)throws Exception{return new JSONObject().put("protocol",1).put("project",new JSONObject(p.toString())).put("assets",new JSONArray());}
 @Test public void silentFieldOverridesStaleMaleWordingBeforeCloud()throws Exception{
  JSONObject r=request().put("voice","none").put("prompt","إعلان موشن عمودي 30 ثانية بالصوت الرجالي المعتمد وبدون موسيقى");
  JSONObject p=AgentMotion.create(context,r);assertEquals("none",p.getString("voiceMode"));assertEquals(r.getString("prompt"),p.getJSONObject("creativeRequest").getString("text"));
  assertEquals("none",MotionProject.load(context,p.getString("id")).getString("voiceMode"));assertTrue(ColabDiagnostics.safe("MOTION_VOICE_NONE"));assertTrue(ColabDiagnostics.safe("SPEECH_BACKEND"));assertFalse(ColabDiagnostics.safe("MOTION_VOICE_NONE_SECRET"));
 }
 @Test public void directionSurvivesFailureAndOnlyExactRequestResumes()throws Exception{
  JSONObject r=request(),p=project(r);MotionCheckpoint.accept(context,envelope(p),p,new AtomicBoolean(),(i,n)->{throw new AssertionError("No assets expected");});
  JSONObject saved=MotionProject.load(context,p.getString("id"));assertEquals("directed",saved.getString("motionCheckpoint"));
  JSONObject resumed=AgentMotion.create(context,r.put("resumeProject",p.getString("id")));assertEquals(p.getString("id"),resumed.getString("id"));assertEquals("retry",resumed.getString("agentTask"));assertEquals(p.getJSONArray("scenes").toString(),resumed.getJSONArray("scenes").toString());
  try{AgentMotion.create(context,new JSONObject(r.toString()).put("prompt","موضوع جديد"));fail();}catch(IOException expected){}
  try{AgentMotion.create(context,new JSONObject(r.toString()).put("voice","none"));fail();}catch(IOException expected){}
 }
 @Test public void checkpointCannotReplaceAnotherThreadsProject()throws Exception{
  JSONObject p=project(request()),copy=envelope(p);copy.getJSONObject("project").put("agentThread","anotherThread");
  try{MotionCheckpoint.accept(context,copy,p,new AtomicBoolean(),(i,n)->new byte[0]);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("MOTION_CHECKPOINT_INVALID"));}
  assertFalse(MotionProject.load(context,p.getString("id")).has("motionCheckpoint"));
 }
 @Test public void originalInputIsNeverOverwrittenByCheckpointAsset()throws Exception{
  JSONObject p=project(request());String name="asset_"+"a".repeat(32)+".jpg";File original=MotionProject.asset(context,p.getString("id"),name);try(OutputStream out=new FileOutputStream(original)){out.write(new byte[]{1,2,3,4});}
  JSONObject cp=envelope(p);cp.getJSONArray("assets").put(new JSONObject().put("name",name).put("sha256","0".repeat(64)).put("size",4).put("chunks",1));
  try{MotionCheckpoint.accept(context,cp,p,new AtomicBoolean(),(i,n)->new byte[]{8,8,8,8});fail();}catch(IOException expected){}
  try(InputStream in=new FileInputStream(original)){assertArrayEquals(new byte[]{1,2,3,4},in.readAllBytes());}
  assertFalse(MotionProject.load(context,p.getString("id")).has("motionCheckpoint"));
 }
 @Test public void corruptDownloadedAssetCannotCommitAProject()throws Exception{
  JSONObject p=project(request());String name="asset_"+"b".repeat(32)+".jpg";JSONObject cp=envelope(p);cp.getJSONArray("assets").put(new JSONObject().put("name",name).put("sha256","0".repeat(64)).put("size",4).put("chunks",1));
  try{MotionCheckpoint.accept(context,cp,p,new AtomicBoolean(),(i,n)->new byte[]{8,8,8,8});fail();}catch(IOException expected){}
  assertFalse(MotionProject.asset(context,p.getString("id"),name).exists());assertFalse(MotionProject.load(context,p.getString("id")).has("motionCheckpoint"));
 }
 @Test public void completeOwnedSpeechFilesResumeWithoutReplacement()throws Exception{
  JSONObject r=request(),p=project(r),cp=envelope(p).put("phase","voiced");
  java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(364).order(java.nio.ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes()).putInt(356).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(24000).putInt(48000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(320);byte[] wave=b.array();
  File source=new File(context.getCacheDir(),"checkpoint-wave.wav");try(OutputStream out=new FileOutputStream(source)){out.write(wave);}String hash=MotionProject.hash(source);source.delete();
  for(int i=0;i<p.getJSONArray("scenes").length();i++)cp.getJSONArray("assets").put(new JSONObject().put("name","speech_"+i+".wav").put("sha256",hash).put("size",wave.length).put("chunks",1));
  MotionCheckpoint.accept(context,cp,p,new AtomicBoolean(),(i,n)->wave);JSONObject saved=MotionProject.load(context,p.getString("id"));assertEquals("voiced",saved.getString("motionCheckpoint"));
  assertEquals(p.getString("id"),AgentMotion.create(context,r.put("resumeProject",p.getString("id"))).getString("id"));
  for(int i=0;i<p.getJSONArray("scenes").length();i++)assertEquals(hash,MotionProject.hash(MotionProject.asset(context,p.getString("id"),"speech_"+i+".wav")));
  for(String name:new String[]{"speech_7.wav","speech_19.wav"})assertEquals(name,MotionProject.asset(context,p.getString("id"),name).getName());
  for(String name:new String[]{"speech_20.wav","speech_007.wav","speech_-1.wav","../speech_0.wav"})try{MotionProject.asset(context,p.getString("id"),name);fail(name);}catch(IOException expected){}
 }
 @Test public void twentySceneVoiceCheckpointRetainsEveryVerifiedFile()throws Exception{
  JSONObject r=request(),p=project(r);JSONArray scenes=new JSONArray();JSONObject template=p.getJSONArray("scenes").getJSONObject(0);
  for(int i=0;i<20;i++)scenes.put(new JSONObject(template.toString()).put("id",String.format(java.util.Locale.ROOT,"%012x",i+1)).put("seconds",2));p.put("scenes",scenes);MotionProject.save(context,p);
  JSONObject cp=envelope(p).put("phase","voiced");byte[] bytes=new byte[]{1,2,3,4};File source=new File(context.getCacheDir(),"checkpoint-twenty-fixture.wav");try(OutputStream out=new FileOutputStream(source)){out.write(bytes);}String hash=MotionProject.hash(source);source.delete();
  for(int i=0;i<20;i++)cp.getJSONArray("assets").put(new JSONObject().put("name","speech_"+i+".wav").put("sha256",hash).put("size",bytes.length).put("chunks",1));
  MotionCheckpoint.accept(context,cp,p,new AtomicBoolean(),(i,n)->bytes);JSONObject resumed=AgentMotion.create(context,r.put("resumeProject",p.getString("id")));assertEquals(20,resumed.getJSONArray("scenes").length());assertEquals("voiced",resumed.getString("motionCheckpoint"));for(int i=0;i<20;i++)assertEquals(hash,MotionProject.hash(MotionProject.asset(context,p.getString("id"),"speech_"+i+".wav")));
 }
 @Test public void incompleteVoiceCheckpointCannotSkipSpeech()throws Exception{
  JSONObject p=project(request()),cp=envelope(p).put("phase","voiced");
  try{MotionCheckpoint.accept(context,cp,p,new AtomicBoolean(),(i,n)->new byte[0]);fail();}catch(IOException expected){}
  assertFalse(MotionProject.load(context,p.getString("id")).has("motionCheckpoint"));
 }
 @Test public void speechRootCauseAndBackendAreKeptWithoutPrivateText()throws Exception{
  ColabDiagnostics diagnostic=new ColabDiagnostics(context,"MOTION_COMPOSE");diagnostic.record("SPEECH_SETUP_TORCH");diagnostic.record("MOTION_ENGINE_CUDA");diagnostic.failed(new IOException("واجهة عربية",new IOException("Colab · SPEECH_DEPENDENCY private https://private.invalid/?token=SECRET")));
  String report=ColabDiagnostics.last(context);assertTrue(report.contains("SPEECH_DEPENDENCY"));assertTrue(report.contains("SPEECH_SETUP_TORCH"));assertTrue(report.contains("MOTION_ENGINE_CUDA"));assertFalse(report.contains("UNCLASSIFIED"));assertFalse(report.contains("SECRET"));assertFalse(report.contains("https"));
 }
}
