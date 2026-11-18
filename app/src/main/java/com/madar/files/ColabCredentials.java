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
  boolean hasModelStorage(){return modelStorageToken!=null;}
 }
 static AuthorizationRequest authorizationRequest(String scope,Account account){
  AuthorizationRequest.Builder request=AuthorizationRequest.builder().setRequestedScopes(Collections.singletonList(new Scope(scope)));
  if(account!=null)request.setAccount(account).setOptOutIncludingGrantedScopes(true);
  return request.build();
 }
 private static Grant grant;private static long expires;
 static synchronized void put(String t,String p){put(t,p,null,null);}
 static synchronized void put(String t,String p,String modelStorage,Account owner){grant=new Grant(t,p,modelStorage,owner);expires=android.os.SystemClock.elapsedRealtime()+120000;}
 static synchronized boolean ready(){if(android.os.SystemClock.elapsedRealtime()>expires)grant=null;return grant!=null;}
 static synchronized boolean readyForMusic(String profile){return ready()&&(!MusicPipeline.BANDIT.equals(profile)||grant.hasModelStorage());}
 static synchronized Grant take(){if(!ready())return null;Grant g=grant;grant=null;return g;}
 static synchronized void clear(){grant=null;}
}
