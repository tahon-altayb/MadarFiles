package com.madar.files;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Fixed diagnostic codes only: never persist prompts, credentials or server bodies. */
final class AgentDiagnostics {
 static final class Failure extends IOException {
  final String code;
  Failure(String code,String message,Throwable cause){super(message+" · "+code,cause);this.code=code;}
 }
 static String code(Throwable error){
  StringBuilder text=new StringBuilder();Throwable cause=error;
  for(int i=0;i<8&&cause!=null;i++,cause=cause.getCause()){
   if(cause instanceof Failure)return ((Failure)cause).code;
   if(cause instanceof java.net.SocketTimeoutException||cause instanceof TimeoutException)return "GEMINI_TIMEOUT";
   text.append(cause.getClass().getSimpleName()).append(' ').append(cause.getMessage()).append(' ');
  }
  String value=text.toString().toLowerCase(Locale.ROOT);
  if(value.matches("(?s).*(429|quota|resource_exhausted|toomanyrequests|الحصة|حد الاستخدام|حد الطلبات).*"))return "GEMINI_QUOTA";
  if(value.matches("(?s).*(app.?check|attestation|play.?integrity).*"))return "GEMINI_APP_CHECK";
  if(value.matches("(?s).*(401|403|permission_denied|unauthenticated|صلاحيات|صلاحية).*"))return "GEMINI_PERMISSION";
  if(value.matches("(?s).*(404|not_found|النموذج غير متاح).*"))return "GEMINI_MODEL_UNAVAILABLE";
  if(value.matches("(?s).*(timeout|timed out|deadline_exceeded|مهلة).*"))return "GEMINI_TIMEOUT";
  if(value.matches("(?s).*(unknownhost|connectexception|socketexception|network|ssl|اتصال).*"))return "GEMINI_NETWORK";
  if(error instanceof org.json.JSONException)return "GEMINI_RESPONSE_INVALID";
  if(error instanceof CancellationException)return "GEMINI_SERVICE_CANCELLED";
  return "GEMINI_REQUEST_FAILED";
 }
 static Failure failure(Throwable error){
  if(error instanceof Failure)return (Failure)error;
  String code=code(error),message;
  switch(code){
   case "GEMINI_TIMEOUT":message="انتهت مهلة اتصال Gemini؛ المرحلة المكتملة محفوظة لإعادة المحاولة";break;
   case "GEMINI_QUOTA":message="بلغ Gemini حد الاستخدام المتاح؛ لم أكرر الطلب تلقائيًا";break;
   case "GEMINI_APP_CHECK":message="تعذر توثيق التطبيق لدى Gemini عبر App Check؛ الطلب محفوظ";break;
   case "GEMINI_PERMISSION":message="رفض Gemini صلاحية الاتصال؛ الطلب محفوظ";break;
   case "GEMINI_MODEL_UNAVAILABLE":message="نموذج Gemini المحدد غير متاح لهذا الاتصال";break;
   case "GEMINI_NETWORK":message="تعذر اتصال Gemini بالشبكة؛ الطلب والمرحلة المكتملة محفوظان";break;
   case "GEMINI_RESPONSE_INVALID":message="أعاد Gemini استجابة غير صالحة؛ لم تُنفذ خطة ناقصة";break;
   case "GEMINI_SERVICE_CANCELLED":message="أُلغي طلب Gemini داخل مسار الاتصال؛ لم أعتبره إلغاءً منك";break;
   default:message="تعذر إكمال طلب Gemini؛ راجع رمز السبب في تقرير المساعد";
  }
  return new Failure(code,message,error);
 }
 static Failure responseFailure(String finish){
  if("MAX_TOKENS".equals(finish))return new Failure("GEMINI_OUTPUT_LIMIT","بلغ رد Gemini حد الإخراج قبل اكتماله؛ لم أُنفذ الخطة الناقصة",null);
  if(Arrays.asList("SAFETY","RECITATION","BLOCKLIST","PROHIBITED_CONTENT","SPII").contains(finish))return new Failure("GEMINI_RESPONSE_BLOCKED","أوقف Gemini الاستجابة وفق سياسة الخدمة؛ الطلب محفوظ",null);
  return new Failure("GEMINI_RESPONSE_INVALID","لم يُرجع Gemini استجابة مكتملة وصالحة؛ الطلب محفوظ",null);
 }
 static String stage(JSONObject input){
  if(input.has("connectionProbe"))return "connection_probe";
  if(input.has("reviewTask"))return "plan_review";
  if(input.has("userRequest"))return "plan_interpret";
  if(input.has("review_task"))return "motion_brief_review";
  if(input.has("client_brief"))return "motion_interpret";
  return "motion_result_review";
 }
 static synchronized void record(Context c,String stage,String status,long began,Throwable error){
  if(!stage.matches("plan_(interpret|review)|motion_(interpret|brief_review|result_review|validate)|connection_probe"))stage="motion_validate";
  long elapsed=Math.max(0,android.os.SystemClock.elapsedRealtime()-began);String issue=error==null?"":status.equals("cancelled")?"CANCELLED":code(error);
  android.content.SharedPreferences p=c.getSharedPreferences("assistant_diagnostics",0);JSONArray old;
  try{old=new JSONArray(p.getString("timeline","[]"));}catch(JSONException invalid){old=new JSONArray();}
  try{JSONArray next=new JSONArray();for(int i=Math.max(0,old.length()-19);i<old.length();i++)next.put(old.get(i));next.put(new JSONObject().put("stage",stage).put("status",status).put("milliseconds",elapsed).put("code",issue));android.content.SharedPreferences.Editor edit=p.edit();if(status.equals("failed")&&issue.matches("GEMINI_(TIMEOUT|NETWORK|REQUEST_FAILED|UNAVAILABLE)"))edit.putLong("downAt",System.currentTimeMillis());else if(status.equals("done"))edit.remove("downAt");edit.putString("provider","gemini").putString("stage",stage).putString("status",status).putString("issue",issue).putLong("milliseconds",elapsed).putString("timeline",next.toString()).apply();}catch(JSONException ignored){}
 }
 /** Gemini timed out or was unreachable in the last ten minutes: the next motion job skips the
  *  online interpretation and uses the chat's own contract instead of waiting minutes again. */
 static String arabic(String code){
  switch(code){
   case "GEMINI_TIMEOUT":return "انتهت المهلة بدون رد";
   case "GEMINI_NETWORK":return "ما وصل الطلب لخادم Google (شبكة أو حجب أو توثيق التطبيق)";
   case "GEMINI_REQUEST_FAILED":return "رفض الخادم الطلب أو فشل";
   case "GEMINI_APP_CHECK":return "تعذر توثيق التطبيق مع Firebase (التطبيق منزّل من خارج المتجر)";
   case "GEMINI_QUOTA":return "انتهت الحصة المتاحة";
   case "GEMINI_PERMISSION":return "رفضت الخدمة التفويض";
   case "GEMINI_UNAVAILABLE":return "تخطيته لأنه ما رد قبل دقائق";
   default:return code.isEmpty()?"سبب غير معروف":code;
  }
 }
 static boolean recentlyDown(Context c){long at=c.getSharedPreferences("assistant_diagnostics",0).getLong("downAt",0);long age=System.currentTimeMillis()-at;return at>0&&age>=0&&age<10*60*1000;}
 static JSONObject report(Context c)throws JSONException{
  android.content.SharedPreferences p=c.getSharedPreferences("assistant_diagnostics",0);
  JSONArray events;try{events=new JSONArray(p.getString("timeline","[]"));}catch(JSONException invalid){events=new JSONArray();}
  return new JSONObject().put("type","agentDiagnostics").put("provider",p.getString("provider","")).put("status",p.getString("status","")).put("stage",p.getString("stage","")).put("milliseconds",p.getLong("milliseconds",0)).put("issue",p.getString("issue","")).put("timeline",events).put("groq",GroqClient.configured(c)).put("geminiResting",recentlyDown(c));
 }
 private AgentDiagnostics(){}
}
