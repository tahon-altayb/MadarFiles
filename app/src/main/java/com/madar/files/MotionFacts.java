package com.madar.files;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Before a new advert: reads the owner's own pictures (menu items and prices, products, the place,
 * the logo) so the chat can show what it found and ask whether anything is missing. Gemini first;
 * when Gemini is down, Groq's vision model reads one picture per request. Only visible facts.
 */
final class MotionFacts {
 static final String SYSTEM="You read a shop's own pictures before an Arabic advert is written. Report ONLY what is visible: readable menu items with their exact prices as written, products shown, the place (seating, interior, storefront), the brand name on a logo, opening hours, delivery apps or contact details written on the picture. Never guess a product, price or service that is not visible. Write short Arabic. kind: logo, menu, product, place, storefront or other. summary: one short Arabic sentence of what the picture shows. items: menu or product names with price ('' when no price is visible), at most 25. facts: other short visible facts (hours, delivery apps, slogan), at most 6.";
 static final List<String> KINDS=Arrays.asList("logo","menu","product","place","storefront","other");

 static JSONObject imageSchema(){
  try{
   JSONObject item=new JSONObject().put("type","object").put("properties",new JSONObject().put("name",new JSONObject().put("type","string")).put("price",new JSONObject().put("type","string"))).put("required",new JSONArray().put("name").put("price"));
   return new JSONObject().put("type","object").put("properties",new JSONObject()
     .put("index",new JSONObject().put("type","integer"))
     .put("kind",new JSONObject().put("type","string").put("enum",new JSONArray(KINDS)))
     .put("summary",new JSONObject().put("type","string"))
     .put("items",new JSONObject().put("type","array").put("items",item))
     .put("facts",new JSONObject().put("type","array").put("items",new JSONObject().put("type","string"))))
    .put("required",new JSONArray().put("index").put("kind").put("summary").put("items").put("facts"));
  }catch(JSONException e){throw new IllegalStateException(e);}
 }

 /** Keeps only well-formed, bounded fields; drops anything else the model returned. */
 static JSONObject clean(JSONObject raw,int index)throws JSONException{
  String kind=raw.optString("kind","other");if(!KINDS.contains(kind))kind="other";
  JSONObject out=new JSONObject().put("index",index).put("kind",kind).put("summary",trim(raw.optString("summary"),140));
  JSONArray items=new JSONArray(),in=raw.optJSONArray("items");Set<String> seen=new HashSet<>();
  if(in!=null)for(int i=0;i<in.length()&&items.length()<25;i++){JSONObject it=in.optJSONObject(i);if(it==null)continue;String name=trim(it.optString("name"),60),price=trim(it.optString("price"),24);if(name.length()<2||!seen.add(name))continue;items.put(new JSONObject().put("name",name).put("price",price));}
  JSONArray facts=new JSONArray(),f=raw.optJSONArray("facts");if(f!=null)for(int i=0;i<f.length()&&facts.length()<6;i++){String v=trim(f.optString(i),100);if(v.length()>=3)facts.put(v);}
  return out.put("items",items).put("facts",facts);
 }
 static String trim(String v,int max){v=String.valueOf(v==null?"":v).replaceAll("[\\p{Cntrl}]"," ").replaceAll("\\s+"," ").trim();return v.length()>max?v.substring(0,max).trim():v;}

 static JSONObject analyze(Context c,JSONArray ids,AtomicBoolean cancel)throws Exception{
  List<File> files=new ArrayList<>();
  for(int i=0;i<ids.length()&&files.size()<8;i++){String id=ids.getString(i);if(!AssistantFiles.info(c,id).optString("kind").equals("image"))continue;files.add(AssistantFiles.resolve(c,id));}
  if(files.isEmpty())throw new IOException("ما فيه صور أحللها");
  Exception gemini=null;
  if(!AgentDiagnostics.recentlyDown(c))try{return gemini(c,files,cancel);}catch(CancellationException stop){throw stop;}catch(Exception failed){gemini=failed;}
  if(GroqClient.configured(c))return groq(c,files,cancel);
  throw gemini!=null?gemini:new IOException("Gemini ما يرد حاليًا، وما فيه مفتاح Groq للتحليل");
 }

 static JSONObject gemini(Context c,List<File> files,AtomicBoolean cancel)throws Exception{
  JSONArray visual=new JSONArray();for(int i=0;i<files.size();i++){visual.put(new JSONObject().put("text","Picture index "+i));visual.put(part(files.get(i),1024,false));}
  JSONObject schema=new JSONObject().put("type","object").put("properties",new JSONObject().put("images",new JSONObject().put("type","array").put("items",imageSchema()))).put("required",new JSONArray().put("images"));
  JSONObject input=new JSONObject().put("task","motion_facts").put("picture_count",files.size());
  JSONObject answer=AgentExecutive.invoke(c,SYSTEM,input,visual,schema,4096,cancel);
  JSONArray in=answer.getJSONArray("images"),out=new JSONArray();Set<Integer> done=new HashSet<>();
  for(int i=0;i<in.length();i++){JSONObject z=in.optJSONObject(i);if(z==null)continue;int index=z.optInt("index",i);if(index<0||index>=files.size()||!done.add(index))continue;out.put(clean(z,index));}
  return new JSONObject().put("provider","gemini").put("images",out);
 }

 static JSONObject groq(Context c,List<File> files,AtomicBoolean cancel)throws Exception{
  JSONArray out=new JSONArray();Exception last=null;
  for(int i=0;i<files.size();i++){
   ColabProbe.check(cancel);String url=(String)part(files.get(i),1024,true);
   for(int attempt=0;attempt<2;attempt++)try{
    JSONObject z=GroqClient.vision(c,SYSTEM,"Picture index "+i+". Return the JSON object for this one picture.",url,imageSchema(),cancel);out.put(clean(z,i));break;
   }catch(CancellationException stop){throw stop;}
   catch(Exception failed){last=failed;if(attempt==0&&String.valueOf(failed.getMessage()).contains("(429)")){for(int w=0;w<200;w++){ColabProbe.check(cancel);Thread.sleep(100);}continue;}break;}
  }
  if(out.length()==0)throw last!=null?last:new IOException("تعذر تحليل الصور");
  return new JSONObject().put("provider","groq").put("images",out);
 }

 static Object part(File file,int edge,boolean dataUrl)throws Exception{
  Bitmap bitmap=FilesUtil.image(file,edge);ByteArrayOutputStream bytes=new ByteArrayOutputStream();try{bitmap.compress(Bitmap.CompressFormat.JPEG,82,bytes);}finally{bitmap.recycle();}
  String data=android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP);
  return dataUrl?"data:image/jpeg;base64,"+data:new JSONObject().put("inlineData",new JSONObject().put("mimeType","image/jpeg").put("data",data));
 }
 private MotionFacts(){}
}
