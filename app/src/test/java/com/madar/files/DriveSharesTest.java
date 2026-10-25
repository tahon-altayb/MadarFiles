package com.madar.files;

import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class DriveSharesTest {
 static final class Memory implements DriveShares.RecordStore {JSONArray files=new JSONArray();public JSONArray list(){return files;}public void save(JSONArray value){files=value;}}
 private DriveShares session(MockWebServer server,Memory memory,AtomicBoolean stop){return new DriveShares("test-token",stop,new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).followRedirects(false).build(),server.url("/upload/"),server.url("/api/"),memory);}
 private File input()throws Exception{File file=Files.createTempFile("madar-drive-test-",".pdf").toFile();Files.write(file.toPath(),new byte[]{1,2,3,4,5});return file;}
 private JSONObject info()throws Exception{return new JSONObject().put("name","sample.pdf").put("mime","application/pdf");}
 @Test public void completeUploadOnlyBecomesPublicAfterPermissionSucceeds()throws Exception{
  try(MockWebServer server=new MockWebServer()){server.start();Memory memory=new Memory();File file=input();server.enqueue(new MockResponse().addHeader("Location",server.url("/session")));server.enqueue(new MockResponse().setBody("{\"id\":\"file123\"}"));server.enqueue(new MockResponse().setBody("{}"));
   try(DriveShares drive=session(server,memory,new AtomicBoolean())){JSONObject result=drive.upload(file,info(),(p,t)->{});assertTrue(result.getBoolean("public"));assertTrue(memory.files.getJSONObject(0).getBoolean("public"));assertEquals("https://drive.google.com/file/d/file123/view",result.getString("url"));RecordedRequest create=server.takeRequest(3,TimeUnit.SECONDS);assertEquals("Bearer test-token",create.getHeader("Authorization"));assertEquals("POST",create.getMethod());RecordedRequest upload=server.takeRequest(3,TimeUnit.SECONDS);assertEquals("bytes 0-4/5",upload.getHeader("Content-Range"));JSONObject permission=new JSONObject(server.takeRequest(3,TimeUnit.SECONDS).getBody().readUtf8());assertEquals("reader",permission.getString("role"));assertEquals("anyone",permission.getString("type"));}finally{file.delete();}
  }
 }
 @Test public void resumesAcknowledgedBytesAndKeepsFailedSharingManageable()throws Exception{
  try(MockWebServer server=new MockWebServer()){server.start();Memory memory=new Memory();File file=input();server.enqueue(new MockResponse().addHeader("Location",server.url("/session")));server.enqueue(new MockResponse().setResponseCode(503));server.enqueue(new MockResponse().setResponseCode(308).addHeader("Range","bytes=0-2"));server.enqueue(new MockResponse().setBody("{\"id\":\"file123\"}"));server.enqueue(new MockResponse().setResponseCode(403));
   try(DriveShares drive=session(server,memory,new AtomicBoolean())){try{drive.upload(file,info(),(p,t)->{});fail("sharing was denied");}catch(IOException expected){assertTrue(expected.getMessage().contains("Drive"));}assertEquals(1,memory.files.length());assertFalse(memory.files.getJSONObject(0).getBoolean("public"));server.takeRequest(3,TimeUnit.SECONDS);server.takeRequest(3,TimeUnit.SECONDS);assertEquals("bytes */5",server.takeRequest(3,TimeUnit.SECONDS).getHeader("Content-Range"));RecordedRequest resumed=server.takeRequest(3,TimeUnit.SECONDS);assertEquals("bytes 3-4/5",resumed.getHeader("Content-Range"));assertArrayEquals(new byte[]{4,5},resumed.getBody().readByteArray());}finally{file.delete();}
  }
 }
 @Test public void cannotSendCredentialsToUnexpectedUploadHost()throws Exception{
  try(MockWebServer server=new MockWebServer()){server.start();Memory memory=new Memory();File file=input();server.enqueue(new MockResponse().addHeader("Location","https://example.invalid/session"));try(DriveShares drive=session(server,memory,new AtomicBoolean())){try{drive.upload(file,info(),(p,t)->{});fail("unexpected host");}catch(IOException expected){assertTrue(expected.getMessage().contains("Drive"));}assertEquals(1,server.getRequestCount());assertEquals(0,memory.files.length());}finally{file.delete();}}
 }
 @Test public void failedDeletionPreservesRecordAndUnknownFilesNeverReachServer()throws Exception{
  try(MockWebServer server=new MockWebServer()){server.start();Memory memory=new Memory();memory.files.put(new JSONObject().put("id","file123"));server.enqueue(new MockResponse().setResponseCode(500));try(DriveShares drive=session(server,memory,new AtomicBoolean())){try{drive.delete("file123");fail();}catch(IOException expected){}assertEquals(1,memory.files.length());try{drive.delete("unknown");fail();}catch(IOException expected){}assertEquals(1,server.getRequestCount());}}
 }
}
