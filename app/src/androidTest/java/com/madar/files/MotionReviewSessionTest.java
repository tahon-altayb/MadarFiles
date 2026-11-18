package com.madar.files;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class MotionReviewSessionTest {
 private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private void pool(String name,Object value)throws Exception{Field field=MotionGpuSession.class.getDeclaredField(name);field.setAccessible(true);field.set(null,value);}
 @Test public void ownedSessionSurvivesReviewAndClosesOnlyOnApproval()throws Exception{
  android.content.SharedPreferences journal=context.getSharedPreferences("colab_owned_runtime",0);Map<String,?> before=journal.getAll();List<String> calls=new ArrayList<>();
  ColabRuntime runtime=new ColabRuntime(context,new ColabCredentials.Grant("fixture-token","review-project"),new AtomicBoolean(),(method,path,body,stop)->{calls.add(method+" "+path);return new JSONObject().put("done",true);});
  MotionGpuSession.Lease seed=new MotionGpuSession.Lease(runtime,"review-chat");AtomicBoolean first=new AtomicBoolean(),second=new AtomicBoolean();
  try{
   journal.edit().clear().putString("name","runtimes/madar-11111111-1111-1111-1111-111111111111").putString("project","review-project").putString("operation","operations/review").commit();pool("idle",seed);
   MotionGpuSession.Lease a=MotionGpuSession.acquire(context,null,"review-chat",first);assertSame(seed,a);assertTrue(MotionGpuSession.release(a,true));assertTrue(MotionGpuSession.available("review-chat"));assertTrue(calls.isEmpty());
   first.set(true);MotionGpuSession.Lease b=MotionGpuSession.acquire(context,null,"review-chat",second);assertSame(a,b);Thread.sleep(150);assertFalse("An old completed task cannot cancel a new review",b.stop.get());
   try{MotionGpuSession.beforeRuntime(new ColabRuntime(context,runtime.grant,new AtomicBoolean()));fail();}catch(java.io.IOException expected){}
   assertTrue(MotionGpuSession.release(b,true));try{MotionGpuSession.approve("another-chat");fail();}catch(java.io.IOException expected){}assertTrue(calls.isEmpty());
   assertTrue(MotionGpuSession.approve("review-chat"));assertFalse(MotionGpuSession.held());assertEquals(1,calls.stream().filter(s->s.startsWith("DELETE ")).count());
  }finally{MotionGpuSession.closeIdle();pool("active",null);pool("idle",null);pool("closing",false);android.content.SharedPreferences.Editor restore=journal.edit().clear();for(Map.Entry<String,?> item:before.entrySet())if(item.getValue() instanceof String)restore.putString(item.getKey(),(String)item.getValue());else if(item.getValue() instanceof Boolean)restore.putBoolean(item.getKey(),(Boolean)item.getValue());restore.commit();}
 }
 @Test public void callerCancellationDoesNotRetainABusyWorker()throws Exception{
  List<String> calls=new ArrayList<>();ColabRuntime runtime=new ColabRuntime(context,new ColabCredentials.Grant("fixture-token","review-project"),new AtomicBoolean(),(m,p,b,c)->{calls.add(m);return new JSONObject();});MotionGpuSession.Lease lease=new MotionGpuSession.Lease(runtime,"cancel-review");AtomicBoolean owner=new AtomicBoolean();
  try{pool("idle",lease);MotionGpuSession.acquire(context,null,"cancel-review",owner);owner.set(true);assertTrue(MotionGpuSession.release(lease,true));assertFalse(MotionGpuSession.held());assertTrue(lease.caller.get());}finally{MotionGpuSession.closeIdle();pool("active",null);pool("idle",null);pool("closing",false);}
 }
}
