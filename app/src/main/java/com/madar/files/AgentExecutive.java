package com.madar.files;
import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Grounded interpretation and bounded review. Never trains weights or executes generated code. */
final class AgentExecutive {
 interface Transport{JSONObject generate(JSONObject body,AtomicBoolean stop)throws Exception;}
 static final long PLAN_TIMEOUT_MILLIS=2*GeminiClient.REQUEST_DEADLINE_MILLIS+45000;
 interface Progress{void stage(String message)throws Exception;}
 static JSONObject invoke(Context c,String system,JSONObject input,JSONArray visual,JSONObject schema,int tokens,AtomicBoolean cancel)throws Exception{
  String stage=AgentDiagnostics.stage(input);long began=android.os.SystemClock.elapsedRealtime();AgentDiagnostics.record(c,stage,"running",began,null);
  try{JSONObject answer=invoke(system,input,visual,schema,tokens,cancel,(b,s)->GeminiClient.generate(c,b,s));AgentDiagnostics.record(c,stage,"done",began,null);return answer;}
  catch(CancellationException stopped){AgentDiagnostics.record(c,stage,"cancelled",began,stopped);throw stopped;}
  catch(Exception error){AgentDiagnostics.Failure failure=AgentDiagnostics.failure(error);AgentDiagnostics.record(c,stage,"failed",began,failure);throw failure;}
 }
 static JSONObject invoke(String system,JSONObject input,JSONArray visual,JSONObject schema,int tokens,AtomicBoolean cancel,Transport transport)throws Exception{
  JSONArray parts=new JSONArray().put(new JSONObject().put("text",input.toString()));if(visual!=null)for(int i=0;i<visual.length();i++)parts.put(visual.getJSONObject(i));
  JSONObject body=new JSONObject().put("systemInstruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",system)))).put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",parts))).put("generationConfig",new JSONObject().put("temperature",0).put("maxOutputTokens",tokens).put("responseMimeType","application/json").put("responseSchema",AgentCloud.schema(schema)));
  AtomicBoolean stop=new AtomicBoolean();ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();timer.scheduleAtFixedRate(()->{if(cancel.get())stop.set(true);},0,100,TimeUnit.MILLISECONDS);
  try{ColabProbe.check(cancel);JSONObject response=transport.generate(body,stop);ColabProbe.check(cancel);if(stop.get())throw AgentDiagnostics.failure(new CancellationException());JSONObject candidate=response.getJSONArray("candidates").getJSONObject(0);if(candidate.has("finishReason")&&!candidate.optString("finishReason").equals("STOP"))throw AgentDiagnostics.responseFailure(candidate.optString("finishReason"));JSONArray content=candidate.getJSONObject("content").getJSONArray("parts");StringBuilder text=new StringBuilder();for(int i=0;i<content.length();i++)text.append(content.getJSONObject(i).optString("text"));if(text.length()>24000)throw new IOException("رد المساعد أطول من الحد");return new JSONObject(text.toString());}
  catch(CancellationException stopped){ColabProbe.check(cancel);throw AgentDiagnostics.failure(stopped);}finally{timer.shutdownNow();}
 }
 static String normalized(String value){return value.replaceAll("[\\u064b-\\u065f\\u0670\\u0640]","").replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ة','ه').replace('ى','ي').replaceAll("\\s+"," ").trim().toLowerCase(Locale.ROOT);}
 static boolean quoted(String quote,String text){return quote.length()>=3&&normalized(text).contains(normalized(quote));}
 static String validateBrief(JSONObject contract,String client,JSONArray sources,JSONArray catalog,int imageCount)throws Exception{
  if(catalog.length()!=imageCount)throw new IOException("فهرس الصور غير مكتمل");Set<Integer> indices=new HashSet<>();Set<Integer> logos=new HashSet<>();for(int i=0;i<catalog.length();i++){JSONObject item=catalog.getJSONObject(i);int index=item.getInt("index");if(index<0||index>=imageCount||!indices.add(index))throw new IOException("مرجع صورة غير صالح");if(item.optString("kind").equals("brand_logo"))logos.add(index);}
  for(String key:new String[]{"rules","must_include","forbidden_claims","omit_from_copy"}){JSONArray list=contract.getJSONArray(key);if(list.length()>12)throw new IOException("شروط الموشن أكثر من الحد");for(int i=0;i<list.length();i++)if(!quoted(list.getJSONObject(i).getString("evidence"),client))throw new IOException("تفسير الطلب استند إلى معلومة لم تذكرها");}
  if(contract.getBoolean("opening_logo")&&!quoted(contract.getString("opening_quote"),client)||contract.getBoolean("physical_visit")&&!quoted(contract.getString("visit_quote"),client)||contract.getBoolean("address_required")&&!quoted(contract.getString("address_quote"),client))throw new IOException("شروط الموشن غير مسندة إلى طلبك");
  int logo=contract.getInt("logo_index");if(logo!=-1&&!logos.contains(logo))throw new IOException("المرجع المحدد ليس شعارًا موثوقًا");
  String address=contract.getString("address"),evidence=contract.getString("address_evidence"),origin=contract.getString("address_source"),ground=client;if(origin.equals("source")){StringBuilder text=new StringBuilder();for(int i=0;i<sources.length();i++)text.append(sources.getJSONObject(i).optString("text")).append('\n');ground=text.toString();}
  if(!address.isEmpty()&&(!Arrays.asList("client","source").contains(origin)||!quoted(evidence,ground)||!quoted(address,evidence)||address.matches("(?s).*(https?://|اكتب|ضع العنوان|مكان الموقع|لا نملك|ما عندنا).*")))throw new IOException("العنوان المقترح لم تثبته البيانات؛ أرسل عنوان الفرع الصحيح");
  if(contract.getBoolean("opening_logo")&&logo<0)return "BRIEF_LOGO_REQUIRED";
  if(contract.getBoolean("address_required")&&address.isEmpty())return "BRIEF_ADDRESS_REQUIRED";
  return "";
 }
 /** The logo the user picked in chat is authoritative: mark it as the brand logo the interpretation must open with. */
 static void trustChosenLogo(JSONObject brief,JSONObject contract,JSONArray catalog)throws Exception{
  JSONObject local=brief.optJSONObject("contract");if(local==null||local.optString("logoAsset").isEmpty())return;
  JSONArray images=brief.getJSONArray("images");int chosen=-1;for(int i=0;i<images.length();i++)if(images.getString(i).equals(local.getString("logoAsset")))chosen=i;if(chosen<0)return;
  for(int i=0;i<catalog.length();i++){JSONObject item=catalog.getJSONObject(i);if(item.getInt("index")==chosen)item.put("kind","brand_logo");else if(item.optString("kind").equals("brand_logo"))item.put("kind","other");}
  if(contract.optBoolean("opening_logo")||local.optBoolean("logoFirst"))contract.put("logo_index",chosen);
 }
 /**
  * When Gemini cannot be reached (network, timeout, quota, model or permission failures)
  * the chat's own brief contract is enough to plan the film: the job continues on Colab
  * with the local contract and its post-generation checks instead of failing outright.
  * Returns false when there is no local contract or the failure is not a Gemini outage.
  */
 static boolean localFallback(JSONObject project,String code)throws Exception{
  JSONObject brief=project.optJSONObject("creativeRequest");
  if(brief==null||brief.optJSONObject("contract")==null||code==null||!code.startsWith("GEMINI_"))return false;
  brief.put("executiveVerified",false).put("executiveFallback",code);brief.remove("clientContract");project.remove("preflightPending");
  return true;
 }
 static void prepareMotion(Context c,JSONObject project,AtomicBoolean cancel)throws Exception{prepareMotion(c,project,cancel,m->{});}
 static void prepareMotion(Context c,JSONObject project,AtomicBoolean cancel,Progress progress)throws Exception{
  JSONObject brief=project.getJSONObject("creativeRequest");String prompt;try(InputStream in=c.getAssets().open("agent-motion-executive.txt");ByteArrayOutputStream out=new ByteArrayOutputStream()){FilesUtil.copy(in,out);prompt=out.toString("UTF-8");}
  JSONObject schema;try(InputStream in=c.getAssets().open("agent-motion-contract.json");ByteArrayOutputStream out=new ByteArrayOutputStream()){FilesUtil.copy(in,out);schema=new JSONObject(out.toString("UTF-8"));}
  prepareBrief(c,project,cancel,progress,prompt,schema,(body,stop)->GeminiClient.generate(c,body,stop));
 }
 static void prepareBrief(Context c,JSONObject project,AtomicBoolean cancel,Progress progress,String prompt,JSONObject schema,Transport transport)throws Exception{
  JSONObject brief=project.getJSONObject("creativeRequest");project.put("preflightPending",true);MotionProject.save(c,project);
  String key=MotionProject.hashText(brief.getString("text")+"\n"+brief.getJSONArray("images")+"\n"+schema+"\n"+TranslationSettings.model(c));
  boolean cached=key.equals(brief.optString("executiveDraftKey"))&&brief.optJSONObject("executiveDraft")!=null&&System.currentTimeMillis()-brief.optLong("executiveDraftAt")<10*60*1000;
  progress.stage(cached?"أستكمل مراجعة التفسير المحفوظ؛ لن أعيد مرحلة الفهم المكتملة":"قراءة المصادر والصور وفهم طلبك عبر Gemini");
  JSONArray sources=cached?brief.getJSONArray("sourceReads"):AgentSources.read(brief.getString("text"),cancel),images=brief.getJSONArray("images"),visual=new JSONArray();
  for(int i=0;i<images.length();i++){visual.put(new JSONObject().put("text","Owned image index "+i));JSONArray sample=AgentVision.sample(c,MotionProject.asset(c,project.getString("id"),images.getString(i)),"image");for(int n=0;n<sample.length();n++)visual.put(sample.get(n));}
  JSONObject input=new JSONObject().put("client_brief",brief.getString("text")).put("page_reads",sources).put("image_count",images.length()).put("previous_contract",brief.optJSONObject("previous")==null?JSONObject.NULL:brief.getJSONObject("previous").optJSONObject("clientContract")).put("saved_preferences",LocalAgent.memory(c)).put("teacher_examples",AgentExamples.relevant(c,brief.getString("text")));
  JSONObject interpreted=cached?brief.getJSONObject("executiveDraft"):measured(c,input,()->invoke(prompt,input,visual,schema,4096,cancel,transport));
  brief.put("sourceReads",sources).put("executiveDraft",interpreted).put("executiveDraftKey",key).put("executiveDraftAt",System.currentTimeMillis());MotionProject.save(c,project);ColabProbe.check(cancel);
  if(brief.optJSONObject("contract")==null)progress.stage("Gemini يراجع التفسير المحفوظ قبل تشغيل GPU");input.put("untrusted_interpretation",interpreted).put("review_task","Independently re-read the actual client request and images. Correct omissions, negation reversals, invented facts and false claims of reading a URL. Return the complete repaired interpretation. No execution has started.");
  // The chat's own contract already fixes omissions and negations deterministically, so the second
  // Gemini pass is skipped then; without it, a review that times out keeps the validated first reading.
  JSONObject reviewed;
  if(brief.optJSONObject("contract")!=null)reviewed=interpreted;
  else try{reviewed=measured(c,input,()->invoke(prompt,input,visual,schema,4096,cancel,transport));}
  catch(CancellationException stopped){throw stopped;}
  catch(Exception slow){if(!AgentDiagnostics.code(slow).matches("GEMINI_(TIMEOUT|NETWORK|REQUEST_FAILED|UNAVAILABLE)"))throw slow;reviewed=interpreted;brief.put("executiveReviewSkipped",AgentDiagnostics.code(slow));}
  JSONObject contract=reviewed.getJSONObject("contract");JSONArray catalog=reviewed.getJSONArray("image_catalog");trustChosenLogo(brief,contract,catalog);String question=validateBrief(contract,brief.getString("text"),sources,catalog,images.length());
  brief.put("clientContract",contract).put("referenceFacts",catalog).put("executiveVerified",true);project.remove("preflightPending");MotionProject.save(c,project);
  if(contract.optBoolean("needs_information")){String needed=contract.optString("question");if(needed.isEmpty()||needed.length()>250||needed.matches("(?is).*(password|api.?key|token|كلمة مرور|مفتاح API).*"))throw new IOException("المعلومة الناقصة لم تتحدد بشكل صالح");throw new MotionCloud.Clarification(needed);}
  if(!question.isEmpty())throw new MotionCloud.Clarification(MotionCloud.briefQuestion(question));
 }
 interface Request{JSONObject run()throws Exception;}
 static JSONObject measured(Context c,JSONObject input,Request request)throws Exception{
  String stage=AgentDiagnostics.stage(input);long began=android.os.SystemClock.elapsedRealtime();AgentDiagnostics.record(c,stage,"running",began,null);
  try{JSONObject result=request.run();AgentDiagnostics.record(c,stage,"done",began,null);return result;}
  catch(CancellationException stopped){AgentDiagnostics.record(c,stage,"cancelled",began,stopped);throw stopped;}
  catch(Exception error){AgentDiagnostics.Failure failure=AgentDiagnostics.failure(error);AgentDiagnostics.record(c,stage,"failed",began,failure);throw failure;}
 }
 static String sourceSummary(JSONObject project){
  JSONObject brief=project.optJSONObject("creativeRequest");if(brief==null)return "";JSONArray reads=brief.optJSONArray("sourceReads");if(reads==null||reads.length()==0)return "";int partial=0;for(int i=0;i<reads.length();i++)if(reads.optJSONObject(i)!=null&&reads.optJSONObject(i).optString("status").equals("partial_text"))partial++;
  JSONObject contract=brief.optJSONObject("clientContract");boolean address=contract!=null&&!contract.optString("address").isEmpty();return (partial==0?"لم أستطع قراءة معلومات موثوقة من الروابط.":"استخرجت نصًا جزئيًا من "+partial+" مصدر؛ لم أتحقق من كامل الموقع.")+(address?" عنوان الفرع مسند إلى النص المتاح.":"");
 }
 static JSONObject executeMotion(Context c,JSONObject original,String mode,ColabCredentials.Grant grant,AtomicBoolean cancel,MotionCloud.Progress progress)throws Exception{
  return reviewedMotion(c,original,mode,cancel,progress,p->MotionCloud.run(c,p,mode,grant,cancel,progress),p->reviewMotion(c,p,cancel));
 }
 interface Runner{JSONObject run(JSONObject project)throws Exception;}
 interface Reviewer{JSONObject review(JSONObject project)throws Exception;}
 static JSONObject reviewedMotion(Context c,JSONObject original,String mode,AtomicBoolean cancel,MotionCloud.Progress progress,Runner runner,Reviewer reviewer)throws Exception{
  JSONObject project=runner.run(original);
  if(!mode.equals("compose")||project.optJSONObject("creativeRequest")==null||!project.getJSONObject("creativeRequest").optBoolean("executiveVerified"))return project;
  for(int attempt=0;attempt<2;attempt++){
   ColabProbe.check(cancel);progress.update(99,"Gemini يراجع النص والمراجع وعينات الفيديو مقابل طلبك");JSONObject review;
   try{review=reviewer.review(project);}catch(java.util.concurrent.CancellationException stopped){throw stopped;}catch(Exception unavailable){review=new JSONObject().put("status","uncertain").put("issues",new JSONArray().put("تعذرت المراجعة الذكية بعد التصدير؛ لا أعتبرها مراجعة ناجحة"));}
   project.put("agentReview",review.put("automaticRepairs",attempt));MotionProject.save(c,project);
   if(!review.getString("status").equals("repair")||review.getJSONArray("issues").length()==0||attempt==1)return project;
   ColabProbe.check(cancel);JSONObject prior=project.optJSONObject("result");if(prior!=null){JSONArray versions=project.optJSONArray("reviewVersions");if(versions==null)versions=new JSONArray();if(versions.length()<3)versions.put(prior);project.put("reviewVersions",versions);}
   project.getJSONObject("creativeRequest").put("reviewFeedback",review.getJSONArray("issues"));project.remove("motionCheckpoint");project.remove("motionTransfer");project.remove("result");project.put("ready",false);MotionProject.save(c,project);
   progress.update(20,"رصدت المراجعة مخالفة محددة؛ أصححها مرة واحدة مع حفظ النسخة السابقة");project=runner.run(project);
  }return project;
 }
 static JSONObject reviewMotion(Context c,JSONObject project,AtomicBoolean cancel)throws Exception{
  JSONObject contract=project.optJSONObject("creativeRequest")==null?null:project.getJSONObject("creativeRequest").optJSONObject("clientContract");if(contract==null)return new JSONObject().put("status","not_checked").put("issues",new JSONArray());
  JSONObject schema=new JSONObject("{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\",\"enum\":[\"pass\",\"repair\",\"uncertain\"]},\"issues\":{\"type\":\"array\",\"maxItems\":6,\"items\":{\"type\":\"string\"}}},\"required\":[\"status\",\"issues\"]}");
  JSONArray visual=new JSONArray();JSONObject result=project.optJSONObject("result");if(result!=null&&result.optString("mime").startsWith("video/"))visual=AgentVision.sample(c,FilesUtil.resolve(c,result.getString("name")),"video");
  JSONObject input=new JSONObject().put("contract",contract).put("scenes",project.getJSONArray("scenes")).put("duration",project.optDouble("duration")).put("voiceMode",project.getString("voiceMode")).put("source_reads",project.getJSONObject("creativeRequest").optJSONArray("sourceReads"));
  JSONObject review=invoke(c,"Review the actual generated project and the supplied video samples against the grounded client contract. Check explicit corrections, logo first, prohibited subject/claim relations, physical branch CTA and exact verified address, readable mobile typography, meaningful photo scale, and shot pacing. Do not treat production directions as ad copy. Samples do not establish unseen footage or audio. Return pass only for the checks supported by this evidence; uncertain for missing evidence. Return repair only for concrete visible/text violations, with concise actionable Arabic issues. Do not invent facts, street names, services or new client requirements. Never ask for another video generation just to improve an already-correct stylistic choice.",input,visual,schema,1200,cancel);
  if(!Arrays.asList("pass","repair","uncertain").contains(review.getString("status"))||review.getJSONArray("issues").length()>6)throw new IOException("مراجعة الناتج غير مكتملة");for(int i=0;i<review.getJSONArray("issues").length();i++)if(review.getJSONArray("issues").getString(i).length()>300)throw new IOException("مراجعة الناتج أطول من الحد");return review;
 }
}
