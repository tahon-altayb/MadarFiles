package com.madar.files;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.Scope;
import java.util.Collections;

/** Tokens stay in native process memory and never cross the WebView bridge. */
public class DriveAccessActivity extends Activity {
 static final String SCOPE="https://www.googleapis.com/auth/drive.file";
 private static String token;private static long until;
 static synchronized String take(){String t=SystemClock.elapsedRealtime()<until?token:null;token=null;return t;}
 private TextView status;
 @Override public void onCreate(Bundle saved){super.onCreate(saved);status=new TextView(this);status.setPadding(40,80,40,40);status.setText("ربط Google Drive لرفع الملفات وإنشاء روابط QR…");setContentView(status);if(saved==null)authorize();}
 private void authorize(){Identity.getAuthorizationClient(this).authorize(AuthorizationRequest.builder().setRequestedScopes(Collections.singletonList(new Scope(SCOPE))).build()).addOnSuccessListener(this,r->{try{if(r.hasResolution()){PendingIntent p=r.getPendingIntent();if(p==null)throw new IllegalStateException();startIntentSenderForResult(p.getIntentSender(),73,null,0,0,0);}else accept(r);}catch(Exception e){fail();}}).addOnFailureListener(this,e->fail());}
 private void accept(AuthorizationResult r){if(r.getAccessToken()==null||!r.getGrantedScopes().contains(SCOPE)){fail();return;}synchronized(DriveAccessActivity.class){token=r.getAccessToken();until=SystemClock.elapsedRealtime()+120000;}setResult(RESULT_OK);finish();}
 private void fail(){new AlertDialog.Builder(this).setMessage("لم يكتمل ربط Drive. تحقق من الشبكة وعميل OAuth وتفعيل Google Drive API للمشروع.").setPositiveButton("إعادة المحاولة",(d,w)->authorize()).setNegativeButton("رجوع",(d,w)->finish()).setOnCancelListener(d->finish()).show();}
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=73)return;if(result!=RESULT_OK||data==null){finish();return;}try{accept(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data));}catch(Exception e){fail();}}
}
