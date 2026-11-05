package com.madar.files;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class ColabAuthorizationTest {
 private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
 @Test public void expiredReviewAuthorizationRenewsWithoutCreatingOrDeletingRuntime()throws Exception{
  android.content.SharedPreferences journal=context.getSharedPreferences("colab_owned_runtime",0);Map<String,?> saved=journal.getAll();
  String name="runtimes/madar-11111111-1111-1111-1111-111111111111";AtomicInteger requests=new AtomicInteger(),renewals=new AtomicInteger();AtomicReference<ColabRuntime> self=new AtomicReference<>();List<String> events=new ArrayList<>();
  journal.edit().clear().putString("name",name).putString("project","review-project").putString("reviewOwner","review-chat").putBoolean("reviewBusy",false).commit();
  try(ColabRuntime runtime=new ColabRuntime(context,new ColabCredentials.Grant("expired-fixture","review-project"),new AtomicBoolean(),(method,path,body,stop)->{
   requests.incrementAndGet();assertEquals("GET",method);assertEquals("v1beta/"+name,path);
   if(self.get().grant.token.equals("expired-fixture"))throw new ColabRuntime.HttpFailure(401);
   return new JSONObject().put("name",name).put("connectionInfo",new JSONObject().put("url","https://review.example.com").put("token","proxy-fixture").put("expireTime",java.time.Instant.now().plusSeconds(300).toString()));
  },(previous,stop)->{renewals.incrementAndGet();assertEquals("expired-fixture",previous.token);return new ColabCredentials.Grant("fresh-fixture",previous.project);})){
   self.set(runtime);runtime.authEvents=events::add;assertTrue(runtime.resumeMotionReview("review-chat"));assertEquals(2,requests.get());assertEquals(1,renewals.get());assertEquals(name,journal.getString("name",""));assertEquals("fresh-fixture",runtime.grant.token);assertEquals(Arrays.asList("AUTH_REFRESH_START","AUTH_REFRESHED"),events);
   assertEquals("proxy-fixture",runtime.runtimeRequest("api/kernels").build().header("X-Colab-Runtime-Proxy-Token"));assertEquals(2,requests.get());
  }finally{
   android.content.SharedPreferences.Editor e=journal.edit().clear();for(Map.Entry<String,?> v:saved.entrySet()){Object x=v.getValue();if(x instanceof String)e.putString(v.getKey(),(String)x);else if(x instanceof Boolean)e.putBoolean(v.getKey(),(Boolean)x);else if(x instanceof Integer)e.putInt(v.getKey(),(Integer)x);else if(x instanceof Long)e.putLong(v.getKey(),(Long)x);}e.commit();
  }
 }
 @Test public void repeatedUnauthorizedResponseStopsAfterOneRenewal()throws Exception{
  AtomicInteger calls=new AtomicInteger(),renewals=new AtomicInteger();
  try(ColabRuntime r=new ColabRuntime(context,new ColabCredentials.Grant("old","review-project"),new AtomicBoolean(),(m,p,b,c)->{calls.incrementAndGet();throw new ColabRuntime.HttpFailure(401);},(g,c)->{renewals.incrementAndGet();return new ColabCredentials.Grant("new",g.project);})){
   try{r.cloud("GET","v1beta/runtimespecs",null,new AtomicBoolean());fail();}catch(ColabRuntime.HttpFailure expected){assertEquals(401,expected.status);}
   assertEquals(2,calls.get());assertEquals(1,renewals.get());
  }
 }
 @Test public void renewedAuthorizationCannotChangeBillingProject()throws Exception{
  AtomicInteger calls=new AtomicInteger();
  try(ColabRuntime r=new ColabRuntime(context,new ColabCredentials.Grant("old","review-project"),new AtomicBoolean(),(m,p,b,c)->{calls.incrementAndGet();throw new ColabRuntime.HttpFailure(401);},(g,c)->new ColabCredentials.Grant("new","another-project"))){
   try{r.cloud("GET","v1beta/runtimespecs",null,new AtomicBoolean());fail();}catch(java.io.IOException expected){assertEquals("Colab · AUTH_REQUIRED",expected.getMessage());}
   assertEquals(1,calls.get());assertEquals("review-project",r.grant.project);assertEquals("old",r.grant.token);
  }
 }
 @Test public void cancellationDuringRenewalDoesNotReplayRequest()throws Exception{
  AtomicInteger calls=new AtomicInteger();AtomicBoolean stop=new AtomicBoolean();
  try(ColabRuntime r=new ColabRuntime(context,new ColabCredentials.Grant("old","review-project"),stop,(m,p,b,c)->{calls.incrementAndGet();throw new ColabRuntime.HttpFailure(401);},(g,c)->{c.set(true);return new ColabCredentials.Grant("new",g.project);})){
   try{r.cloud("GET","v1beta/runtimespecs",null,stop);fail();}catch(java.util.concurrent.CancellationException expected){}
   assertEquals(1,calls.get());assertEquals("old",r.grant.token);
  }
 }
}
