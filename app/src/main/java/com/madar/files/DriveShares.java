package com.madar.files;

import android.content.Context;
import okhttp3.*;
import org.json.*;
import java.io.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Resumable chunked upload. Only files created by Madar can be managed here. */
final class DriveShares implements AutoCloseable {
 interface Progress{void update(int percent,String text);}
 interface RecordStore{JSONArray list();void save(JSONArray values)throws Exception;}
 private final RecordStore store;private final HttpUrl uploadBase,apiBase;private final String token;private final AtomicBoolean cancel;private final OkHttpClient client;private volatile Call active;
 DriveShares(Context c,String t,AtomicBoolean stop){this(t,stop,new OkHttpClient.Builder().connectTimeout(25,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS).writeTimeout(90,TimeUnit.SECONDS).callTimeout(2,TimeUnit.MINUTES).followRedirects(false).build(),HttpUrl.get("https://www.googleapis.com/upload/drive/v3/"),HttpUrl.get("https://www.googleapis.com/drive/v3/"),new RecordStore(){public JSONArray list(){return DriveShares.list(c);}public void save(JSONArray values)throws Exception{DriveShares.save(c,values);}});}
 DriveShares(String t,AtomicBoolean stop,OkHttpClient http,HttpUrl upload,HttpUrl api,RecordStore records){token=t;cancel=stop;client=http;uploadBase=upload;apiBase=api;store=records;}
 void cancel(){cancel.set(true);Call c=active;if(c!=null)c.cancel();}
 private Response send(Request.Builder b)throws Exception{ColabProbe.check(cancel);active=client.newCall(b.header("Authorization","Bearer "+token).build());Response r=active.execute();if(r.code()==401){r.close();throw new IOException("انتهت صلاحية Google؛ أعد الربط ثم حاول مجددًا");}if(r.code()==403){r.close();throw new IOException("رفض Drive الطلب؛ تحقق من تفعيل Drive API ومساحة الحساب والسماح بمشاركة الرابط");}return r;}
 private JSONObject json(Request.Builder b)throws Exception{try(Response r=send(b)){if(!r.isSuccessful()||r.body()==null)throw new IOException("تعذر اتصال Drive · HTTP "+r.code());String s=r.body().string();return s.isEmpty()?new JSONObject():new JSONObject(s);}}
 static synchronized JSONArray list(Context c){JSONObject s=GeminiCheckpoint.read(new File(c.getFilesDir(),"drive-shares.json"));return s==null?new JSONArray():s.optJSONArray("files")==null?new JSONArray():s.optJSONArray("files");}
 private static synchronized void save(Context c,JSONArray files)throws Exception{GeminiCheckpoint.save(new File(c.getFilesDir(),"drive-shares.json"),new JSONObject().put("files",files));}
 private void record(JSONObject item)throws Exception{JSONArray files=store.list();files.put(item);store.save(files);}
 static JSONObject owned(Context c,String id)throws Exception{for(int i=0;i<list(c).length();i++){JSONObject f=list(c).getJSONObject(i);if(f.optString("id").equals(id))return f;}throw new IOException("هذا الملف غير مسجل ضمن مشاركات مدار");}
 JSONObject upload(File file,JSONObject info,Progress progress)throws Exception{
  if(!file.isFile()||file.length()==0||file.length()>1024L*1024*1024)throw new IOException("ارفع ملفًا غير فارغ حجمه حتى 1 غيغابايت");
  JSONObject metadata=new JSONObject().put("name",info.optString("name",file.getName())).put("appProperties",new JSONObject().put("madar","qr"));String session;
  try(Response r=send(new Request.Builder().url(uploadBase.resolve("files?uploadType=resumable&fields=id,name,webViewLink,size")).header("X-Upload-Content-Type",info.optString("mime",FilesUtil.mime(file))).header("X-Upload-Content-Length",Long.toString(file.length())).post(RequestBody.create(MediaType.parse("application/json"),metadata.toString())))){if(!r.isSuccessful()||r.header("Location")==null)throw new IOException("تعذر بدء رفع Drive · HTTP "+r.code());session=r.header("Location");}
  // Drive session URLs are sensitive; never expose them to the WebView or logs.
  HttpUrl sessionUrl=HttpUrl.parse(session);if(sessionUrl==null||!sessionUrl.scheme().equals(uploadBase.scheme())||!sessionUrl.host().equals(uploadBase.host())||sessionUrl.port()!=uploadBase.port())throw new IOException("عنوان رفع Drive غير صالح");
  JSONObject uploaded=null;long offset=0,total=file.length();try(RandomAccessFile input=new RandomAccessFile(file,"r")){while(offset<total){ColabProbe.check(cancel);int count=(int)Math.min(4*1024*1024,total-offset);byte[] chunk=new byte[count];input.seek(offset);input.readFully(chunk);boolean advanced=false;
   for(int attempt=0;attempt<3&&!advanced;attempt++){try(Response r=send(new Request.Builder().url(session).header("Content-Range","bytes "+offset+"-"+(offset+count-1)+"/"+total).put(RequestBody.create(MediaType.parse("application/octet-stream"),chunk)))){if(r.code()==308){String range=r.header("Range","");long next=range.matches("bytes=0-[0-9]+")?Long.parseLong(range.substring(8))+1:0;if(next<=offset||next>offset+count)throw new IOException("موضع رفع Drive غير صالح");offset=next;advanced=true;}else if(r.isSuccessful()&&r.body()!=null){uploaded=new JSONObject(r.body().string());offset=total;advanced=true;}else if(r.code()<500&&r.code()!=429)throw new IOException("توقف رفع Drive · HTTP "+r.code());}catch(IOException network){ColabProbe.check(cancel);if(attempt==2)throw network;}
    if(!advanced){try(Response r=send(new Request.Builder().url(session).header("Content-Range","bytes */"+total).put(RequestBody.create(null,new byte[0])))){if(r.code()==308){String range=r.header("Range","");long next=range.matches("bytes=0-[0-9]+")?Long.parseLong(range.substring(8))+1:0;if(next<0||next>total)throw new IOException("استجابة استئناف غير صالحة");if(next!=offset){offset=next;advanced=true;}}else if(r.isSuccessful()&&r.body()!=null){uploaded=new JSONObject(r.body().string());offset=total;advanced=true;}}}
   }if(!advanced)throw new IOException("تعذر استكمال الرفع بعد ثلاث محاولات؛ أعد المحاولة");progress.update((int)(offset*95/total),"رفع الملف إلى Drive");}}
  if(uploaded==null||!uploaded.has("id"))throw new IOException("لم يؤكد Drive اكتمال الملف");String id=uploaded.getString("id");if(!id.matches("[A-Za-z0-9_-]{5,200}"))throw new IOException("معرف Drive غير صالح");
  JSONObject item=new JSONObject().put("id",id).put("name",info.optString("name")).put("size",total).put("url","https://drive.google.com/file/d/"+id+"/view").put("public",false).put("created",System.currentTimeMillis());record(item);
  json(new Request.Builder().url(apiBase.resolve("files/"+id+"/permissions")).post(RequestBody.create(MediaType.parse("application/json"),new JSONObject().put("type","anyone").put("role","reader").put("allowFileDiscovery",false).toString())));
  synchronized(DriveShares.class){JSONArray files=store.list();for(int i=0;i<files.length();i++)if(files.getJSONObject(i).optString("id").equals(id))files.getJSONObject(i).put("public",true);store.save(files);}item.put("public",true);progress.update(100,"اكتمل رفع الملف وإنشاء الرابط");return item;
 }
 void delete(String id)throws Exception{boolean known=false;for(int i=0;i<store.list().length();i++)if(store.list().getJSONObject(i).optString("id").equals(id))known=true;if(!known)throw new IOException("هذا الملف غير مسجل ضمن مشاركات مدار");try(Response r=send(new Request.Builder().url(apiBase.resolve("files/"+id)).delete())){if(!r.isSuccessful()&&r.code()!=404)throw new IOException("تعذر حذف الملف من Drive · HTTP "+r.code());}synchronized(DriveShares.class){JSONArray files=store.list(),keep=new JSONArray();for(int i=0;i<files.length();i++)if(!files.getJSONObject(i).optString("id").equals(id))keep.put(files.getJSONObject(i));store.save(keep);}}
 @Override public void close(){Call c=active;if(c!=null&&cancel.get())c.cancel();client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
}
