package com.madar.files;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Free backup brain for the chat when Gemini is unreachable (sideloaded builds can fail App
 * Check). Text only: one bounded JSON call with the owner's own Groq key; images and motion
 * stay with Gemini or the local contract.
 */
final class GroqClient {
 static final String MODEL="openai/gpt-oss-120b";
 static final String ENDPOINT="https://api.groq.com/openai/v1/chat/completions";
 static String key(Context c){return c.getSharedPreferences("groq",0).getString("key","");}
 static boolean configured(Context c){return key(c).startsWith("gsk_");}
 static boolean validKey(String value){return value!=null&&value.matches("gsk_[A-Za-z0-9]{20,200}");}

 /** One JSON answer. The schema is described in the instructions; JSON mode guarantees an object. */
 static JSONObject json(Context c,String system,String user,JSONObject schema,int tokens,AtomicBoolean cancel)throws Exception{
  String instructions=system+"\n\nReturn ONLY one JSON object that matches this JSON schema exactly (types, required fields and enum values):\n"+schema;
  JSONObject request=new JSONObject().put("model",MODEL).put("temperature",0).put("max_completion_tokens",Math.max(512,Math.min(tokens,4096))).put("reasoning_effort","low")
   .put("response_format",new JSONObject().put("type","json_object"))
   .put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content",instructions)).put(new JSONObject().put("role","user").put("content",user)));
  JSONObject response;
  try{response=post(key(c),request,cancel);}
  catch(IOException unsupported){if(!String.valueOf(unsupported.getMessage()).contains("(400)"))throw unsupported;request.remove("reasoning_effort");response=post(key(c),request,cancel);}
  JSONObject choice=response.getJSONArray("choices").getJSONObject(0);
  if("length".equals(choice.optString("finish_reason")))throw new IOException("رد Groq أطول من الحد؛ اختصر الطلب");
  String content=choice.getJSONObject("message").optString("content","").trim();
  if(content.isEmpty()||content.length()>24000)throw new IOException("رد Groq غير صالح");
  return new JSONObject(content);
 }

 static final String VISION_MODEL="qwen/qwen3.8-27b";
 /** One picture (a data URL, at most 3 per Groq request) read into one JSON object. */
 static JSONObject vision(Context c,String system,String user,String imageUrl,JSONObject schema,AtomicBoolean cancel)throws Exception{
  if(imageUrl==null||!imageUrl.startsWith("data:image/jpeg;base64,")||imageUrl.length()>6_000_000)throw new IOException("صورة غير صالحة للتحليل");
  JSONArray content=new JSONArray().put(new JSONObject().put("type","text").put("text",user+"\n\nReturn ONLY one JSON object that matches this JSON schema exactly:\n"+schema)).put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url",imageUrl)));
  JSONObject request=new JSONObject().put("model",VISION_MODEL).put("temperature",0).put("max_completion_tokens",2048).put("reasoning_effort","none")
   .put("response_format",new JSONObject().put("type","json_object"))
   .put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content",system)).put(new JSONObject().put("role","user").put("content",content)));
  JSONObject response;
  try{response=post(key(c),request,cancel);}
  catch(IOException unsupported){if(!String.valueOf(unsupported.getMessage()).contains("(400)"))throw unsupported;request.remove("reasoning_effort");response=post(key(c),request,cancel);}
  return object(response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content",""));
 }
 /** The JSON object in a reply, also when a model wraps it in reasoning or a code fence. */
 static JSONObject object(String content)throws Exception{
  String text=content.replaceAll("(?s)<think>.*?</think>","").trim();int a=text.indexOf('{'),b=text.lastIndexOf('}');
  if(a<0||b<=a||b-a>24000)throw new IOException("رد Groq غير صالح");return new JSONObject(text.substring(a,b+1));
 }

 static JSONObject post(String key,JSONObject body,AtomicBoolean cancel)throws Exception{
  if(!validKey(key))throw new IOException("أضف مفتاح Groq صالحًا (يبدأ بـ gsk_)");
  HttpURLConnection connection=(HttpURLConnection)new URL(ENDPOINT).openConnection();
  AtomicBoolean done=new AtomicBoolean();Thread watcher=new Thread(()->{try{while(!done.get()){if(cancel.get()){connection.disconnect();return;}Thread.sleep(100);}}catch(InterruptedException ignored){}},"madar-groq-cancel");watcher.setDaemon(true);
  try{
   connection.setInstanceFollowRedirects(false);connection.setRequestMethod("POST");connection.setConnectTimeout(15000);connection.setReadTimeout(60000);
   connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setRequestProperty("Authorization","Bearer "+key);connection.setDoOutput(true);
   byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);watcher.start();
   if(cancel.get())throw new CancellationException();
   try(OutputStream stream=connection.getOutputStream()){stream.write(bytes);}
   int code=connection.getResponseCode();if(cancel.get())throw new CancellationException();
   if(code!=200){String reason=code==401||code==403?"مفتاح Groq غير صالح أو بلا صلاحية":code==429?"وصل Groq المجاني إلى حده المؤقت؛ أعد المحاولة بعد دقيقة":code==413?"طلبك أكبر من حد Groq المجاني":"تعذر اتصال Groq";throw new IOException(reason+" ("+code+")");}
   try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] chunk=new byte[8192];int n;while((n=in.read(chunk))!=-1){if(cancel.get())throw new CancellationException();if(out.size()+n>2000000)throw new IOException("استجابة Groq أكبر من الحد");out.write(chunk,0,n);}return new JSONObject(out.toString("UTF-8"));}
  }finally{done.set(true);watcher.interrupt();connection.disconnect();}
 }

 static void show(Activity a){
  LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);int pad=(int)(20*a.getResources().getDisplayMetrics().density);box.setPadding(pad,pad,pad,pad);
  TextView note=new TextView(a);note.setText((configured(a)?"مفتاح Groq محفوظ. ":"")+"Groq بديل مجاني يفهم طلبات الشات إذا ما رد Gemini. الصق مفتاحك من console.groq.com (يبدأ بـ gsk_). يُرسل له نص الشات، وصور الموشن لتحليلها قبل البدء إذا ما رد Gemini. للمجاني حدود بالدقيقة واليوم.");box.addView(note);
  EditText secret=new EditText(a);secret.setHint("gsk_…");secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);secret.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);box.addView(secret);
  AlertDialog dialog=new AlertDialog.Builder(a).setTitle("مفتاح Groq المجاني").setView(box).setPositiveButton("حفظ",null).setNegativeButton("إلغاء",null).setNeutralButton("حذف المفتاح",(d,w)->a.getSharedPreferences("groq",0).edit().clear().apply()).create();
  dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String value=secret.getText().toString().trim();if(!validKey(value)){Toast.makeText(a,"المفتاح لازم يبدأ بـ gsk_ وبدون مسافات",Toast.LENGTH_LONG).show();return;}if(!a.getSharedPreferences("groq",0).edit().putString("key",value).commit()){Toast.makeText(a,"تعذر الحفظ",Toast.LENGTH_LONG).show();return;}secret.setText("");Toast.makeText(a,"حُفظ مفتاح Groq؛ يُستخدم إذا ما رد Gemini",Toast.LENGTH_LONG).show();dialog.dismiss();}));
  dialog.show();
 }
 private GroqClient(){}
}
