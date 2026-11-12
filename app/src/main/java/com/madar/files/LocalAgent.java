package com.madar.files;
import android.content.Context;
import com.google.ai.edge.litertlm.*;
import org.json.*;
import okhttp3.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local language inference proposes a bounded tool call; existing app tools execute it. */
final class LocalAgent {
 static final long MODEL_BYTES=977184032L;
 static final String MODEL_SHA="2a72330b504a6596d53a2c00923c57173b10f1b83e6a541213ffec08d1fc5de8";
 static final String MODEL_URL="https://huggingface.co/litert-community/Qwen3-1.7B/resolve/79bf0667b32ae025b972e5fe555fe72ca6e8816c/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm";
 private volatile Conversation conversation;private volatile Call download;
 private final Object inferenceLock=new Object();private Engine warmEngine;private long verifiedModified=-1;private String verifiedPath="";private volatile boolean closed;private long cloudBackoff;private int cloudFailures;
 volatile Progress status=(p,m)->{};
 synchronized Engine engine(Context c,File file)throws Exception{
  if(closed)throw new IOException("أُغلقت جلسة المساعد");
  if(warmEngine!=null&&verifiedPath.equals(file.getAbsolutePath())&&verifiedModified==file.lastModified()&&file.length()==MODEL_BYTES)return warmEngine;
  if(warmEngine!=null){warmEngine.close();warmEngine=null;}
  status.update(-1,"المساعد المحلي · التحقق من النموذج لأول تشغيل");if(!digest(file).equals(MODEL_SHA)){file.delete();throw new IOException("نموذج المساعد تالف؛ أعد تنزيله");}
  status.update(-1,"المساعد المحلي · تحميل المحرك؛ يُعاد استخدامه للأسئلة التالية");Engine value=new Engine(new EngineConfig(file.getAbsolutePath(),new Backend.CPU(),null,null,8192,null,c.getCacheDir().getAbsolutePath()));
  try{value.initialize();}catch(Exception|LinkageError failure){value.close();throw failure;}warmEngine=value;verifiedPath=file.getAbsolutePath();verifiedModified=file.lastModified();return value;
 }
 void close(){closed=true;cancel();Thread release=new Thread(()->{synchronized(inferenceLock){synchronized(this){if(warmEngine!=null){warmEngine.close();warmEngine=null;}}}},"madar-agent-release");release.setDaemon(true);release.start();}
 JSONObject provider(JSONObject plan,String name)throws Exception{return plan.put("provider",name);}
 /** Text-only plan from Groq with the compact local prompt (fits the free per-minute token limit). */
 JSONObject groq(Context c,String user,JSONObject source,JSONArray previous,AtomicBoolean cancel)throws Exception{
  boolean think=needsThought(user);status.update(-1,think?"Groq · يفكر في طلبك":"Groq · فهم طلبك");
  JSONObject plain=new JSONObject(source.toString());plain.remove("_visual");String kind=plain.optString("kind");
  String prompt;try(InputStream in=c.getAssets().open("agent-local-prompt.txt");ByteArrayOutputStream out=new ByteArrayOutputStream()){FilesUtil.copy(in,out);prompt=out.toString("UTF-8");}prompt+=understanding(c);
  // Groq's free tier refuses a request over about 8000 tokens (413): the instructions take most of it, so the newest
  // turns that fit are sent, and a long file description (a film's scenes) is shortened only when it has to be.
  JSONObject schema=AgentTools.schema(kind);JSONArray tools=new JSONArray(AgentTools.tools(kind)),all=history(previous);JSONObject preferences=memory(c);
  int fixed=tokens(prompt)+tokens(schema.toString())+tokens(tools.toString())+tokens(preferences.toString())+tokens(user)+120;
  JSONObject shown=fixed+tokens(plain.toString())>REQUEST_TOKENS-500?compact(plain,300):plain;
  JSONArray recent=fitting(all,REQUEST_TOKENS-fixed-tokens(shown.toString()));
  JSONObject data=new JSONObject().put("source",shown).put("availableTools",tools).put("preferences",preferences).put("conversation",recent).put("userRequest",user);
  JSONObject answer;
  try{answer=GroqClient.json(c,prompt,data.toString(),schema,think?3072:2048,think?"medium":"low",cancel);}
  catch(GroqClient.TooLarge large){
   // Still too large by Groq's own count: the request alone, with the file described briefly.
   data.put("source",compact(plain,160)).put("conversation",fitting(all,300));
   answer=GroqClient.json(c,prompt,data.toString(),schema,2048,"low",cancel);
  }
  return provider(AgentTools.validate(answer,kind),"groq");
 }
 /** What one request may hold, by the over-estimate below (Groq's free limit is about 8000 tokens a request). */
 static final int REQUEST_TOKENS=7600;
 /** A safe over-estimate of tokens: Arabic letters cost about one token for two, everything else one for three and a half. */
 static int tokens(String text){int arabic=0;for(int i=0;i<text.length();i++){char ch=text.charAt(i);if(ch>=0x600&&ch<=0x6ff)arabic++;}return (int)Math.ceil(arabic/2.0+(text.length()-arabic)/3.5);}
 /** The newest turns that fit in the room left (at most eight, each cut to 400 characters); older ones are left out first. */
 static JSONArray fitting(JSONArray turns,int room)throws JSONException{
  java.util.ArrayList<JSONObject> kept=new java.util.ArrayList<>();int used=0;
  for(int i=turns.length()-1;i>=0&&kept.size()<8;i--){JSONObject turn=turns.getJSONObject(i);String text=turn.optString("text");if(text.length()>400)text=text.substring(0,400)+"…";
   JSONObject cut=new JSONObject().put("role",turn.optString("role")).put("text",text);int cost=tokens(cut.toString());if(used+cost>room)break;used+=cost;kept.add(0,cut);}
  JSONArray out=new JSONArray();for(JSONObject turn:kept)out.put(turn);return out;
 }
 /** The selected file's description with long texts cut and long lists shortened, when the full one does not fit. */
 static JSONObject compact(JSONObject source,int each)throws JSONException{
  JSONObject out=new JSONObject();
  for(java.util.Iterator<String> names=source.keys();names.hasNext();){String name=names.next();Object value=source.get(name);
   if(value instanceof String&&((String)value).length()>each)value=((String)value).substring(0,each)+"…";
   else if(value instanceof JSONObject)value=compact((JSONObject)value,each);
   else if(value instanceof JSONArray&&value.toString().length()>each*4){JSONArray list=(JSONArray)value,cut=new JSONArray();for(int i=0;i<list.length()&&cut.toString().length()<each*4;i++){Object item=list.get(i);cut.put(item instanceof JSONObject?compact((JSONObject)item,each):item instanceof String&&((String)item).length()>each?((String)item).substring(0,each)+"…":item);}value=cut;}
   out.put(name,value);}
  return out;
 }
 static boolean needsThought(String user){
  String t=user==null?"":user.replaceAll("[\\u064b-\\u065f\\u0670\\u0640]","").replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ة','ه').replace('ى','ي');
  if(t.length()>220)return true;
  if(t.matches("(?s).*(?:وش رايك|ايش رايك|رايك|ايش الافضل|وش الافضل|ايهم افضل|تنصحني|تنصح|اقترح|اقتراح|قارن|الفرق بين|ليش|ليه |لماذا|كيف اسوي|كيف اخلي|خطه|فكر|حلل|راجع|قيّم|قيم لي|وش ناقص|ايش ناقص|وش المشكله|ايش المشكله).*"))return true;
  int steps=0;java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:^|\\s)(?:ثم|وبعدين|بعدين|بعدها|وبعد كذا)(?:\\s|$)").matcher(t);while(m.find())steps++;
  return steps>=2;
 }

 /** How the online planners (Gemini, Groq) read dialect, ideas and vague requests; the small on-device model keeps its short prompt. */
 static String understanding(Context c){try(InputStream in=c.getAssets().open("agent-understanding.txt");ByteArrayOutputStream out=new ByteArrayOutputStream()){FilesUtil.copy(in,out);return out.toString("UTF-8");}catch(IOException missing){return "";}}
 interface Progress {void update(int percent,String message);}
 static File model(Context c){return new File(c.getFilesDir(),"agent-qwen3-1.7b.litertlm");}
 static boolean ready(Context c){return model(c).isFile()&&model(c).length()==MODEL_BYTES;}
 static String digest(File file)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new BufferedInputStream(new FileInputStream(file))){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}StringBuilder hex=new StringBuilder();for(byte b:md.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();}
 void cancel(){Call call=download;if(call!=null)call.cancel();Conversation chat=conversation;if(chat!=null)try{chat.cancelProcess();}catch(Exception ignored){}}
 void install(Context c,AtomicBoolean cancel,Progress progress)throws Exception{
  File target=model(c);if(ready(c)&&digest(target).equals(MODEL_SHA)){progress.update(100,"المساعد المحلي جاهز");return;}
  if(c.getFilesDir().getUsableSpace()<MODEL_BYTES+128L*1024*1024)throw new IOException("يحتاج نموذج المساعد إلى 1100 ميغابايت مساحة متاحة");
  File part=new File(target.getPath()+".partial");boolean saved=false;OkHttpClient client=new OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).callTimeout(15,TimeUnit.MINUTES).build();
  try{download=client.newCall(new Request.Builder().url(MODEL_URL).build());try(Response response=download.execute()){
    if(!response.isSuccessful()||response.body()==null)throw new IOException("تعذر تنزيل نموذج المساعد · HTTP "+response.code());long bytes=0;int previous=-1;try(InputStream in=response.body().byteStream();OutputStream out=new BufferedOutputStream(new FileOutputStream(part))){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){ColabProbe.check(cancel);bytes+=n;if(bytes>MODEL_BYTES)throw new IOException("حجم نموذج المساعد غير مطابق");out.write(b,0,n);int percent=(int)(100*bytes/MODEL_BYTES);if(percent!=previous){progress.update(percent,"تنزيل نموذج المساعد · "+percent+"٪");previous=percent;}}}
    ColabProbe.check(cancel);if(bytes!=MODEL_BYTES||!digest(part).equals(MODEL_SHA))throw new IOException("فشل التحقق من سلامة نموذج المساعد");if(!part.renameTo(target))throw new IOException("تعذر حفظ نموذج المساعد");saved=true;progress.update(100,"المساعد جاهز؛ يعمل دون اتصال بعد التنزيل");
   }}finally{download=null;if(!saved)part.delete();client.dispatcher().executorService().shutdown();client.connectionPool().evictAll();}
 }
 JSONObject plan(Context c,String user,JSONObject source,AtomicBoolean cancel)throws Exception {return plan(c,user,source,new JSONArray(),cancel);}
 static JSONArray history(JSONArray input)throws Exception{
  JSONArray out=new JSONArray();if(input==null)return out;if(input.length()>20)throw new IOException("اختصر سياق المحادثة");
  int length=0;for(int i=0;i<input.length();i++){JSONObject turn=input.getJSONObject(i);String role=turn.getString("role"),text=turn.getString("text");length+=text.length();if(!Arrays.asList("user","assistant").contains(role)||text.length()>1000||length>12000)throw new IOException("سياق المساعد غير صالح");out.put(new JSONObject().put("role",role).put("text",text));}return out;
 }
 JSONObject plan(Context c,String user,JSONObject source,JSONArray previous,AtomicBoolean cancel)throws Exception {
  if(user.trim().isEmpty()||user.length()>6000)throw new IOException("اكتب طلبًا واضحًا لا يتجاوز 6000 حرف");
  ColabProbe.check(cancel);JSONObject direct=AgentIntents.resolve(user,source,previous);
  if(direct!=null)return provider(AgentTools.validate(direct,source.optString("kind")),"direct");
  // Gemini just timed out and the on-device model is ready: answer locally instead of waiting again.
  boolean online=!c.getSharedPreferences("local_agent",0).getBoolean("offline",false);
  // Gemini just failed: the owner's free Groq key answers straight away instead of another long wait.
  if(online&&GroqClient.configured(c)&&AgentDiagnostics.recentlyDown(c))return groq(c,user,source,previous,cancel);
  if(online&&!(AgentDiagnostics.recentlyDown(c)&&ready(c))){
   status.update(-1,"Gemini · فهم الطلب والمرفقات ومراجعة الخطة");
   try{return provider(AgentCloud.plan(c,user,source,previous,cancel),"gemini");}
   catch(java.util.concurrent.CancellationException stopped){throw stopped;}
   catch(Exception failed){String code=AgentDiagnostics.code(failed);if(!GroqClient.configured(c)||!code.startsWith("GEMINI_")||code.equals("GEMINI_RESPONSE_BLOCKED"))throw failed;ColabProbe.check(cancel);return groq(c,user,source,previous,cancel);}
  }
  if(direct!=null)return provider(AgentTools.validate(direct,source.optString("kind")),"direct");source=new JSONObject(source.toString());source.remove("_visual");
  if(!ready(c))throw new IOException("هذا الطلب المركب يحتاج اتصال Gemini أو تنزيل النموذج المحلي؛ الأوامر المباشرة تعمل فورًا");
  File file=model(c);ColabProbe.check(cancel);
  String prompt;try(InputStream in=c.getAssets().open("agent-local-prompt.txt");ByteArrayOutputStream out=new ByteArrayOutputStream()){FilesUtil.copy(in,out);prompt=out.toString("UTF-8");}
  String kind=source.optString("kind");JSONObject data=new JSONObject().put("source",source).put("availableTools",new JSONArray(AgentTools.tools(kind))).put("preferences",memory(c)).put("conversation",history(previous)).put("userRequest",user);
  synchronized(inferenceLock){Engine engine=engine(c,file);ColabProbe.check(cancel);status.update(-1,"المساعد المحلي · فهم طلبك");
   try(Conversation chat=engine.createConversation(new ConversationConfig(Contents.Companion.of(prompt),Collections.emptyList(),Collections.emptyList(),new SamplerConfig(1,1.0,0.0,0),false,Collections.emptyList(),Collections.emptyMap(),null,false,768,new ThinkingConfig(false),true))){
    conversation=chat;ColabProbe.check(cancel);Message result=chat.sendMessage(Message.Companion.user(data.toString()),Collections.emptyMap(),null,null,null,768,new ThinkingConfig(false),ResponseFormat.Companion.json(AgentTools.schema(kind).toString()));ColabProbe.check(cancel);StringBuilder text=new StringBuilder();for(Content content:result.getContents().getContents())if(content instanceof Content.Text)text.append(((Content.Text)content).getText());
    if(text.length()>8000)throw new IOException("رد المساعد غير مكتمل؛ اختصر الطلب");JSONObject plan=AgentTools.validate(new JSONObject(text.toString()),kind);if(c.getSharedPreferences("local_agent",0).getBoolean("learn",true)){JSONObject saved=memory(c).optJSONObject(kind+":"+plan.getString("tool"));if(saved!=null){Set<String> explicit=new HashSet<>();JSONArray fields=plan.getJSONArray("fields");for(int i=0;i<fields.length();i++)explicit.add(fields.getJSONObject(i).getString("name"));for(Iterator<String> keys=saved.keys();keys.hasNext();){String key=keys.next();if(!explicit.contains(key))fields.put(new JSONObject().put("name",key).put("value",saved.getString(key)));}}}return provider(AgentTools.validate(plan,kind),"local");
   }finally{conversation=null;}
  }
 }
 static JSONObject memory(Context c){try{return new JSONObject(c.getSharedPreferences("local_agent",0).getString("preferences","{}"));}catch(Exception e){return new JSONObject();}}
 static void remember(Context c,String kind,String tool,JSONObject options)throws Exception {if(!AgentTools.tools(kind).contains(tool))return;JSONObject preferences=memory(c),values=new JSONObject();for(String key:new String[]{"format","quality","ocrLanguage","musicOutput","musicMode","translationMode","target","geminiReview"})if(options.has(key)&&options.optString(key).length()<40)values.put(key,options.optString(key));preferences.put(kind+":"+tool,values);c.getSharedPreferences("local_agent",0).edit().putString("preferences",preferences.toString()).apply();}
 static void forget(Context c){AgentExamples.forget(c);c.getSharedPreferences("local_agent",0).edit().clear().putBoolean("learn",false).apply();}
}
