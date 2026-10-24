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
 JSONObject plan(Context c,String user,JSONObject source,AtomicBoolean cancel)throws Exception {
  if(user.trim().isEmpty()||user.length()>1000)throw new IOException("اكتب طلبًا واضحًا لا يتجاوز 1000 حرف");if(!ready(c))throw new IOException("نزّل نموذج المساعد المحلي أولًا");
  File file=model(c);if(!digest(file).equals(MODEL_SHA))throw new IOException("نموذج المساعد تالف؛ أعد تنزيله");ColabProbe.check(cancel);
  String prompt;try(InputStream in=c.getAssets().open("agent-prompt.txt");ByteArrayOutputStream out=new ByteArrayOutputStream()){FilesUtil.copy(in,out);prompt=out.toString("UTF-8");}
  String kind=source.optString("kind");JSONObject data=new JSONObject().put("source",source).put("availableTools",new JSONArray(AgentTools.tools(kind))).put("preferences",memory(c)).put("userRequest",user);
  EngineConfig config=new EngineConfig(file.getAbsolutePath(),new Backend.CPU(),null,null,4096,0,c.getCacheDir().getAbsolutePath());
  try(Engine engine=new Engine(config)){engine.initialize();ColabProbe.check(cancel);
   try(Conversation chat=engine.createConversation(new ConversationConfig(Contents.Companion.of(prompt),Collections.emptyList(),Collections.emptyList(),new SamplerConfig(1,1.0,0.0,0),false,Collections.emptyList(),Collections.emptyMap(),null,false,384,new ThinkingConfig(false),true))){
    conversation=chat;ColabProbe.check(cancel);Message result=chat.sendMessage(Message.Companion.user(data.toString()),Collections.emptyMap(),null,null,null,384,new ThinkingConfig(false),ResponseFormat.Companion.json(AgentTools.schema(kind).toString()));ColabProbe.check(cancel);StringBuilder text=new StringBuilder();for(Content content:result.getContents().getContents())if(content instanceof Content.Text)text.append(((Content.Text)content).getText());
    if(text.length()>8000)throw new IOException("رد المساعد غير مكتمل؛ اختصر الطلب");JSONObject plan=AgentTools.validate(new JSONObject(text.toString()),kind);if(c.getSharedPreferences("local_agent",0).getBoolean("learn",false)){JSONObject saved=memory(c).optJSONObject(kind+":"+plan.getString("tool"));if(saved!=null){Set<String> explicit=new HashSet<>();JSONArray fields=plan.getJSONArray("fields");for(int i=0;i<fields.length();i++)explicit.add(fields.getJSONObject(i).getString("name"));for(Iterator<String> keys=saved.keys();keys.hasNext();){String key=keys.next();if(!explicit.contains(key))fields.put(new JSONObject().put("name",key).put("value",saved.getString(key)));}}}return AgentTools.validate(plan,kind);
   }finally{conversation=null;}
  }
 }
 static JSONObject memory(Context c){try{return new JSONObject(c.getSharedPreferences("local_agent",0).getString("preferences","{}"));}catch(Exception e){return new JSONObject();}}
 static void remember(Context c,String kind,String tool,JSONObject options)throws Exception {if(!AgentTools.tools(kind).contains(tool))return;JSONObject preferences=memory(c),values=new JSONObject();for(String key:new String[]{"format","quality","ocrLanguage","musicOutput","musicMode","translationMode","target","geminiReview"})if(options.has(key)&&options.optString(key).length()<40)values.put(key,options.optString(key));preferences.put(kind+":"+tool,values);c.getSharedPreferences("local_agent",0).edit().putString("preferences",preferences.toString()).apply();}
 static void forget(Context c){c.getSharedPreferences("local_agent",0).edit().clear().apply();}
}
