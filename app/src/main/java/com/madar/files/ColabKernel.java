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
 interface Requests {Request get(boolean refresh)throws Exception;}
 interface Events {void record(String code);}
 static final class Policy {
  final long connectMs,pollMs,initialMs,processMs,retryMs;final int attempts;
  Policy(long connectMs,long pollMs,long initialMs,long processMs,long retryMs,int attempts){this.connectMs=connectMs;this.pollMs=pollMs;this.initialMs=initialMs;this.processMs=processMs;this.retryMs=retryMs;this.attempts=attempts;}
  static Policy standard(){return new Policy(90000,2000,120000,35*60000L,1000,2);}
 }
 static String networkCode(Throwable error){return error instanceof javax.net.ssl.SSLException?"WS_TLS":error instanceof java.net.UnknownHostException?"WS_DNS":error instanceof java.net.SocketTimeoutException?"WS_NETWORK_TIMEOUT":"WS_NETWORK";}
 static boolean retryable(String code){return code.equals("WS_CONNECT_TIMEOUT")||code.equals("WS_NETWORK_TIMEOUT")||code.equals("WS_DNS")||code.equals("WS_NETWORK")||code.equals("WS_CLOSED")||code.equals("WS_HTTP_401")||code.equals("WS_HTTP_403")||code.equals("WS_HTTP_429")||code.equals("WS_HTTP_500")||code.equals("WS_HTTP_502")||code.equals("WS_HTTP_503")||code.equals("WS_HTTP_504");}
 static final class Attempt {
  final AtomicBoolean opened=new AtomicBoolean();final AtomicReference<String> transport=new AtomicReference<>("");
  volatile WebSocket socket;volatile boolean abandoned;
 }
 /** One channel is opened before upload, then used once. Never replay a queued execute request. */
 static final class Session implements AutoCloseable {
  final OkHttpClient client;final Requests requests;final AtomicBoolean cancel;final Events events;final Policy policy;
  final AtomicReference<String> result=new AtomicReference<>();volatile String executionId="";volatile boolean closed;boolean executed,completed;int attempts;Attempt active;
  Session(OkHttpClient client,Requests requests,AtomicBoolean cancel,Events events,Policy policy)throws Exception{this.client=client;this.requests=requests;this.cancel=cancel;this.events=events;this.policy=policy;connect();}
  private void pause(long ms)throws Exception{long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(ms);while(System.nanoTime()<end){ColabProbe.check(cancel);if(closed)throw new IOException("Colab · WS_CLOSED");Thread.sleep(Math.min(50,Math.max(1,ms)));}}
  private void connect()throws Exception{
   String failure="WS_CONNECT_TIMEOUT";
   while(attempts<policy.attempts){ColabProbe.check(cancel);events.record("WS_ATTEMPT_"+(++attempts));
    Request original=requests.get(attempts>1);String origin=original.url().scheme()+"://"+original.url().host()+(original.url().port()==80||original.url().port()==443?"":":"+original.url().port());
    Request request=original.newBuilder().header("Origin",origin).header("X-Colab-Client-Agent","madarfiles").build();
    Attempt a=new Attempt();active=a;
    OkHttpClient channelClient=client.newBuilder().readTimeout(Math.max(1000,policy.connectMs+5000),TimeUnit.MILLISECONDS).callTimeout(Math.max(1000,policy.connectMs+10000),TimeUnit.MILLISECONDS).pingInterval(20,TimeUnit.SECONDS).build();
    WebSocket socket=channelClient.newWebSocket(request,new WebSocketListener(){
     @Override public void onOpen(WebSocket ws,Response response){synchronized(a){if(a.abandoned||closed||cancel.get()){ws.cancel();return;}a.socket=ws;a.opened.set(true);}}
     private void receive(JSONObject message){synchronized(Session.this){try{String value=reply(executionId,message);if(value!=null)result.set(value);}catch(Exception ignored){}}}
     @Override public void onMessage(WebSocket ws,String text){if(text.length()>1024*1024){a.transport.set("WS_FRAME_SIZE");ws.cancel();return;}try{receive(new JSONObject(text));}catch(Exception e){a.transport.set("WS_FRAME_FORMAT");ws.cancel();}}
     @Override public void onMessage(WebSocket ws,ByteString bytes){try{receive(decode(bytes));}catch(Exception e){a.transport.set("WS_FRAME_FORMAT");ws.cancel();}}
     @Override public void onFailure(WebSocket ws,Throwable error,Response response){a.transport.compareAndSet("",response==null?networkCode(error):"WS_HTTP_"+response.code());}
     @Override public void onClosing(WebSocket ws,int code,String reason){a.transport.compareAndSet("","WS_CLOSED");ws.close(code,null);}
     @Override public void onClosed(WebSocket ws,int code,String reason){a.transport.compareAndSet("","WS_CLOSED");}
    });
    a.socket=socket;long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(policy.connectMs);
    try{while(!a.opened.get()&&a.transport.get().isEmpty()&&System.nanoTime()<deadline)pause(50);
     ColabProbe.check(cancel);if(a.opened.get()&&a.transport.get().isEmpty()){events.record("WS_OPEN");return;}
     failure=a.transport.get().isEmpty()?"WS_CONNECT_TIMEOUT":a.transport.get();events.record(failure);
    }catch(Exception e){abandon(a);throw e;}
    abandon(a);if(!retryable(failure)||attempts>=policy.attempts)throw new IOException("Colab · "+failure);events.record("WS_RETRY");pause(policy.retryMs);
   }throw new IOException("Colab · "+failure);
  }
  private void abandon(Attempt a){synchronized(a){a.abandoned=true;if(a.socket!=null)a.socket.cancel();}}
  void executeNext(String code,Poll poll)throws Exception{
   if(!executed||!completed)throw new IOException("Colab · EXECUTION_ALREADY_SENT");
   executed=false;completed=false;execute(code,poll);
  }
  void execute(String code,Poll poll)throws Exception{
   ColabProbe.check(cancel);if(executed)throw new IOException("Colab · EXECUTION_ALREADY_SENT");
   if(active==null||!active.transport.get().isEmpty()){if(active!=null)abandon(active);connect();}
   Attempt a=active;String nextId=UUID.randomUUID().toString();String payload=message(nextId,UUID.randomUUID().toString(),code).toString();synchronized(this){executionId=nextId;result.set(null);}
   synchronized(a){if(closed||a.abandoned||!a.transport.get().isEmpty())throw new IOException("Colab · WS_LOST_BEFORE_EXECUTION");executed=true;if(!a.socket.send(payload))throw new IOException("Colab · WS_SEND");}
   events.record("EXECUTION_QUEUED");long begin=System.nanoTime(),deadline=begin+TimeUnit.MILLISECONDS.toNanos(policy.processMs),nextPoll=begin,lastRest=begin;boolean seen=false,reportedDisconnect=false;
   while(true){ColabProbe.check(cancel);if(closed)throw new IOException("Colab · WS_CLOSED");String reply=result.get();if("OK".equals(reply)){completed=true;return;}
    if(reply!=null){try{if(poll.finished()){completed=true;return;}}catch(ColabRuntime.HttpFailure e){if(e.status!=404)throw e;}throw new IOException("Colab · "+reply);}
    long now=System.nanoTime();if(now>deadline)throw new IOException("Colab · PROCESS_TIMEOUT");
    if(now>=nextPoll){nextPoll=now+TimeUnit.MILLISECONDS.toNanos(policy.pollMs);try{if(poll.finished()){completed=true;return;}seen=true;lastRest=System.nanoTime();}
     catch(ColabRuntime.HttpFailure e){if(e.status==404){if(now-begin>TimeUnit.MILLISECONDS.toNanos(policy.initialMs))throw new IOException("Colab · WORKER_NOT_STARTED");}
      else if(e.status==429||e.status>=500){if(System.nanoTime()-lastRest>TimeUnit.MILLISECONDS.toNanos(policy.initialMs))throw new IOException("Colab · PROGRESS_HTTP_"+e.status);events.record("PROGRESS_RETRY");}
      else throw new IOException("Colab · PROGRESS_HTTP_"+e.status);}
     catch(ColabRuntime.NetworkFailure e){if(System.nanoTime()-lastRest>TimeUnit.MILLISECONDS.toNanos(policy.initialMs))throw e;events.record("PROGRESS_RETRY");}
    }
    if(!a.transport.get().isEmpty()&&!reportedDisconnect){events.record(a.transport.get());if(seen)events.record("REST_CONTINUES");reportedDisconnect=true;}
    if(!a.transport.get().isEmpty()&&!seen&&now-begin>TimeUnit.MILLISECONDS.toNanos(policy.initialMs))throw new IOException("Colab · "+a.transport.get());
    Thread.sleep(50);
   }
  }
  @Override public void close(){closed=true;if(active!=null)abandon(active);}
 }
 static Session open(OkHttpClient client,Requests requests,AtomicBoolean cancel,Events events)throws Exception{return new Session(client,requests,cancel,events,Policy.standard());}
 static void run(OkHttpClient client,Request request,String code,AtomicBoolean cancel,Poll poll)throws Exception{
  Policy p=Policy.standard();try(Session session=new Session(client,refresh->request,cancel,event->{},new Policy(p.connectMs,p.pollMs,p.initialMs,p.processMs,p.retryMs,1))){session.execute(code,poll);}
 }
}
