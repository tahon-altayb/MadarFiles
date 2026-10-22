package com.madar.files;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only Colab connection probe. Never creates a runtime or sends media. */
final class ColabProbe {
 static final String SCOPE="https://www.googleapis.com/auth/colaboratory";
 interface Transport {JSONObject get(String path,String token,String project,AtomicBoolean cancel)throws Exception;}
 interface Progress {void update(String message);}
 static final class Failure extends IOException {
  final int status;
  Failure(int status,String message){super(message);this.status=status;}
 }
 static void check(AtomicBoolean cancel){if(cancel.get()||Thread.currentThread().isInterrupted())throw new CancellationException();}
 static boolean validProject(String value){return value!=null&&value.matches("[a-z][a-z0-9-]{4,28}[a-z0-9]");}
 static String run(String token,String project,AtomicBoolean cancel,Progress progress)throws Exception{return run(token,project,cancel,progress,ColabProbe::get);}
 static String run(String token,String project,AtomicBoolean cancel,Progress progress,Transport transport)throws Exception {
  check(cancel);if(!validProject(project))throw new IOException("تحقق من معرّف مشروع Google Cloud");
  if(token==null||token.isEmpty()||token.length()>16384||token.matches("(?s).*\\s.*"))throw new IOException("لم يصل تصريح الدخول من Google؛ أعد الاتصال");
  progress.update("قراءة اشتراك Colab…");JSONObject subscription=transport.get("subscription",token,project,cancel);
  check(cancel);progress.update("فحص الموارد المؤهلة لحسابك…");JSONObject specs=transport.get("runtimespecs",token,project,cancel);
  check(cancel);return summary(project,subscription,specs);
 }
 static String summary(String project,JSONObject subscription,JSONObject response)throws Exception {
  if(!validProject(project))throw new IOException("معرّف مشروع غير صالح");
  Object rawTier=subscription.get("tier");if(!(rawTier instanceof String))throw new IOException("استجابة اشتراك Colab غير صالحة");
  String tier;
  switch((String)rawTier){case "SUBSCRIPTION_TIER_FREE":tier="مجاني";break;case "SUBSCRIPTION_TIER_PRO":tier="Colab Pro";break;case "SUBSCRIPTION_TIER_PRO_PLUS":tier="Colab Pro+";break;case "SUBSCRIPTION_TIER_UNSPECIFIED":tier="غير محدد";break;default:throw new IOException("نوع اشتراك Colab غير معروف؛ يلزم تحديث الفحص");}
  JSONArray list=response.optJSONArray("runtimeSpecs");if(list==null)list=new JSONArray();if(list.length()>100)throw new IOException("قائمة موارد Colab أكبر من الحد");
  StringBuilder eligible=new StringBuilder(),ineligible=new StringBuilder();int available=0;
  for(int i=0;i<list.length();i++){
   JSONObject item=list.getJSONObject(i),key=item.getJSONObject("key");Object allowed=item.has("eligible")?item.get("eligible"):Boolean.FALSE;if(!(allowed instanceof Boolean))throw new IOException("حالة مورد Colab غير صالحة");
   String variant=safe(key,"variant"),accelerator=safe(key,"accelerator"),shape=safe(key,"shape");
   if(!variant.matches("VARIANT_CPU|VARIANT_GPU|VARIANT_TPU")||!shape.matches("SHAPE_STANDARD|SHAPE_HIGHMEM"))throw new IOException("مواصفات Colab جديدة؛ يلزم تحديث الفحص");
   String text=(variant.equals("VARIANT_CPU")?"CPU":variant.equals("VARIANT_GPU")?"GPU":"TPU")+" · "+accelerator+(shape.equals("SHAPE_HIGHMEM")?" · ذاكرة مرتفعة":" · ذاكرة عادية");
   if((Boolean)allowed){available++;eligible.append("• ").append(text).append('\n');}else ineligible.append("• ").append(text).append('\n');
  }
  StringBuilder out=new StringBuilder("مدار 1.4.5 · فحص Colab\nالمشروع: ").append(project).append("\nالاتصال: نجح\nالاشتراك: ").append(tier).append("\n\nالموارد المؤهلة: ").append(available).append('\n');
  out.append(available==0?"لم تُرجع الخدمة موارد مؤهلة لهذا الحساب.\n":eligible);
  if(ineligible.length()>0)out.append("\nموارد غير مؤهلة حاليًا:\n").append(ineligible);
  out.append("\nهذا فحص للأهلية فقط؛ لم تُنشأ جلسة ولم يُرفع صوت أو فيديو. أهلية المورد لا تضمن توفره وقت التشغيل. معالجة المقاطع عبر Colab لم تُفعّل في هذه النسخة.");
  return out.toString();
 }
 private static String safe(JSONObject key,String field)throws Exception {Object raw=key.get(field);if(!(raw instanceof String)||!((String)raw).matches("[A-Za-z0-9_.-]{1,40}"))throw new IOException("مواصفات مورد غير صالحة");return (String)raw;}
 static Failure failure(int status,JSONObject body){
  // Only recognized machine reasons are used; server messages may echo credentials.
  String reason="";JSONObject error=body==null?null:body.optJSONObject("error");JSONArray details=error==null?null:error.optJSONArray("details");
  if(details!=null)for(int i=0;i<Math.min(20,details.length());i++){JSONObject item=details.optJSONObject(i);if(item==null)continue;String value=item.optString("reason");if(value.matches("SERVICE_DISABLED|SERVICE_NOT_VISIBLE|ACCESS_TOKEN_SCOPE_INSUFFICIENT|USER_PROJECT_DENIED|CONSUMER_INVALID")){reason=value;break;}}
  String message;
  switch(reason){
   case "SERVICE_DISABLED":message="فعّل Colab API في المشروع المحدد ثم أعد الفحص";break;
   case "SERVICE_NOT_VISIBLE":message="المشروع غير مصرح له بخدمة Colab؛ راجع المشروع الذي حصل على الموافقة";break;
   case "ACCESS_TOKEN_SCOPE_INSUFFICIENT":message="لم تُمنح صلاحية Colab للحساب؛ أعد الاتصال وراجع شاشة الموافقة";break;
   case "USER_PROJECT_DENIED":message="الحساب لا يملك صلاحية استخدام خدمات المشروع المحدد؛ اختر حساب المشروع";break;
   case "CONSUMER_INVALID":message="معرّف المشروع أو إعداد استهلاك الخدمة غير صالح";break;
   default:message=status==401?"انتهى تصريح الدخول؛ اضغط اتصال مجددًا":status==403?"رفضت Google الوصول؛ تحقق من حساب الاختبار والمشروع الموافق عليه وصلاحية Colab":status==429?"وصل Colab إلى حد الطلبات؛ انتظر ثم أعد الفحص":status>=500?"خدمة Colab غير متاحة مؤقتًا؛ أعد الفحص لاحقًا":"تعذر فحص Colab؛ راجع إعداد المشروع";
  }
  return new Failure(status,message+" ("+status+(reason.isEmpty()?"":" · "+reason)+")");
 }
 static long retryDelay(int status,int attempt,String retryAfter){
  if(attempt>=2||!(status==408||status==429||status>=500&&status<=599||status==0))return -1;
  if(retryAfter!=null&&!retryAfter.isEmpty())try{long seconds=Long.parseLong(retryAfter.trim());if(seconds<0||seconds>15)return -1;return Math.max(1000L<<attempt,seconds*1000);}catch(NumberFormatException e){return -1;}
  return 1000L<<attempt;
 }
 private static void pause(long millis,AtomicBoolean cancel)throws InterruptedException {long end=System.nanoTime()+millis*1000000;while(System.nanoTime()<end){check(cancel);Thread.sleep(Math.min(100,Math.max(1,(end-System.nanoTime())/1000000)));}check(cancel);}
 private static JSONObject get(String path,String token,String project,AtomicBoolean cancel)throws Exception {
  if(!path.matches("subscription|runtimespecs"))throw new IOException("طلب فحص غير صالح");
  for(int attempt=0;;attempt++){
   check(cancel);HttpURLConnection connection=(HttpURLConnection)new URL("https://colaboratory.googleapis.com/v1beta/"+path).openConnection();
   AtomicBoolean done=new AtomicBoolean();Thread watcher=new Thread(()->{try{while(!done.get()){if(cancel.get()){connection.disconnect();return;}Thread.sleep(100);}}catch(InterruptedException ignored){}},"madar-colab-cancel");watcher.setDaemon(true);
   long delay=-1;IOException failure=null;
   try{
    connection.setInstanceFollowRedirects(false);connection.setRequestMethod("GET");connection.setConnectTimeout(15000);connection.setReadTimeout(30000);connection.setRequestProperty("Authorization","Bearer "+token);connection.setRequestProperty("X-Goog-User-Project",project);connection.setRequestProperty("Accept","application/json");watcher.start();
    int status=connection.getResponseCode();check(cancel);JSONObject body;
    try{body=read(status==200?connection.getInputStream():connection.getErrorStream(),cancel);}catch(JSONException e){if(status==200)throw new IOException("استجابة Colab غير صالحة");body=new JSONObject();}
    if(status==200){check(cancel);return body;}
    failure=failure(status,body);delay=retryDelay(status,attempt,connection.getHeaderField("Retry-After"));
   }catch(Failure e){throw e;}catch(IOException e){check(cancel);failure=new IOException("انقطع اتصال فحص Colab؛ تحقق من الشبكة وأعد المحاولة");delay=retryDelay(0,attempt,null);}
   finally{done.set(true);watcher.interrupt();connection.disconnect();}
   if(delay<0)throw failure;pause(delay,cancel);
  }
 }
 private static JSONObject read(InputStream stream,AtomicBoolean cancel)throws Exception {
  if(stream==null)return new JSONObject();
  try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] bytes=new byte[4096];int count;while((count=in.read(bytes))!=-1){check(cancel);if(out.size()+count>131072)throw new IOException("استجابة Colab أكبر من الحد");out.write(bytes,0,count);}check(cancel);return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));}
 }
}
