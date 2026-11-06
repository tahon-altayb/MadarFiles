package com.madar.files;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class AgentExecutiveTest {
 private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private String oldExamples;private boolean hadLearn,oldLearn;private final List<String> projects=new ArrayList<>();
 @Before public void before(){oldExamples=context.getSharedPreferences("agent_examples",0).getString("approved",null);hadLearn=context.getSharedPreferences("local_agent",0).contains("learn");oldLearn=context.getSharedPreferences("local_agent",0).getBoolean("learn",true);context.getSharedPreferences("agent_examples",0).edit().clear().commit();context.getSharedPreferences("local_agent",0).edit().putBoolean("learn",true).commit();}
 @After public void after()throws Exception{if(oldExamples==null)context.getSharedPreferences("agent_examples",0).edit().clear().commit();else context.getSharedPreferences("agent_examples",0).edit().putString("approved",oldExamples).commit();android.content.SharedPreferences.Editor e=context.getSharedPreferences("local_agent",0).edit();if(hadLearn)e.putBoolean("learn",oldLearn);else e.remove("learn");e.commit();for(String id:projects)remove(MotionProject.directory(context,id));}
 private void remove(java.io.File path){java.io.File[] children=path.listFiles();if(children!=null)for(java.io.File f:children)remove(f);path.delete();}
 private JSONObject reply(JSONObject data)throws Exception{return new JSONObject().put("candidates",new JSONArray().put(new JSONObject().put("finishReason","STOP").put("content",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",data.toString()))))));}
 private JSONObject plan(String tool)throws Exception{return new JSONObject().put("tool",tool).put("fields",new JSONArray()).put("message","");}
 private JSONObject contract()throws Exception{return new JSONObject().put("needs_information",false).put("question","").put("opening_logo",false).put("opening_quote","").put("physical_visit",false).put("visit_quote","").put("visit_text","").put("address_required",false).put("address_quote","").put("address","").put("address_evidence","").put("address_source","missing").put("logo_index",-1).put("rules",new JSONArray()).put("must_include",new JSONArray()).put("forbidden_claims",new JSONArray()).put("omit_from_copy",new JSONArray());}
 private JSONObject project()throws Exception{JSONObject p=MotionProject.create(context);projects.add(p.getString("id"));p.put("creativeRequest",new JSONObject().put("text","خلي الشعار أولًا ولا تضف موسيقى").put("executiveVerified",true).put("clientContract",contract()));return p;}
 @Test public void modelProposalIsReviewedAndRepairedBeforeDispatch()throws Exception{
  AtomicInteger calls=new AtomicInteger();JSONObject first=plan("trim").put("fields",new JSONArray().put(new JSONObject().put("name","start").put("value","2")).put(new JSONObject().put("name","end").put("value","8"))),fixed=plan("trim").put("fields",new JSONArray().put(new JSONObject().put("name","start").put("value","1")).put(new JSONObject().put("name","end").put("value","3")));
  JSONObject result=AgentCloud.decide("system",new JSONObject().put("userRequest","احتفظ من 1 إلى 3 فقط"),new JSONArray(),"video",new AtomicBoolean(),(body,stop)->{int n=calls.incrementAndGet();if(n==2){String input=body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text");assertTrue(input.contains("untrustedProposal"));assertTrue(input.contains("reviewTask"));}return reply(n==1?first:fixed);});
  assertEquals(2,calls.get());assertEquals("1",result.getJSONArray("fields").getJSONObject(0).getString("value"));assertTrue(result.getBoolean("agentReviewed"));
 }
 @Test public void unsupportedFinalToolIsRejectedRatherThanExecuted()throws Exception{
  AtomicInteger calls=new AtomicInteger();try{AgentCloud.decide("system",new JSONObject(),new JSONArray(),"image",new AtomicBoolean(),(b,s)->{calls.incrementAndGet();return reply(plan("shell"));});fail();}catch(java.io.IOException expected){}assertEquals(2,calls.get());
 }
 @Test public void missingAddressProducesSpecificQuestionBeforeGpuWork()throws Exception{
  JSONObject c=contract().put("physical_visit",true).put("visit_quote","زورونا في فرعنا").put("visit_text","زورونا في فرعنا").put("address_required",true).put("address_quote","اكتب مكان الموقع");String text="زورونا في فرعنا واكتب مكان الموقع";
  assertEquals("BRIEF_ADDRESS_REQUIRED",AgentExecutive.validateBrief(c,text,new JSONArray(),new JSONArray(),0));assertTrue(MotionCloud.briefQuestion("BRIEF_ADDRESS_REQUIRED").contains("عنوان الفرع"));
 }
 @Test public void addressFromAnotherExampleCannotBecomeCurrentFact()throws Exception{
  JSONObject c=contract().put("address","مدينة الاختبار، شارع المثال").put("address_evidence","العنوان: مدينة الاختبار، شارع المثال").put("address_source","source");
  try{AgentExecutive.validateBrief(c,"اكتب عنوان الفرع",new JSONArray().put(new JSONObject().put("status","unavailable").put("text","")),new JSONArray(),0);fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("العنوان"));}
 }
 @Test public void storefrontCannotSatisfyAnExplicitLogoRequest()throws Exception{
  JSONObject c=contract().put("opening_logo",true).put("opening_quote","الشعار أولًا").put("logo_index",0);JSONArray catalog=new JSONArray().put(new JSONObject().put("index",0).put("kind","place_photo"));
  try{AgentExecutive.validateBrief(c,"الشعار أولًا",new JSONArray(),catalog,1);fail();}catch(java.io.IOException expected){}
  c.put("logo_index",-1);assertEquals("BRIEF_LOGO_REQUIRED",AgentExecutive.validateBrief(c,"الشعار أولًا",new JSONArray(),catalog,1));
 }
 @Test public void sourceReaderDoesNotConfuseScriptsWithPageFacts()throws Exception{
  String text=AgentSources.text("<html><script>PRIVATE_FAKE_ADDRESS</script><style>PRIVATE_CSS</style><body><h1>عنوان الفرع</h1><p>مدينة الاختبار</p></body></html>");assertTrue(text.contains("عنوان الفرع"));assertFalse(text.contains("PRIVATE"));for(String url:new String[]{"file:///etc/passwd","http://127.0.0.1/","http://[::1]/","https://user:pass@example.com/"})try{AgentSources.url(url);fail(url);}catch(java.io.IOException expected){}
 }
 @Test public void onlyUserApprovedExamplesAreRetainedAndCanBeRevoked()throws Exception{
  JSONObject p=project();assertEquals(0,AgentExamples.all(context).length());AgentExamples.approve(context,p);assertEquals(1,AgentExamples.all(context).length());assertEquals(1,AgentExamples.relevant(context,"خلي الشعار أولًا").length());assertFalse(AgentExamples.relevant(context,"خلي الشعار أولًا").getJSONObject(0).has("project"));AgentExamples.reject(context,p.getString("id"));assertEquals(0,AgentExamples.all(context).length());
 }
 @Test public void disabledLearningAndCredentialsAreNotSavedAsExamples()throws Exception{
  JSONObject p=project();context.getSharedPreferences("local_agent",0).edit().putBoolean("learn",false).commit();AgentExamples.approve(context,p);assertEquals(0,AgentExamples.all(context).length());context.getSharedPreferences("local_agent",0).edit().putBoolean("learn",true).commit();p.getJSONObject("creativeRequest").put("text","token=PRIVATE_VALUE");AgentExamples.approve(context,p);assertEquals(0,AgentExamples.all(context).length());
 }
 @Test public void automaticRepairIsStrictlyBoundedToOneAndKeepsPriorVersion()throws Exception{
  JSONObject p=project();AtomicInteger runs=new AtomicInteger(),reviews=new AtomicInteger();JSONObject done=AgentExecutive.reviewedMotion(context,p,"compose",new AtomicBoolean(),(a,b)->{},v->{int n=runs.incrementAndGet();assertTrue(n<=2);v.put("result",new JSONObject().put("name","fixture_"+n+".mp4").put("mime","video/mp4"));return v;},v->{reviews.incrementAndGet();return new JSONObject().put("status","repair").put("issues",new JSONArray().put("الشعار ليس أول مرجع"));});
  assertEquals(2,runs.get());assertEquals(2,reviews.get());assertEquals(1,done.getJSONObject("agentReview").getInt("automaticRepairs"));assertEquals(1,done.getJSONArray("reviewVersions").length());assertEquals("fixture_1.mp4",done.getJSONArray("reviewVersions").getJSONObject(0).getString("name"));
 }
 @Test public void unavailableReviewDoesNotCauseAnotherRenderOrClaimSuccess()throws Exception{
  JSONObject p=project();AtomicInteger runs=new AtomicInteger();JSONObject done=AgentExecutive.reviewedMotion(context,p,"compose",new AtomicBoolean(),(a,b)->{},v->{runs.incrementAndGet();return v;},v->{throw new java.io.IOException("quota unavailable");});assertEquals(1,runs.get());assertEquals("uncertain",done.getJSONObject("agentReview").getString("status"));
 }
 @Test public void cancellationAfterReviewCannotLaunchRepair()throws Exception{
  JSONObject p=project();AtomicInteger runs=new AtomicInteger();AtomicBoolean stop=new AtomicBoolean();try{AgentExecutive.reviewedMotion(context,p,"compose",stop,(a,b)->{},v->{runs.incrementAndGet();return v;},v->{stop.set(true);return new JSONObject().put("status","repair").put("issues",new JSONArray().put("تصحيح"));});fail();}catch(java.util.concurrent.CancellationException expected){}assertEquals(1,runs.get());
 }
}
