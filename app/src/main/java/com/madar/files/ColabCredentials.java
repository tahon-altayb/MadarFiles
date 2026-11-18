package com.madar.files;

import android.accounts.Account;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.common.api.Scope;
import java.util.Collections;

/** Native, one-use, two-minute handoff. Never persisted or passed in an Intent. */
final class ColabCredentials {
 static final String MODEL_STORAGE_SCOPE="https://www.googleapis.com/auth/drive.appdata";
 static final class Grant {
  final String token,project;final Account account;private String modelStorageToken;
  Grant(String t,String p){this(t,p,null,null);}
  Grant(String t,String p,String modelStorage,Account owner){token=t;project=p;modelStorageToken=modelStorage;account=owner;}
  String takeModelStorage(){String value=modelStorageToken;modelStorageToken=null;return value;}
  String modelStoragePrefix(){return "MADAR_MODEL_STORAGE_GRANT = "+org.json.JSONObject.quote(modelStorageToken)+"\n";}
 }
 static AuthorizationRequest authorizationRequest(String scope,Account account){
  AuthorizationRequest.Builder request=AuthorizationRequest.builder().setRequestedScopes(Collections.singletonList(new Scope(scope)));
  if(account!=null)request.setAccount(account).setOptOutIncludingGrantedScopes(true);
  return request.build();
 }
 static boolean validStorageToken(String token){return token!=null&&token.matches("[A-Za-z0-9._~+/-]{10,8190}={0,2}");}
 static boolean storageScopes(java.util.Collection<String> scopes){
  if(scopes==null||scopes.isEmpty())return false;
  for(String s:scopes)if(!MODEL_STORAGE_SCOPE.equals(s)&&!"https://www.googleapis.com/auth/drive.appfolder".equals(s))return false;
  return true;
 }
 static boolean verifyStorageToken(String token){
  if(!validStorageToken(token))return false;
  okhttp3.HttpUrl endpoint=okhttp3.HttpUrl.get("https://oauth2.googleapis.com/tokeninfo").newBuilder()
   .addQueryParameter("access_token",token).build();
  okhttp3.OkHttpClient http=new okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
   .callTimeout(12,java.util.concurrent.TimeUnit.SECONDS).build();
  try(okhttp3.Response response=http.newCall(new okhttp3.Request.Builder().url(endpoint).build()).execute()){
   if(!response.isSuccessful()||response.body()==null)return false;
   String scope=new org.json.JSONObject(response.body().string()).optString("scope").trim();
   return storageScopes(java.util.Arrays.asList(scope.split("\\s+")));
  }catch(Exception failure){return false;}
 }
 private static Grant grant;private static long expires;
 static synchronized void put(String t,String p){put(t,p,null,null);}
 static synchronized void put(String t,String p,String modelStorage,Account owner){grant=new Grant(t,p,modelStorage,owner);expires=android.os.SystemClock.elapsedRealtime()+120000;}
 static synchronized boolean ready(){if(android.os.SystemClock.elapsedRealtime()>expires)grant=null;return grant!=null;}
 static synchronized boolean readyForMusic(String profile){return ready();}
 static synchronized Grant take(){if(!ready())return null;Grant g=grant;grant=null;return g;}
 static synchronized void clear(){grant=null;}
}
