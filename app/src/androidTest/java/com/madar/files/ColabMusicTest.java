package com.madar.files;

import android.content.Context;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ColabMusicTest {
 private Context context;private MockWebServer server;private ColabRuntime runtime;private File output;
 private final AtomicBoolean cancel=new AtomicBoolean();private final String folder="madar_fixture";
 @Before public void setup()throws Exception{
  context=InstrumentationRegistry.getInstrumentation().getTargetContext();server=new MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0);
  runtime=new ColabRuntime(context,new ColabCredentials.Grant("OAUTH_MUST_STAY_LOCAL","madarfiles"),cancel);
  // Local fixture only: production base is populated solely by authenticated runtimes.get.
  set("base",server.url("/qa/base/").newBuilder().host("127.0.0.1").build());set("refreshAt",Long.MAX_VALUE);set("proxyToken","PROXY_FIXTURE");
  output=File.createTempFile("music_download_test_",".pcm",context.getCacheDir());
 }
 private void set(String name,Object value)throws Exception{Field field=ColabRuntime.class.getDeclaredField(name);field.setAccessible(true);field.set(runtime,value);}
 @After public void close()throws Exception{runtime.close();server.close();output.delete();}
 private byte[] pcm(int frames){byte[] bytes=new byte[frames*4];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*31+7);return bytes;}
 private JSONObject manifest(byte[]... parts)throws Exception{
  JSONArray chunks=new JSONArray();long frames=0;for(byte[] part:parts){frames+=part.length/4;chunks.put(new JSONObject().put("bytes",part.length).put("sha256",ColabMusic.sha(part)));}
  return new JSONObject().put("frames",frames).put("sample_rate",44100).put("channels",2).put("model_sha256",ColabMusic.MODEL_SHA).put("chunks",chunks);
 }
 private void json(JSONObject body){server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(body.toString()));}
 private void text(JSONObject body)throws Exception{json(new JSONObject().put("format","text").put("content",body.toString()));}
 private JSONObject binary(byte[] bytes)throws Exception{return new JSONObject().put("format","base64").put("content",Base64.encodeToString(bytes,Base64.DEFAULT));}
 private void fetch(long frames,int count)throws Exception{ColabMusic.fetchResult(runtime,folder,frames,count,output,cancel,(p,m)->{});}
 @Test(timeout=20000) public void completedRestJobDownloadsFullAndShortChunksThroughProductionPath()throws Exception{
  byte[] first=pcm(ColabMusic.CORE),last=pcm(17);text(manifest(first,last));json(binary(first));json(binary(last));
  fetch(ColabMusic.CORE+17,2);
  ByteArrayOutputStream expected=new ByteArrayOutputStream();expected.write(first);expected.write(last);assertArrayEquals(expected.toByteArray(),Files.readAllBytes(output.toPath()));
  String[] paths={"/qa/base/api/contents/"+folder+"/result.json?format=text","/qa/base/api/contents/"+folder+"/output_0.pcm?content=1&format=base64","/qa/base/api/contents/"+folder+"/output_1.pcm?content=1&format=base64"};
  for(String path:paths){RecordedRequest request=server.takeRequest(2,TimeUnit.SECONDS);assertNotNull(request);assertEquals(path,request.getPath());assertEquals("GET",request.getMethod());assertEquals("PROXY_FIXTURE",request.getHeader("X-Colab-Runtime-Proxy-Token"));assertNull(request.getHeader("Authorization"));}
  assertEquals(3,server.getRequestCount());
 }
 @Test(timeout=20000) public void compressedPacksDownloadThroughProductionAndPreserveEveryPcmByte()throws Exception{
  byte[] first=pcm(ColabMusic.CORE),last=pcm(17);ByteArrayOutputStream original=new ByteArrayOutputStream();original.write(first);original.write(last);MusicWire.Packed packed=MusicWire.encode(original.toByteArray());assertTrue(packed.compressed);
  text(manifest(first,last).put("packs",new JSONArray().put(packed.metadata)));json(binary(packed.data));fetch(ColabMusic.CORE+17,2);assertArrayEquals(original.toByteArray(),Files.readAllBytes(output.toPath()));
  assertEquals(2,server.getRequestCount());server.takeRequest(2,TimeUnit.SECONDS);assertEquals("/qa/base/api/contents/"+folder+"/pack_0.pcm.zlib?content=1&format=base64",server.takeRequest(2,TimeUnit.SECONDS).getPath());
 }
 @Test(timeout=20000) public void corruptedCompressedAudioNeverPublishesAPartialResult()throws Exception{
  byte[] raw=pcm(ColabMusic.CORE);MusicWire.Packed packed=MusicWire.encode(raw);assertTrue(packed.compressed);byte[] bad=packed.data.clone();bad[bad.length/2]^=1;
  text(manifest(raw).put("packs",new JSONArray().put(packed.metadata)));json(binary(bad));try{fetch(ColabMusic.CORE,1);fail();}catch(IOException error){assertTrue(error.getMessage().contains("MUSIC_WIRE_HASH"));}assertFalse(output.exists());
 }
 @Test(timeout=20000) public void transientDownloadRetriesOnlyReadAndKeepsValidatedBytes()throws Exception{
  byte[] data=pcm(31);text(manifest(data));server.enqueue(new MockResponse().setResponseCode(503));json(binary(data));fetch(31,1);assertArrayEquals(data,Files.readAllBytes(output.toPath()));assertEquals(3,server.getRequestCount());
 }
 @Test(timeout=20000) public void corruptionOrTruncationDeletesPartialDownloadAndReportsExactCode()throws Exception{
  for(boolean truncated:new boolean[]{false,true}){
   byte[] first=pcm(ColabMusic.CORE),last=pcm(17),bad=last.clone();if(truncated)bad=new byte[4];else bad[0]^=1;
   text(manifest(first,last));json(binary(first));json(binary(bad));
   try{fetch(ColabMusic.CORE+17,2);fail();}catch(IOException e){assertTrue(e.getMessage().contains(truncated?"MUSIC_OUTPUT_LENGTH":"MUSIC_OUTPUT_HASH"));}
   assertFalse(output.exists());
  }
 }
 @Test(timeout=20000) public void invalidManifestOrFormatNeverBecomesUsableAudio()throws Exception{
  byte[] data=pcm(17);text(manifest(data).put("model_sha256","wrong"));
  try{fetch(17,1);fail();}catch(IOException e){assertTrue(e.getMessage().contains("MUSIC_RESULT_SCHEMA"));}assertEquals(1,server.getRequestCount());assertFalse(output.exists());
  text(manifest(data));json(binary(data).put("format","text"));
  try{fetch(17,1);fail();}catch(IOException e){assertTrue(e.getMessage().contains("MUSIC_OUTPUT_FORMAT"));}assertFalse(output.exists());
 }
 @Test public void supportedPathsAreAcceptedAndUnsafeQueriesFailBeforeAnyNetworkRequest()throws Exception{
  for(String path:new String[]{"api/kernels","api/kernelspecs","api/kernels/fixture-id","api/kernels/fixture-id/channels?session_id=fixture-id","api/contents",ColabRuntime.contentPath(folder,null),ColabRuntime.contentPath(folder+"/progress.json","text"),ColabRuntime.contentPath(folder+"/output_0.pcm","base64")})assertNotNull(runtime.runtimeRequest(path));
  for(String path:new String[]{"api/contents/other/result.json?format=text","api/contents/"+folder+"/../secret","api/contents/"+folder+"/%2e%2e/secret","api/contents/"+folder+"//result.json","api/contents/"+folder+"/result.json?format=text&token=private","api/contents/"+folder+"/output.pcm?content=1&format=base64&download=1","https://elsewhere.invalid/api/contents/"+folder}){
   try{runtime.runtimeRequest(path);fail(path);}catch(IOException e){assertTrue(e.getMessage().contains("JUPYTER_PATH_INVALID"));}
  }
  assertEquals(0,server.getRequestCount());
 }
 @Test public void cancelledFetchDeletesPartialFileWithoutContactingServer()throws Exception{
  Files.write(output.toPath(),new byte[]{1,2,3});cancel.set(true);
  try{fetch(17,1);fail();}catch(CancellationException expected){}
  assertFalse(output.exists());assertEquals(0,server.getRequestCount());
 }
 @Test public void resultFailureDiagnosticsAreSpecificAndContainNoServerText(){
  for(String code:new String[]{"JUPYTER_PATH_INVALID","MUSIC_RESULT_SCHEMA","MUSIC_OUTPUT_FORMAT","MUSIC_OUTPUT_LENGTH","MUSIC_OUTPUT_HASH","RESULT_VERIFIED"})assertTrue(ColabDiagnostics.safe(code));
 }
}
