package com.madar.files;

import android.content.Context;
import okhttp3.Response;
import org.json.*;
import java.io.*;
import java.util.*;

/** Keeps Google's fixed error codes, never response messages, credentials or upload URLs. */
final class DriveFailure extends IOException {
 final int status;final String reason,stage,project;final boolean retryable;
 private static final Set<String> REASONS=new HashSet<>(Arrays.asList("accessNotConfigured","SERVICE_DISABLED","API_DISABLED","storageQuotaExceeded","rateLimitExceeded","userRateLimitExceeded","dailyLimitExceeded","RATE_LIMIT_EXCEEDED","RESOURCE_EXHAUSTED","insufficientPermissions","ACCESS_TOKEN_SCOPE_INSUFFICIENT","authError","UNAUTHENTICATED","domainPolicy","invalidSharingRequest","sharingOutsideDomainNotAllowed","insufficientFilePermissions","appNotAuthorizedToFile","notFound","PERMISSION_DENIED"));
 DriveFailure(int code,String why,String at,String consumer){super(message(code,why,at));status=code;reason=why;stage=at;project=validProject(consumer);retryable=code==429||code>=500||Arrays.asList("rateLimitExceeded","userRateLimitExceeded","RATE_LIMIT_EXCEEDED").contains(why);}
 static DriveFailure from(Response response,String stage){String body="";try{body=response.peekBody(65536).string();}catch(Exception ignored){}return parse(response.code(),body,stage);}
 static DriveFailure parse(int status,String body,String stage){String reason="UNKNOWN",project="";try{JSONObject error=new JSONObject(body).optJSONObject("error");if(error!=null){JSONArray details=error.optJSONArray("details");if(details!=null)for(int i=0;i<Math.min(details.length(),20);i++){JSONObject d=details.optJSONObject(i);if(d==null||!d.optString("@type").equals("type.googleapis.com/google.rpc.ErrorInfo"))continue;String candidate=d.optString("reason");if(REASONS.contains(candidate))reason=candidate;JSONObject metadata=d.optJSONObject("metadata");if(metadata!=null&&metadata.optString("service").equals("drive.googleapis.com"))project=validProject(metadata.optString("consumer").replaceFirst("^projects/",""));}JSONArray errors=error.optJSONArray("errors");if((reason.equals("UNKNOWN")||reason.equals("PERMISSION_DENIED"))&&errors!=null)for(int i=0;i<Math.min(errors.length(),20);i++){JSONObject d=errors.optJSONObject(i);if(d!=null&&REASONS.contains(d.optString("reason"))){reason=d.getString("reason");break;}}}}catch(Exception ignored){}return new DriveFailure(status,reason,stage,project);}
 static String validProject(String value){return value!=null&&value.matches("(?:[0-9]{6,30}|[a-z][a-z0-9-]{4,61}[a-z0-9])")?value:"";}
 boolean disabled(){return Arrays.asList("accessNotConfigured","SERVICE_DISABLED","API_DISABLED").contains(reason);}
 boolean reconnect(){return status==401||Arrays.asList("insufficientPermissions","ACCESS_TOKEN_SCOPE_INSUFFICIENT","authError","UNAUTHENTICATED").contains(reason);}
 JSONObject details()throws JSONException{return new JSONObject().put("http",status).put("reason",reason).put("stage",stage).put("project",project).put("retryable",retryable).put("setup",disabled()).put("reconnect",reconnect());}
 static void remember(Context c,DriveFailure error){try{GeminiCheckpoint.save(new File(c.getFilesDir(),"drive-diagnostic.json"),error.details());}catch(Exception ignored){}}
 static JSONObject last(Context c){JSONObject value=GeminiCheckpoint.read(new File(c.getFilesDir(),"drive-diagnostic.json"));return value==null?new JSONObject():value;}
 static void clear(Context c){new File(c.getFilesDir(),"drive-diagnostic.json").delete();}
 static String setupUrl(Context c){String project=validProject(last(c).optString("project"));if(project.isEmpty()){int id=c.getResources().getIdentifier("project_id","string",c.getPackageName());if(id!=0)project=validProject(c.getString(id));}return "https://console.cloud.google.com/apis/library/drive.googleapis.com"+(project.isEmpty()?"":"?project="+project);}
 private static String message(int status,String reason,String stage){String detail;
  if(reason.equals("NETWORK"))detail="تعذر الاتصال بـGoogle Drive. تحقق من الشبكة ثم أعد المحاولة.";
  else if(Arrays.asList("accessNotConfigured","SERVICE_DISABLED","API_DISABLED").contains(reason))detail="Google Drive API غير مفعّل في مشروع التطبيق. افتح إعداد المشروع وفعّله مرة واحدة، ثم أعد المحاولة.";
  else if(reason.equals("storageQuotaExceeded"))detail="مساحة حساب Google Drive ممتلئة. أفرغ مساحة في الحساب ثم أعد المحاولة.";
  else if(status==401||Arrays.asList("insufficientPermissions","ACCESS_TOKEN_SCOPE_INSUFFICIENT","authError","UNAUTHENTICATED").contains(reason))detail="صلاحية Google Drive غير كافية أو انتهت. أعد الربط وامنح مدار صلاحية الملفات التي ينشئها.";
  else if(reason.equals("domainPolicy"))detail="سياسة مؤسسة حساب Google تمنع وصول مدار إلى Drive. استخدم حسابًا يسمح بالوصول أو راجع مسؤول المؤسسة.";
  else if(Arrays.asList("invalidSharingRequest","sharingOutsideDomainNotAllowed").contains(reason))detail="حساب Google Drive لا يسمح بمشاركة هذا الملف مع أي شخص يملك الرابط. راجع إعداد المشاركة أو استخدم حسابًا يسمح بها.";
  else if(reason.equals("dailyLimitExceeded"))detail="بلغ مشروع Google Drive حد الاستخدام اليومي. راجع حدود المشروع ثم أعد المحاولة.";
  else if(status==429||status>=500||Arrays.asList("rateLimitExceeded","userRateLimitExceeded","RATE_LIMIT_EXCEEDED").contains(reason))detail="توقف Google Drive مؤقتًا أو بلغ حد الطلبات. أعد المحاولة بعد قليل.";
  else detail="رفض Google Drive العملية (HTTP "+status+"). احتُفظ برمز التشخيص لتمييز السبب بدقة.";
  return stage.equals("share")?"اكتمل رفع الملف إلى Drive، لكن لم تكتمل إتاحة الرابط. "+detail+" يمكنك إعادة مشاركة الملف من «ملفاتي المرفوعة» دون رفعه مرة أخرى.":detail;
 }
}
