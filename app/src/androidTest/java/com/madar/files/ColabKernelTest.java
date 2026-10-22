package com.madar.files;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import okio.ByteString;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.nio.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ColabKernelTest {
 private MockWebServer server;private OkHttpClient client;
 @Before public void setup()throws Exception{server=new MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0);client=new OkHttpClient.Builder().readTimeout(10,TimeUnit.SECONDS).build();}
 @After public void close()throws Exception{client.dispatcher().cancelAll();client.connectionPool().evictAll();server.close();}
 private JSONObject reply(String request,String status)throws Exception{JSONObject m=new JSONObject(request);return new JSONObject().put("header",new JSONObject().put("msg_type","execute_reply")).put("parent_header",m.getJSONObject("header")).put("content",new JSONObject().put("status",status).put("ename","FileNotFoundError").put("evalue","SECRET_TOKEN_MUST_NOT_APPEAR"));}
 private Request request(){return new Request.Builder().url(server.url("/api/kernels/test/channels").newBuilder().host("127.0.0.1").build()).build();}
 @Test(timeout=20000) public void realWebSocketExecutesTextProtocol()throws Exception{
  AtomicReference<String> code=new AtomicReference<>();server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){@Override public void onMessage(WebSocket socket,String message){try{JSONObject m=new JSONObject(message);code.set(m.getJSONObject("content").getString("code"));assertFalse(m.getJSONObject("content").getBoolean("store_history"));socket.send(reply(message,"ok").toString());}catch(Exception e){socket.cancel();}}}));
  ColabKernel.run(client,request(),"fixture_code",new AtomicBoolean(),()->false);assertEquals("fixture_code",code.get());
 }
 @Test(timeout=20000) public void realWebSocketAcceptsDefaultBinaryReplies()throws Exception{
  server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){@Override public void onMessage(WebSocket socket,String message){try{byte[] json=reply(message,"ok").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);ByteBuffer frame=ByteBuffer.allocate(8+json.length).order(ByteOrder.BIG_ENDIAN).putInt(1).putInt(8).put(json);socket.send(ByteString.of(frame.array()));}catch(Exception e){socket.cancel();}}}));ColabKernel.run(client,request(),"test",new AtomicBoolean(),()->false);
 }
 @Test(timeout=20000) public void pythonErrorsAreClassifiedWithoutRawMessages()throws Exception{
  server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){@Override public void onMessage(WebSocket socket,String message){try{socket.send(reply(message,"error").toString());}catch(Exception e){socket.cancel();}}}));try{ColabKernel.run(client,request(),"test",new AtomicBoolean(),()->false);fail();}catch(java.io.IOException e){assertTrue(e.getMessage().contains("PY_FILE_NOT_FOUND"));assertFalse(e.getMessage().contains("SECRET"));}
 }
 @Test(timeout=20000) public void rejectedHandshakeIncludesOnlyHttpStatus()throws Exception{
  server.enqueue(new MockResponse().setResponseCode(403).setBody("SECRET_TOKEN_MUST_NOT_APPEAR"));try{ColabKernel.run(client,request(),"test",new AtomicBoolean(),()->false);fail();}catch(java.io.IOException e){assertTrue(e.getMessage().contains("WS_HTTP_403"));assertFalse(e.getMessage().contains("SECRET"));}
 }
 @Test(timeout=20000) public void pythonFailureFetchesPreciseWorkerDiagnosticBeforeThrowing()throws Exception{
  AtomicBoolean replied=new AtomicBoolean();server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){@Override public void onMessage(WebSocket socket,String message){try{replied.set(true);socket.send(reply(message,"error").toString());}catch(Exception e){socket.cancel();}}}));
  try{ColabKernel.run(client,request(),"test",new AtomicBoolean(),()->{if(replied.get())throw new java.io.IOException("Colab · GPU_LIBRARIES");return false;});fail();}catch(java.io.IOException e){assertEquals("Colab · GPU_LIBRARIES",e.getMessage());}
 }
 @Test(timeout=20000) public void disconnectedSocketCanRecoverFromCompletedRestProgress()throws Exception{
  AtomicInteger polls=new AtomicInteger();server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){@Override public void onMessage(WebSocket socket,String message){socket.close(1000,"private reason ignored");}}));ColabKernel.run(client,request(),"test",new AtomicBoolean(),()->polls.incrementAndGet()>1);assertTrue(polls.get()>1);
 }
 @Test(timeout=20000) public void cancelClosesPendingExecution()throws Exception{
  AtomicBoolean cancel=new AtomicBoolean();server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){@Override public void onMessage(WebSocket socket,String message){cancel.set(true);}}));try{ColabKernel.run(client,request(),"test",cancel,()->false);fail();}catch(java.util.concurrent.CancellationException expected){}
 }
 @Test(timeout=20000) public void malformedBinaryAndUnrelatedRepliesCannotCompleteExecution()throws Exception{
  try{ColabKernel.decode(ByteString.of(new byte[8]));fail();}catch(java.io.IOException expected){}
  assertNull(ColabKernel.reply("different",reply(ColabKernel.message("ours","s","code").toString(),"ok")));
 }
 private MockResponse success(){return new MockResponse().withWebSocketUpgrade(new WebSocketListener(){@Override public void onMessage(WebSocket socket,String message){try{socket.send(reply(message,"ok").toString());}catch(Exception e){socket.cancel();}}});}
 private ColabKernel.Policy fast(){return new ColabKernel.Policy(500,20,200,3000,10,2);}
 @Test(timeout=50000) public void coldKernelHandshakeLongerThanOldThirtySecondLimitSucceeds()throws Exception{
  server.enqueue(success().setHeadersDelay(32,TimeUnit.SECONDS));long start=System.nanoTime();
  ColabKernel.run(client,request(),"delayed",new AtomicBoolean(),()->false);
  assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)>=31);
 }
 @Test(timeout=15000) public void transientHandshakeRefreshesAndSendsCommandOnlyOnce()throws Exception{
  server.enqueue(new MockResponse().setResponseCode(503));server.enqueue(success());AtomicInteger factories=new AtomicInteger();
  try(ColabKernel.Session session=new ColabKernel.Session(client,refresh->{assertEquals(factories.get()>0,refresh);factories.incrementAndGet();return request();},new AtomicBoolean(),event->{},fast())){session.execute("once",()->false);}
  assertEquals(2,factories.get());assertEquals(2,server.getRequestCount());
 }
 @Test(timeout=15000) public void opensBeforeUploadAndDoesNotExecuteUntilContentIsReady()throws Exception{
  AtomicInteger messages=new AtomicInteger();server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){@Override public void onMessage(WebSocket socket,String message){try{messages.incrementAndGet();socket.send(reply(message,"ok").toString());}catch(Exception e){socket.cancel();}}}));server.enqueue(new MockResponse().setBody("{}"));
  try(ColabKernel.Session session=new ColabKernel.Session(client,refresh->request(),new AtomicBoolean(),event->{},fast())){
   RecordedRequest upgrade=server.takeRequest(2,TimeUnit.SECONDS);assertNotNull(upgrade);assertEquals("madarfiles",upgrade.getHeader("X-Colab-Client-Agent"));assertEquals(0,messages.get());
   try(Response response=client.newCall(new Request.Builder().url(server.url("/api/contents/madar_fixture/audio.wav")).put(RequestBody.create("fixture",MediaType.get("text/plain"))).build()).execute()){assertEquals(200,response.code());}
   assertEquals(0,messages.get());session.execute("ready",()->false);assertEquals(1,messages.get());
   try{session.execute("duplicate",()->false);fail();}catch(java.io.IOException e){assertTrue(e.getMessage().contains("EXECUTION_ALREADY_SENT"));}
  }
 }
 @Test(timeout=15000) public void missingProgressAfterDisconnectNeverReplaysQueuedExecution()throws Exception{
  AtomicInteger messages=new AtomicInteger(),connections=new AtomicInteger();server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){@Override public void onMessage(WebSocket socket,String message){messages.incrementAndGet();socket.close(1000,"ignored");}}));
  try(ColabKernel.Session session=new ColabKernel.Session(client,refresh->{connections.incrementAndGet();return request();},new AtomicBoolean(),event->{},fast())){
   try{session.execute("once",()->{throw new ColabRuntime.HttpFailure(404);});fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("WORKER_NOT_STARTED"));}
  }assertEquals(1,messages.get());assertEquals(1,connections.get());
 }
 @Test(timeout=15000) public void transientRestFailuresRecoverWithoutReplayingExecution()throws Exception{
  AtomicInteger reads=new AtomicInteger();server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){@Override public void onMessage(WebSocket socket,String message){}}));
  try(ColabKernel.Session session=new ColabKernel.Session(client,refresh->request(),new AtomicBoolean(),event->{},fast())){session.execute("once",()->{if(reads.incrementAndGet()==1)throw new ColabRuntime.NetworkFailure(new java.net.SocketTimeoutException());return true;});}assertEquals(2,reads.get());assertEquals(1,server.getRequestCount());
 }
 @Test(timeout=15000) public void handshakeRetriesAreBoundedAndCancelable()throws Exception{
  server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));AtomicInteger attempts=new AtomicInteger();
  try{new ColabKernel.Session(client,refresh->{attempts.incrementAndGet();return request();},new AtomicBoolean(),event->{},fast());fail();}catch(java.io.IOException e){assertTrue(e.getMessage().contains("WS_CONNECT_TIMEOUT"));}assertEquals(2,attempts.get());
 }
 @Test(timeout=15000) public void cancellationWhileHandshakePendingDoesNotWaitForDeadline()throws Exception{
  server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));AtomicBoolean cancel=new AtomicBoolean();ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();timer.schedule(()->cancel.set(true),200,TimeUnit.MILLISECONDS);
  try{try{ColabKernel.open(client,refresh->request(),cancel,event->{});fail();}catch(CancellationException expected){}}finally{timer.shutdownNow();}
 }

}
