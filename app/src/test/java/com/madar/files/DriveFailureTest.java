package com.madar.files;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DriveFailureTest {
 private String legacy(String reason)throws Exception{return new JSONObject().put("error",new JSONObject().put("message","private@example.com Bearer secret-token upload-session").put("errors",new JSONArray().put(new JSONObject().put("reason",reason)))).toString();}
 @Test public void serviceDisabledRetainsProjectWithoutServerMessageOrActivationUrl()throws Exception{
  JSONObject detail=new JSONObject().put("@type","type.googleapis.com/google.rpc.ErrorInfo").put("reason","SERVICE_DISABLED").put("metadata",new JSONObject().put("service","drive.googleapis.com").put("consumer","projects/123456789012").put("activationUrl","https://evil.invalid/?token=private"));
  DriveFailure f=DriveFailure.parse(403,new JSONObject().put("error",new JSONObject().put("details",new JSONArray().put(detail)).put("message","secret-token")).toString(),"start");
  assertTrue(f.disabled());assertEquals("123456789012",f.project);assertEquals("SERVICE_DISABLED",f.reason);assertFalse(f.retryable);assertFalse(f.details().toString().contains("secret-token"));assertFalse(f.details().toString().contains("evil.invalid"));
 }
 @Test public void oldAndNewDisabledCodesAreBothRecognized()throws Exception{for(String reason:new String[]{"accessNotConfigured","API_DISABLED"}){DriveFailure f=DriveFailure.parse(403,legacy(reason),"start");assertTrue(f.disabled());assertTrue(f.getMessage().contains("غير مفعّل"));}}
 @Test public void storageScopeAndPolicyAreDifferentCauses()throws Exception{
  DriveFailure storage=DriveFailure.parse(403,legacy("storageQuotaExceeded"),"upload"),scope=DriveFailure.parse(403,legacy("insufficientPermissions"),"start"),policy=DriveFailure.parse(403,legacy("domainPolicy"),"share");
  assertTrue(storage.getMessage().contains("ممتلئة"));assertFalse(storage.disabled());assertTrue(scope.reconnect());assertFalse(scope.disabled());assertTrue(policy.getMessage().contains("سياسة مؤسسة"));assertFalse(policy.reconnect());assertTrue(policy.getMessage().contains("اكتمل رفع"));
 }
 @Test public void malformedOrUnknown403DoesNotInventDisabledApi()throws Exception{for(String body:new String[]{"<html>secret-token</html>",legacy("SECRET_UNRECOGNIZED"),"{}"}){DriveFailure f=DriveFailure.parse(403,body,"start");assertEquals("UNKNOWN",f.reason);assertFalse(f.disabled());assertFalse(f.getMessage().contains("ممتلئة"));assertFalse(f.details().toString().contains("secret"));assertFalse(f.getMessage().contains("SECRET_UNRECOGNIZED"));}}
 @Test public void temporaryFailuresAreRetryableButDailyQuotaAndScopeAreNot()throws Exception{for(String reason:new String[]{"rateLimitExceeded","userRateLimitExceeded","RATE_LIMIT_EXCEEDED"})assertTrue(DriveFailure.parse(403,legacy(reason),"share").retryable);for(String reason:new String[]{"dailyLimitExceeded","insufficientPermissions","storageQuotaExceeded"})assertFalse(DriveFailure.parse(403,legacy(reason),"start").retryable);assertTrue(DriveFailure.parse(429,"{}","start").retryable);assertTrue(DriveFailure.parse(503,"{}","share").retryable);assertTrue(DriveFailure.parse(401,"{}","start").reconnect());}
 @Test public void projectIdentifiersCannotInjectLinksOrQueryParameters(){assertEquals("",DriveFailure.validProject("123456&redirect=evil"));assertEquals("",DriveFailure.validProject("https://evil.invalid"));assertEquals("madar-project-123",DriveFailure.validProject("madar-project-123"));assertEquals("123456789012",DriveFailure.validProject("123456789012"));}
 @Test public void genericDetailDoesNotHideSpecificLegacyReason()throws Exception{JSONObject e=new JSONObject(legacy("storageQuotaExceeded"));e.getJSONObject("error").put("details",new JSONArray().put(new JSONObject().put("@type","type.googleapis.com/google.rpc.ErrorInfo").put("reason","PERMISSION_DENIED")));assertEquals("storageQuotaExceeded",DriveFailure.parse(403,e.toString(),"upload").reason);}
}
