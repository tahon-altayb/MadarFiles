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
 private String runtime="",proxyToken="";private HttpUrl base;private long refreshAt;
 static final class NetworkFailure extends IOException {final String code;NetworkFailure(IOException e){super("Colab · "+(e instanceof javax.net.ssl.SSLException?"IO_TLS":e instanceof java.net.UnknownHostException?"IO_DNS":e instanceof java.net.SocketTimeoutException?"IO_TIMEOUT":"IO_NETWORK"));code=getMessage().substring(8);}}
 static final class ProtocolFailure extends IOException {ProtocolFailure(String message){super(message);}}
 static final class HttpFailure extends IOException {final int status;HttpFailure(int n){super("Colab · HTTP_"+n);status=n;}}
 ColabRuntime(Context c,ColabCredentials.Grant g,AtomicBoolean stop){this(c,g,stop,null);}
 ColabRuntime(Context c,ColabCredentials.Grant g,AtomicBoolean stop,CloudTransport t){context=c;grant=g;cancel=stop;transport=t;}
 JSONObject cloud(String method,String path,JSONObject data,AtomicBoolean stop)throws Exception{
  ColabProbe.check(stop);
  if(!path.matches("v1beta/(runtimespecs|runtimes(\\?.*)?|runtimes/madar-[a-z0-9-]+(\\?.*)?)|v1/operations/[A-Za-z0-9_./-]+"))throw new IOException("مسار Colab غير صالح");
  if(transport!=null)return transport.call(method,path,data,stop);
  return request(new Request.Builder().url("https://colaboratory.googleapis.com/"+path).header("Authorization","Bearer "+grant.token).header("X-Goog-User-Project",grant.project),method,data,stop,2*1024*1024,20000);
 }
 JSONObject request(Request.Builder builder,String method,JSONObject body,AtomicBoolean stop,int maximum)throws Exception {return request(builder,method,body,stop,maximum,100000);}
 JSONObject request(Request.Builder builder,String method,JSONObject body,AtomicBoolean stop,int maximum,long timeoutMs)throws Exception {
  ColabProbe.check(stop);RequestBody payload=body==null?null:RequestBody.create(body.toString(),MediaType.get("application/json; charset=utf-8"));Call call=http.newCall(builder.method(method,payload).build());call.timeout().timeout(timeoutMs,TimeUnit.MILLISECONDS);
  Thread watcher=new Thread(()->{try{while(!Thread.currentThread().isInterrupted()){if(stop.get()){call.cancel();return;}Thread.sleep(100);}}catch(InterruptedException ignored){}},"colab-cancel");watcher.setDaemon(true);watcher.start();
  try(Response response=call.execute()){
   ColabProbe.check(stop);if(!response.isSuccessful())throw new HttpFailure(response.code());if(response.body()==null)return new JSONObject();
   try(InputStream in=response.body().byteStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
    byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1){ColabProbe.check(stop);if(out.size()+n>maximum)throw new ProtocolFailure("Colab · RESPONSE_TOO_LARGE");out.write(bytes,0,n);}
    String text=out.toString("UTF-8");return text.trim().isEmpty()?new JSONObject():parseJson(text);
   }
  }catch(IOException e){ColabProbe.check(stop);throw e instanceof HttpFailure||e instanceof ProtocolFailure?e:new NetworkFailure(e);}finally{watcher.interrupt();}
 }
 static JSONObject parseJson(String value)throws IOException{try{return new JSONObject(value);}catch(JSONException e){throw new ProtocolFailure("Colab · RESPONSE_INVALID_JSON");}}
 static List<JSONObject> candidates(JSONObject response)throws Exception{
  List<JSONObject> out=new ArrayList<>();JSONArray list=response.optJSONArray("runtimeSpecs");if(list==null)return out;
  for(String accelerator:new String[]{"L4","T4","A100"})for(int i=0;i<Math.min(100,list.length());i++){
   JSONObject item=list.getJSONObject(i),key=item.getJSONObject("key");if(Boolean.TRUE.equals(item.opt("eligible"))&&key.optString("variant").equals("VARIANT_GPU")&&key.optString("shape").equals("SHAPE_STANDARD")&&key.optString("accelerator").equals(accelerator))out.add(key);
  }return out;
 }
 static boolean ownName(String name){return name!=null&&name.matches("runtimes/madar-[a-f0-9-]{36}");}
 private android.content.SharedPreferences journal(){return context.getSharedPreferences("colab_owned_runtime",0);}
 static List<JSONObject> musicCandidates(JSONObject response,String preferred,String last)throws Exception{
  List<JSONObject> list=candidates(response);String first=Arrays.asList("T4","L4","A100").contains(preferred)?preferred:Arrays.asList("T4","L4","A100").contains(last)?last:"T4";
  List<String> order=new ArrayList<>(Arrays.asList("T4","L4","A100"));order.remove(first);order.add(0,first);list.sort(Comparator.comparingInt(v->order.indexOf(v.optString("accelerator"))));return list;
 }
 void create(MusicSeparator.Progress progress)throws Exception {create(progress,false);}
 void create(MusicSeparator.Progress progress,boolean music)throws Exception {
  ColabProbe.check(cancel);MusicGpuSession.beforeRuntime(this);if(music&&resumePendingMusic(progress))return;if(journal().contains("name"))progress.update(11,"التحقق من إنهاء جلسة مدار السابقة قبل طلب GPU");cleanupPrevious();JSONObject available=cloud("GET","v1beta/runtimespecs",null,cancel);
  android.content.SharedPreferences settings=context.getSharedPreferences("colab_connection",0);
  List<JSONObject> specs=music?musicCandidates(available,settings.getString("music_gpu","auto"),settings.getString("music_gpu_last","")):candidates(available);if(specs.isEmpty())throw new IOException("لا يوجد GPU مناسب مؤهل حاليًا؛ أعد المحاولة لاحقًا");
  for(int i=0;i<Math.min(2,specs.size());i++){
   ColabProbe.check(cancel);JSONObject spec=specs.get(i);String id="madar-"+UUID.randomUUID();runtime="runtimes/"+id;
   if(!journal().edit().putString("name",runtime).putString("project",grant.project).putString("requestId",UUID.randomUUID().toString()).putString("spec",spec.toString()).remove("operation").commit())throw new IOException("تعذر حفظ حالة جلسة Colab");
   progress.update(12,"طلب GPU · "+spec.getString("accelerator")+"؛ قد يستغرق بدء الجلسة دقائق");
   JSONObject operation=cloud("POST","v1beta/runtimes?runtimeId="+id+"&requestId="+journal().getString("requestId",""),new JSONObject().put("runtimeSpec",spec),cancel);
   String name=operation.optString("name");if(!name.isEmpty())journal().edit().putString("operation",name).commit();long began=System.nanoTime(),deadline=began+TimeUnit.MINUTES.toNanos(5);
   // Cancellation leaves the journal intact; background cleanup resolves the same operation.
   while(!operation.optBoolean("done")){
    ColabProbe.check(cancel);if(!name.matches("operations/[A-Za-z0-9_./-]+")||System.nanoTime()>deadline)throw new IOException("لم يوفّر Colab مورد GPU خلال خمس دقائق؛ لم يبدأ فصل الصوت. غيّر مورد GPU من إعدادات Colab بعد انتهاء إغلاق الجلسة");
    long elapsed=TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-began);progress.update(12,"انتظار مورد GPU · "+spec.getString("accelerator")+" · مضى "+elapsed+" ثانية؛ الفصل لم يبدأ بعد");
    long pause=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(System.nanoTime()<pause){ColabProbe.check(cancel);Thread.sleep(50);}operation=cloud("GET","v1/"+name,null,cancel);
   }
   JSONObject error=operation.optJSONObject("error");if(error!=null){int code=error.optInt("code");journal().edit().clear().commit();runtime="";if((code==8||code==9||code==14)&&i+1<Math.min(2,specs.size())){progress.update(12,"المورد غير متاح؛ تجربة GPU بديل مرة واحدة");continue;}throw new IOException("لم يمنح Colab موردًا للمعالجة ("+code+"). راجع توفر الموارد ورصيد وحدات الحساب.");}
   ColabProbe.check(cancel);progress.update(13,"تم تخصيص GPU · قراءة بيانات اتصال المحرك");refresh();if(music)settings.edit().putString("music_gpu_last",spec.getString("accelerator")).apply();return;
  }
 }
 /** Only resume an assignment that is still pending: no previous kernel could have started. */
 private boolean resumePendingMusic(MusicSeparator.Progress progress)throws Exception{
  String name=journal().getString("name",""),operation=journal().getString("operation","");
  if(!ownName(name)||!grant.project.equals(journal().getString("project",""))||!operation.matches("operations/[A-Za-z0-9_./-]+"))return false;
  JSONObject spec;try{spec=new JSONObject(journal().getString("spec",""));}catch(JSONException invalid){return false;}
  if(!spec.optString("variant").equals("VARIANT_GPU")||!spec.optString("shape").equals("SHAPE_STANDARD")||!Arrays.asList("T4","L4","A100").contains(spec.optString("accelerator")))return false;
  JSONObject state=cloud("GET","v1/"+operation,null,cancel);if(state.optBoolean("done"))return false;
  runtime=name;long began=System.nanoTime(),deadline=began+TimeUnit.MINUTES.toNanos(5);
  do{
   ColabProbe.check(cancel);if(System.nanoTime()>deadline)throw new IOException("طلب GPU السابق لم يكتمل بعد · RUNTIME_PENDING_TIMEOUT");
   progress.update(12,"استئناف طلب GPU السابق · "+spec.getString("accelerator")+" · مضى "+TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-began)+" ثانية؛ لم يُطلب مورد آخر");
   long pause=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(System.nanoTime()<pause){ColabProbe.check(cancel);Thread.sleep(50);}state=cloud("GET","v1/"+operation,null,cancel);
  }while(!state.optBoolean("done"));
  if(state.has("error")){journal().edit().clear().commit();runtime="";return false;}
  ColabProbe.check(cancel);progress.update(13,"اكتمل طلب GPU السابق · قراءة بيانات اتصال المحرك");refresh();context.getSharedPreferences("colab_connection",0).edit().putString("music_gpu_last",spec.getString("accelerator")).apply();return true;
 }
 void refresh()throws Exception{
  JSONObject value=cloud("GET","v1beta/"+runtime,null,cancel);if(!runtime.equals(value.optString("name")))throw new IOException("Colab · RUNTIME_ID_MISMATCH");JSONObject info=value.getJSONObject("connectionInfo");base=runtimeUrl(info.getString("url"));proxyToken=info.getString("token");if(proxyToken.isEmpty()||proxyToken.length()>16384||proxyToken.matches("(?s).*\\s.*"))throw new IOException("Colab · RUNTIME_TOKEN_INVALID");long remaining;try{remaining=java.time.Instant.parse(info.getString("expireTime")).toEpochMilli()-System.currentTimeMillis();}catch(Exception e){throw new IOException("Colab · RUNTIME_EXPIRY_INVALID");}if(remaining<=0)throw new IOException("Colab · RUNTIME_TOKEN_EXPIRED");refreshAt=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(1000,Math.min(240000,remaining-30000)));
 }
 static HttpUrl runtimeUrl(String value)throws IOException{
  HttpUrl url=HttpUrl.parse(value);if(url==null||!url.isHttps()||!url.username().isEmpty()||!url.password().isEmpty()||url.query()!=null||url.fragment()!=null||url.port()!=443)throw new IOException("Colab · RUNTIME_URL_INVALID");// Trust is established by the authenticated, fixed-origin runtimes.get response
  // and the matching runtime name in refresh(), not by an undocumented DNS suffix.
  // Never call this with an address from an Intent, WebView, or user field.
  String host=url.host();if(!host.contains(".")||host.contains(":")||host.matches("[0-9.]+")||host.endsWith(".localhost")||host.endsWith(".local")||host.endsWith(".internal")||host.endsWith(".invalid")||host.endsWith("."))throw new IOException("Colab · RUNTIME_URL_INVALID");return url.newBuilder().encodedPath(url.encodedPath().endsWith("/")?url.encodedPath():url.encodedPath()+"/").build();
 }
 static String contentPath(String path,String format)throws IOException{
  if(path==null||!path.matches("madar_[a-zA-Z0-9_]+(?:/[a-zA-Z0-9_-]+(?:\\.[a-zA-Z0-9_-]+)*)?"))throw new ProtocolFailure("Colab · JUPYTER_PATH_INVALID");
  String result="api/contents/"+path;
  if(format==null)return result;
  if(format.equals("text"))return result+"?format=text";
  if(format.equals("base64"))return result+"?content=1&format=base64";
  throw new ProtocolFailure("Colab · JUPYTER_PATH_INVALID");
 }
 static void validateRuntimePath(String path)throws IOException{
  if(path!=null&&path.matches("api/(kernels|kernelspecs|kernels/[a-zA-Z0-9-]+|kernels/[a-zA-Z0-9-]+/channels\\?session_id=[a-zA-Z0-9-]+|contents)"))return;
  if(path!=null&&path.startsWith("api/contents/")){
   String relative=path.substring("api/contents/".length());int query=relative.indexOf('?');
   String file=query<0?relative:relative.substring(0,query),suffix=query<0?"":relative.substring(query);
   String format=suffix.isEmpty()?null:suffix.equals("?format=text")?"text":suffix.equals("?content=1&format=base64")?"base64":"invalid";
   if(path.equals(contentPath(file,format)))return;
  }
  throw new ProtocolFailure("Colab · JUPYTER_PATH_INVALID");
 }
 Request.Builder runtimeRequest(String path)throws Exception{
  validateRuntimePath(path);if(base==null||System.nanoTime()>refreshAt)refresh();
  return new Request.Builder().url(base.resolve(path)).header("X-Colab-Runtime-Proxy-Token",proxyToken).header("X-Colab-Client-Agent","madarfiles");
 }
 JSONObject jupyter(String method,String path,JSONObject data,int maximum)throws Exception{return jupyter(method,path,data,maximum,100000);}
 JSONObject jupyter(String method,String path,JSONObject data,int maximum,long timeoutMs)throws Exception{
  // Replay only reads and PUT of the exact same owned content. Never replay kernel/runtime creation.
  for(int attempt=0;;attempt++)try{return request(runtimeRequest(path),method,data,cancel,maximum,timeoutMs);}catch(IOException e){
   if(attempt>0||!(method.equals("GET")||method.equals("PUT")))throw e;
   if(e instanceof HttpFailure){int status=((HttpFailure)e).status;if(status==401||status==403)refresh();else if(status!=429&&status!=500&&status!=502&&status!=503&&status!=504)throw e;}
   else if(!(e instanceof NetworkFailure))throw e;
   long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(System.nanoTime()<end){ColabProbe.check(cancel);Thread.sleep(50);}
  }
 }
 void put(String path,String content,String format)throws Exception{jupyter("PUT",contentPath(path,null),new JSONObject().put("type","file").put("format",format).put("content",content),2*1024*1024);}
 String getText(String path,int max)throws Exception{return getText(path,max,100000);}
 String getText(String path,int max,long timeoutMs)throws Exception{return jupyter("GET",contentPath(path,"text"),null,max,timeoutMs).getString("content");}
 byte[] getBinary(String path,int maximum)throws Exception{
  JSONObject file=jupyter("GET",contentPath(path,"base64"),null,maximum);
  if(!file.optString("format").equals("base64")||!(file.opt("content") instanceof String))throw new ProtocolFailure("Colab · MUSIC_OUTPUT_FORMAT");
  try{return android.util.Base64.decode(file.getString("content"),android.util.Base64.DEFAULT);}
  catch(IllegalArgumentException e){throw new ProtocolFailure("Colab · MUSIC_OUTPUT_FORMAT");}
 }
 ColabKernel.Session openKernel(String kernel,ColabKernel.Events events)throws Exception{
  return ColabKernel.open(http,force->{if(force){refresh();JSONObject state=jupyter("GET","api/kernels/"+kernel,null,65536);if(state.optString("execution_state").equals("dead"))throw new IOException("Colab · KERNEL_DEAD");}
   return runtimeRequest("api/kernels/"+kernel+"/channels?session_id="+UUID.randomUUID()).build();},cancel,events);
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
 boolean cleanup(){try{cleanupPrevious();return true;}catch(Exception ignored){return false;}}
 @Override public void close(){http.dispatcher().cancelAll();http.connectionPool().evictAll();http.dispatcher().executorService().shutdown();}
}
