package com.madar.files;

import okhttp3.*;
import okio.ByteString;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Default Jupyter protocol: text or framed binary JSON. No raw server errors are exposed. */
final class ColabKernel {
 interface Poll {boolean finished()throws Exception;}
 static String pythonCode(String name){switch(name){case "FileNotFoundError":return "PY_FILE_NOT_FOUND";case "ModuleNotFoundError":return "PY_MODULE_MISSING";case "ImportError":return "PY_IMPORT";case "CalledProcessError":return "PY_WORKER_EXIT";case "PermissionError":return "PY_PERMISSION";case "SyntaxError":return "PY_SYNTAX";case "NameError":return "PY_NAME";default:return "PY_EXECUTION";}}
 static JSONObject decode(ByteString bytes)throws IOException,JSONException{
  if(bytes.size()<8||bytes.size()>1024*1024)throw new IOException("WS_FRAME_SIZE");ByteBuffer b=bytes.asByteBuffer().order(ByteOrder.BIG_ENDIAN);int count=b.getInt();
  if(count<1||count>64||4L*(count+1)>bytes.size())throw new IOException("WS_FRAME_FORMAT");int[] offsets=new int[count];int previous=4*(count+1);
  for(int i=0;i<count;i++){offsets[i]=b.getInt();if(offsets[i]<previous||offsets[i]>bytes.size())throw new IOException("WS_FRAME_FORMAT");previous=offsets[i];}
  int end=count==1?bytes.size():offsets[1];return new JSONObject(bytes.substring(offsets[0],end).utf8());
 }
 static JSONObject message(String id,String session,String code)throws JSONException{
  return new JSONObject().put("header",new JSONObject().put("msg_id",id).put("username","madar").put("session",session).put("msg_type","execute_request").put("version","5.3").put("date",java.time.Instant.now().toString()))
   .put("parent_header",new JSONObject()).put("metadata",new JSONObject()).put("buffers",new JSONArray()).put("channel","shell")
   .put("content",new JSONObject().put("code",code).put("silent",true).put("store_history",false).put("user_expressions",new JSONObject()).put("allow_stdin",false).put("stop_on_error",true));
 }
 static String reply(String id,JSONObject message)throws JSONException{
  JSONObject parent=message.optJSONObject("parent_header"),header=message.optJSONObject("header");if(parent==null||header==null||!id.equals(parent.optString("msg_id"))||!header.optString("msg_type").equals("execute_reply"))return null;
  JSONObject content=message.getJSONObject("content");return content.optString("status").equals("ok")?"OK":pythonCode(content.optString("ename"));
 }
 static void run(OkHttpClient client,Request request,String code,AtomicBoolean cancel,Poll poll)throws Exception{
  ColabProbe.check(cancel);
  String id=UUID.randomUUID().toString(),session=UUID.randomUUID().toString(),payload=message(id,session,code).toString();
  AtomicBoolean opened=new AtomicBoolean(),sent=new AtomicBoolean();AtomicReference<String> result=new AtomicReference<>(),transport=new AtomicReference<>("");
  String origin=request.url().scheme()+"://"+request.url().host()+(request.url().port()==80||request.url().port()==443?"":":"+request.url().port());
  Request handshake=request.newBuilder().header("Origin",origin).build();
  WebSocket ws=client.newWebSocket(handshake,new WebSocketListener(){
   @Override public void onOpen(WebSocket socket,Response response){opened.set(true);sent.set(socket.send(payload));if(!sent.get())transport.set("WS_SEND");}
   private void receive(JSONObject m){try{String value=reply(id,m);if(value!=null)result.set(value);}catch(Exception ignored){}}
   @Override public void onMessage(WebSocket socket,String text){if(text.length()>1024*1024){transport.set("WS_FRAME_SIZE");socket.cancel();return;}try{receive(new JSONObject(text));}catch(Exception ignored){}}
   @Override public void onMessage(WebSocket socket,ByteString bytes){try{receive(decode(bytes));}catch(Exception e){transport.set("WS_FRAME_FORMAT");socket.cancel();}}
   @Override public void onFailure(WebSocket socket,Throwable failure,Response response){transport.set(response!=null?"WS_HTTP_"+response.code():failure instanceof javax.net.ssl.SSLException?"WS_TLS":"WS_NETWORK");}
   @Override public void onClosed(WebSocket socket,int status,String reason){transport.compareAndSet("","WS_CLOSED_"+status);}
  });
  long begin=System.nanoTime(),end=begin+TimeUnit.MINUTES.toNanos(35),nextPoll=begin;boolean seenProgress=false;
  try{while(true){ColabProbe.check(cancel);String reply=result.get();if("OK".equals(reply))return;if(reply!=null){try{if(poll.finished())return;}catch(ColabRuntime.HttpFailure e){if(e.status!=404)throw e;}throw new IOException("Colab · "+reply);}
    if(!opened.get()&&!transport.get().isEmpty())throw new IOException("Colab · "+transport.get());
    if(System.nanoTime()-begin>TimeUnit.SECONDS.toNanos(30)&&!opened.get())throw new IOException("Colab · WS_CONNECT_TIMEOUT");
    if(System.nanoTime()>end)throw new IOException("Colab · PROCESS_TIMEOUT");
    if(sent.get()&&System.nanoTime()>=nextPoll){nextPoll=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);try{if(poll.finished())return;seenProgress=true;}catch(ColabRuntime.HttpFailure e){if(e.status!=404||System.nanoTime()-begin>TimeUnit.SECONDS.toNanos(60))throw new IOException("Colab · "+(transport.get().isEmpty()?"PROGRESS_HTTP_"+e.status:transport.get()));}}
    // A disconnected socket does not discard a job that is reporting progress through REST.
    if(opened.get()&&!transport.get().isEmpty()&&!seenProgress&&System.nanoTime()-begin>TimeUnit.SECONDS.toNanos(60))throw new IOException("Colab · "+transport.get());
    Thread.sleep(100);
   }}finally{ws.cancel();}
 }
}
