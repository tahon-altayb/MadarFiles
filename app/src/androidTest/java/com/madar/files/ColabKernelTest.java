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
}
