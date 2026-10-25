package com.madar.files;

import android.content.Context;
import android.util.Base64;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;
import com.google.firebase.ai.FirebaseAI;
import com.google.firebase.ai.java.GenerativeModelFutures;
import com.google.firebase.ai.type.*;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One explicit Google transport per request; never silently switches billing accounts. */
final class GeminiClient {
 private static FirebaseApp managed;
 private static void check(AtomicBoolean cancel){if(cancel.get()||Thread.currentThread().isInterrupted())throw new CancellationException();}
 static boolean configured(Context c){FirebaseOptions o=FirebaseOptions.fromResource(c);return o!=null&&o.getProjectId()!=null&&!o.getProjectId().isEmpty();}
 static synchronized FirebaseApp managed(Context c)throws IOException {
  if(managed!=null)return managed;
  if(!configured(c))throw new IOException("الربط المباشر لم يُفعّل لهذه النسخة؛ استخدم مفتاحك المحفوظ أو إعداد خدمة الترجمة");
  FirebaseApp app=FirebaseApp.initializeApp(c.getApplicationContext());
  if(app==null)throw new IOException("تعذر تهيئة ربط Gemini");
  FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance());
  managed=app;return app;
 }
 static JSONObject generate(Context c,JSONObject body,AtomicBoolean cancel)throws Exception {
  check(cancel);String model=TranslationSettings.model(c),key=TranslationSettings.credential(c);
  if(!key.isEmpty())return OnlineTranslation.post(model,key,body,cancel);
  if(!model.matches("gemini-[a-zA-Z0-9.\\-]{1,80}"))throw new IOException("اسم نموذج غير صالح");
  GenerationConfig.Builder config=new GenerationConfig.Builder();JSONObject generation=body.getJSONObject("generationConfig");
  config.responseMimeType="application/json";config.responseSchema=schema(generation.getJSONObject("responseSchema"));config.maxOutputTokens=generation.getInt("maxOutputTokens");
  Content system=content(body.getJSONObject("systemInstruction"));
  GenerativeModelFutures client=GenerativeModelFutures.from(FirebaseAI.getInstance(managed(c),GenerativeBackend.googleAI()).generativeModel(model,config.build(),null,null,null,system,new RequestOptions(120000L)));
  JSONArray inputs=body.getJSONArray("contents");if(inputs.length()!=1)throw new IOException("عدد رسائل الترجمة غير صالح");
  ListenableFuture<GenerateContentResponse> future=client.generateContent(content(inputs.getJSONObject(0)));
  try{
   long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(125);
   while(true){
    check(cancel);if(System.nanoTime()>until)throw new IOException("انتهت مهلة Gemini؛ لم تُحفظ استجابة ناقصة");
    try{
     GenerateContentResponse response=future.get(150,TimeUnit.MILLISECONDS);check(cancel);
     if(response.getCandidates().isEmpty()||!FinishReason.STOP.equals(response.getCandidates().get(0).getFinishReason())||response.getText()==null)throw new IOException("استجابة Gemini غير مكتملة؛ لم يُعتمد الناتج الناقص");
     if(response.getText().length()>2000000)throw new IOException("استجابة الخدمة أكبر من الحد");
     return new JSONObject().put("candidates",new JSONArray().put(new JSONObject().put("finishReason","STOP").put("content",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",response.getText()))))));
    }catch(TimeoutException pending){/* Check cancellation without blocking the worker indefinitely. */}
   }
  }catch(ExecutionException failed){check(cancel);throw failure(failed.getCause());}
  finally{future.cancel(true);}
 }
 static IOException failure(Throwable cause){
  StringBuilder detail=new StringBuilder();Throwable next=cause;for(int i=0;i<8&&next!=null;i++){detail.append(next.toString()).append(' ');next=next.getCause();}String diagnostic=detail.toString().toLowerCase(java.util.Locale.ROOT);
  if(diagnostic.contains("429")||diagnostic.contains("quota")||diagnostic.contains("resource_exhausted")||diagnostic.contains("toomanyrequests"))return new IOException("Gemini: بلغ الحساب حد الطلبات أو الحصة المتاحة. أعد التشغيل بنفس الخيارات بعد تجدد الحصة؛ تُستعاد أجزاء الاستماع المكتملة إن وجدت. لا تحتاج إعادة إعداد Firebase.");
  if(diagnostic.contains("app check")||diagnostic.contains("appcheck")||diagnostic.contains("attestation"))return new IOException("Gemini: تعذر توثيق التطبيق عبر App Check؛ راجع تسجيل Play Integrity وتوقيع التطبيق.");
  if(diagnostic.contains("403")||diagnostic.contains("permission_denied"))return new IOException("Gemini: رفضت الخدمة صلاحية هذا الطلب. راجع صلاحيات المشروع والخدمة.");
  if(diagnostic.contains("timeout")||diagnostic.contains("network")||diagnostic.contains("unknownhost"))return new IOException("Gemini: تعذر الاتصال بالخدمة أو انتهت مهلتها؛ أعد التشغيل لاستعادة الأجزاء المكتملة.");
  return new IOException("تعذر إكمال طلب Gemini. احتُفظ بالأجزاء المكتملة. رمز التشخيص: "+(cause==null?"UNKNOWN":cause.getClass().getSimpleName()));
 }
 static Content content(JSONObject input)throws Exception {
  Content.Builder out=new Content.Builder();JSONArray parts=input.getJSONArray("parts");
  for(int i=0;i<parts.length();i++){JSONObject part=parts.getJSONObject(i);if(part.has("text"))out.addText(part.getString("text"));else if(part.has("inlineData")){JSONObject audio=part.getJSONObject("inlineData");String mime=audio.getString("mimeType");if(!Arrays.asList("audio/wav","image/jpeg","image/png").contains(mime))throw new IOException("صيغة وسائط غير مدعومة");byte[] bytes=Base64.decode(audio.getString("data"),Base64.NO_WRAP);if(bytes.length>16*1024*1024)throw new IOException("الوسائط أكبر من حد الطلب");out.addInlineData(bytes,mime);}else throw new IOException("محتوى طلب غير مدعوم");}
  return out.build();
 }
 static Schema schema(JSONObject input)throws Exception {
  switch(input.getString("type")){
   // Firebase 18 exposes a JVM method named boolean, a Java keyword.
   // Call the verified no-argument SDK factory through reflection.
   case "BOOLEAN":return (Schema)Schema.class.getMethod("boolean").invoke(null);
   case "STRING":return Schema.str();case "NUMBER":return Schema.numDouble();case "INTEGER":return Schema.numInt();
   case "ARRAY":return Schema.array(schema(input.getJSONObject("items")));
   case "OBJECT":
    JSONObject properties=input.getJSONObject("properties");Map<String,Schema> fields=new LinkedHashMap<>();List<String> optional=new ArrayList<>();Set<String> required=new HashSet<>();JSONArray list=input.optJSONArray("required");if(list!=null)for(int i=0;i<list.length();i++)required.add(list.getString(i));
    for(Iterator<String> keys=properties.keys();keys.hasNext();){String name=keys.next();fields.put(name,schema(properties.getJSONObject(name)));if(!required.contains(name))optional.add(name);}
    return Schema.obj(fields,optional);
   default:throw new IOException("نوع بنية استجابة غير مدعوم");
  }
 }
}
