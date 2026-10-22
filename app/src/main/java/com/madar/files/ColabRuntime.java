package com.madar.files;

import android.content.Context;
import org.json.*;
import okhttp3.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Control plane + Jupyter. OAuth never goes to the runtime; proxy tokens never go to WebView. */
final class ColabRuntime implements AutoCloseable {
 interface CloudTransport {JSONObject call(String method,String path,JSONObject body,AtomicBoolean cancel)throws Exception;}
 final Context context;final ColabCredentials.Grant grant;final AtomicBoolean cancel;private final CloudTransport transport;
 final OkHttpClient http=new OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).writeTimeout(90,TimeUnit.SECONDS).callTimeout(100,TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
 private String runtime="",proxyToken="";private HttpUrl base;private long refreshAt;private volatile WebSocket socket;
 static final class HttpFailure extends IOException {final int status;HttpFailure(int n){super("تعذر اتصال Colab (HTTP "+n+")");status=n;}}
 ColabRuntime(Context c,ColabCredentials.Grant g,AtomicBoolean stop){this(c,g,stop,null);}
 ColabRuntime(Context c,ColabCredentials.Grant g,AtomicBoolean stop,CloudTransport t){context=c;grant=g;cancel=stop;transport=t;}
 JSONObject cloud(String method,String path,JSONObject data,AtomicBoolean stop)throws Exception{
  ColabProbe.check(stop);
  if(!path.matches("v1beta/(runtimespecs|runtimes(\\?.*)?|runtimes/madar-[a-z0-9-]+(\\?.*)?)|v1/operations/[A-Za-z0-9_./-]+"))throw new IOException("مسار Colab غير صالح");
  if(transport!=null)return transport.call(method,path,data,stop);
  return request(new Request.Builder().url("https://colaboratory.googleapis.com/"+path).header("Authorization","Bearer "+grant.token).header("X-Goog-User-Project",grant.project),method,data,stop,2*1024*1024);
 }
 JSONObject request(Request.Builder builder,String method,JSONObject body,AtomicBoolean stop,int maximum)throws Exception {
  ColabProbe.check(stop);RequestBody payload=body==null?null:RequestBody.create(body.toString(),MediaType.get("application/json; charset=utf-8"));Call call=http.newCall(builder.method(method,payload).build());
  Thread watcher=new Thread(()->{try{while(!Thread.currentThread().isInterrupted()){if(stop.get()){call.cancel();return;}Thread.sleep(100);}}catch(InterruptedException ignored){}},"colab-cancel");watcher.setDaemon(true);watcher.start();
  try(Response response=call.execute()){
   ColabProbe.check(stop);if(!response.isSuccessful())throw new HttpFailure(response.code());if(response.body()==null)return new JSONObject();
   try(InputStream in=response.body().byteStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
    byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1){ColabProbe.check(stop);if(out.size()+n>maximum)throw new IOException("استجابة Colab أكبر من الحد");out.write(bytes,0,n);}
    String text=out.toString("UTF-8");return text.trim().isEmpty()?new JSONObject():parseJson(text);
   }
  }catch(IOException e){ColabProbe.check(stop);throw e instanceof HttpFailure?e:new IOException("انقطع اتصال Colab؛ تحقق من الشبكة وأعد التجربة");}finally{watcher.interrupt();}
 }
 static JSONObject parseJson(String value)throws IOException{try{return new JSONObject(value);}catch(JSONException e){throw new IOException("استجابة Colab غير مكتملة؛ أعد التجربة");}}
 static List<JSONObject> candidates(JSONObject response)throws Exception{
  List<JSONObject> out=new ArrayList<>();JSONArray list=response.optJSONArray("runtimeSpecs");if(list==null)return out;
  for(String accelerator:new String[]{"L4","T4","A100"})for(int i=0;i<Math.min(100,list.length());i++){
   JSONObject item=list.getJSONObject(i),key=item.getJSONObject("key");if(Boolean.TRUE.equals(item.opt("eligible"))&&key.optString("variant").equals("VARIANT_GPU")&&key.optString("shape").equals("SHAPE_STANDARD")&&key.optString("accelerator").equals(accelerator))out.add(key);
  }return out;
 }
 static boolean ownName(String name){return name!=null&&name.matches("runtimes/madar-[a-f0-9-]{36}");}
 private android.content.SharedPreferences journal(){return context.getSharedPreferences("colab_owned_runtime",0);}
 void create(MusicSeparator.Progress progress)throws Exception {
  cleanupPrevious();List<JSONObject> specs=candidates(cloud("GET","v1beta/runtimespecs",null,cancel));if(specs.isEmpty())throw new IOException("لا يوجد GPU مناسب مؤهل حاليًا؛ أعد المحاولة لاحقًا");
  for(int i=0;i<Math.min(2,specs.size());i++){
   ColabProbe.check(cancel);JSONObject spec=specs.get(i);String id="madar-"+UUID.randomUUID();runtime="runtimes/"+id;
   if(!journal().edit().putString("name",runtime).putString("project",grant.project).putString("requestId",UUID.randomUUID().toString()).putString("spec",spec.toString()).remove("operation").commit())throw new IOException("تعذر حفظ حالة جلسة Colab");
   progress.update(12,"طلب GPU · "+spec.getString("accelerator")+"؛ قد يستغرق بدء الجلسة دقائق");
   JSONObject operation=cloud("POST","v1beta/runtimes?runtimeId="+id+"&requestId="+journal().getString("requestId",""),new JSONObject().put("runtimeSpec",spec),cancel);
   String name=operation.optString("name");if(!name.isEmpty())journal().edit().putString("operation",name).commit();long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);
   // Track completion even after cancellation, then delete our assignment in finally.
   while(!operation.optBoolean("done")){
    if(!name.matches("operations/[A-Za-z0-9_./-]+")||System.nanoTime()>deadline)throw new IOException("لم يكتمل بدء Colab؛ افحص الجلسة السابقة قبل إعادة التجربة");Thread.sleep(2000);operation=cloud("GET","v1/"+name,null,new AtomicBoolean());
   }
   JSONObject error=operation.optJSONObject("error");if(error!=null){int code=error.optInt("code");journal().edit().clear().commit();runtime="";if((code==8||code==9||code==14)&&i+1<Math.min(2,specs.size())){progress.update(12,"المورد غير متاح؛ تجربة GPU بديل مرة واحدة");continue;}throw new IOException("لم يمنح Colab موردًا للمعالجة ("+code+"). راجع توفر الموارد ورصيد وحدات الحساب.");}
   ColabProbe.check(cancel);refresh();return;
  }
 }
 void refresh()throws Exception{
  JSONObject value=cloud("GET","v1beta/"+runtime,null,cancel);if(!runtime.equals(value.optString("name")))throw new IOException("جلسة Colab غير متطابقة");JSONObject info=value.getJSONObject("connectionInfo");base=runtimeUrl(info.getString("url"));proxyToken=info.getString("token");if(proxyToken.isEmpty()||proxyToken.length()>16384||proxyToken.matches("(?s).*\\s.*"))throw new IOException("تصريح جلسة Colab غير صالح");long remaining;try{remaining=java.time.Instant.parse(info.getString("expireTime")).toEpochMilli()-System.currentTimeMillis();}catch(Exception e){throw new IOException("وقت صلاحية جلسة Colab غير صالح");}if(remaining<=0)throw new IOException("تصريح جلسة Colab منتهي؛ أعد الاتصال وتحقق من ساعة الجهاز");refreshAt=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(1000,Math.min(240000,remaining-30000)));
 }
 static HttpUrl runtimeUrl(String value)throws IOException{
  HttpUrl url=HttpUrl.parse(value);if(url==null||!url.isHttps()||!url.username().isEmpty()||!url.password().isEmpty()||url.query()!=null||url.fragment()!=null||url.port()!=443)throw new IOException("عنوان جلسة Colab غير صالح");// Trust is established by the authenticated, fixed-origin runtimes.get response
  // and the matching runtime name in refresh(), not by an undocumented DNS suffix.
  // Never call this with an address from an Intent, WebView, or user field.
  String host=url.host();if(!host.contains(".")||host.contains(":")||host.matches("[0-9.]+")||host.endsWith(".localhost")||host.endsWith(".local")||host.endsWith(".internal")||host.endsWith(".invalid")||host.endsWith("."))throw new IOException("عنوان جلسة Colab ليس عنوان اتصال عامًا صالحًا");return url.newBuilder().encodedPath(url.encodedPath().endsWith("/")?url.encodedPath():url.encodedPath()+"/").build();
 }
 Request.Builder runtimeRequest(String path)throws Exception{
  if(base==null||System.nanoTime()>refreshAt)refresh();if(!path.matches("api/(kernels|kernelspecs|kernels/[a-zA-Z0-9-]+/channels\\?session_id=[a-zA-Z0-9-]+|contents/madar_[a-zA-Z0-9_./-]+(\\?format=text)?|contents)"))throw new IOException("مسار جلسة غير صالح");return new Request.Builder().url(base.resolve(path)).header("X-Colab-Runtime-Proxy-Token",proxyToken);
 }
 JSONObject jupyter(String method,String path,JSONObject data,int maximum)throws Exception{return request(runtimeRequest(path),method,data,cancel,maximum);}
 void put(String path,String content,String format)throws Exception{jupyter("PUT","api/contents/"+path,new JSONObject().put("type","file").put("format",format).put("content",content),2*1024*1024);}
 String getText(String path,int max)throws Exception{return jupyter("GET","api/contents/"+path+"?format=text",null,max).getString("content");}
 void execute(String kernel,String code,Runnable poll)throws Exception{
  String id=UUID.randomUUID().toString(),session=UUID.randomUUID().toString();CountDownLatch done=new CountDownLatch(1);AtomicBoolean ok=new AtomicBoolean();JSONObject header=new JSONObject().put("msg_id",id).put("username","madar").put("session",session).put("msg_type","execute_request").put("version","5.3").put("date",java.time.Instant.now().toString());
  String message=new JSONObject().put("header",header).put("parent_header",new JSONObject()).put("metadata",new JSONObject()).put("channel","shell").put("content",new JSONObject().put("code",code).put("silent",true).put("store_history",false).put("user_expressions",new JSONObject()).put("allow_stdin",false).put("stop_on_error",true)).toString();
  socket=http.newWebSocket(runtimeRequest("api/kernels/"+kernel+"/channels?session_id="+session).build(),new WebSocketListener(){
   @Override public void onOpen(WebSocket ws,Response response){ws.send(message);}
   @Override public void onMessage(WebSocket ws,String text){if(text.length()>1024*1024){ws.cancel();done.countDown();return;}try{Boolean success=executionReply(id,text);if(success!=null){ok.set(success);done.countDown();}}catch(Exception ignored){}}
   @Override public void onFailure(WebSocket ws,Throwable failure,Response response){done.countDown();}
   @Override public void onClosed(WebSocket ws,int status,String reason){done.countDown();}
  });
  long end=System.nanoTime()+TimeUnit.MINUTES.toNanos(35);try{while(!done.await(2,TimeUnit.SECONDS)){ColabProbe.check(cancel);if(System.nanoTime()>end)throw new IOException("تجاوزت معالجة Colab مهلة 35 دقيقة");poll.run();}ColabProbe.check(cancel);if(!ok.get())throw new IOException("لم تكتمل أوامر جلسة Colab؛ أعد التجربة وأرسل حالة المعالجة");}finally{socket.cancel();socket=null;}
 }
 static Boolean executionReply(String id,String text)throws JSONException{
  JSONObject m=new JSONObject(text);if(m.optJSONObject("parent_header")==null||!id.equals(m.getJSONObject("parent_header").optString("msg_id"))||!m.getJSONObject("header").optString("msg_type").equals("execute_reply"))return null;
  return m.getJSONObject("content").optString("status").equals("ok");
 }
 void cleanupPrevious()throws Exception{
  String name=journal().getString("name","");if(name.isEmpty())return;if(!ownName(name)||!grant.project.equals(journal().getString("project","")))throw new IOException("توجد جلسة مدار سابقة بمشروع آخر؛ اتصل بالمشروع السابق لإنهائها أولًا");String operation=journal().getString("operation","");
  if(operation.isEmpty()){
   String requestId=journal().getString("requestId","");if(!requestId.matches("[a-f0-9-]{36}"))throw new IOException("تعذر استرجاع طلب جلسة مدار؛ أوقفها من Colab");
   JSONObject resolved=cloud("POST","v1beta/runtimes?runtimeId="+name.substring(9)+"&requestId="+requestId,new JSONObject().put("runtimeSpec",new JSONObject(journal().getString("spec",""))),new AtomicBoolean());
   operation=resolved.optString("name");if(!operation.isEmpty())journal().edit().putString("operation",operation).commit();
   if(!resolved.optBoolean("done")&&operation.isEmpty())throw new IOException("تعذر تأكيد حالة جلسة مدار؛ أوقفها من Colab");
  }
  if(!operation.isEmpty()){long end=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);JSONObject state;do{state=cloud("GET","v1/"+operation,null,new AtomicBoolean());if(state.optBoolean("done"))break;if(System.nanoTime()>end)throw new IOException("جلسة مدار السابقة ما زالت قيد الإنشاء؛ أعد فحص الاتصال لاحقًا");Thread.sleep(2000);}while(true);}
  cloud("DELETE","v1beta/"+name+"?allowMissing=true",null,new AtomicBoolean());journal().edit().clear().commit();
 }
 boolean cleanup(){if(socket!=null)socket.cancel();try{cleanupPrevious();return true;}catch(Exception ignored){return false;}}
 @Override public void close(){http.dispatcher().cancelAll();http.connectionPool().evictAll();http.dispatcher().executorService().shutdown();}
}
