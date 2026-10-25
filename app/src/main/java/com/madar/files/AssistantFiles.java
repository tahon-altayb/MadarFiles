package com.madar.files;
import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.util.UUID;
/** Private, stable attachments. Never deletes a document from its original provider. */
final class AssistantFiles {
 static File directory(Context c)throws IOException{File d=new File(c.getFilesDir(),"assistant-attachments");if(!d.isDirectory()&&!d.mkdirs())throw new IOException("تعذر حفظ المرفق");return d;}
 static File resolve(Context c,String id)throws IOException{if(!id.matches("[a-f0-9\\-]{36}"))throw new IOException("معرف المرفق غير صالح");File f=new File(directory(c),id+".media");if(!f.isFile())throw new IOException("المرفق لم يعد موجودًا؛ أرفقه مجددًا");return f;}
 static boolean owns(Context c,File f)throws IOException{return f!=null&&f.getCanonicalFile().getParentFile().equals(directory(c).getCanonicalFile());}
 static JSONObject store(Context c,File source,JSONObject info)throws Exception{
  if(owns(c,source))return new JSONObject(info.toString());
  if(c.getFilesDir().getUsableSpace()<source.length()+64L*1024*1024)throw new IOException("المساحة لا تكفي للاحتفاظ بالمرفق");
  String id=UUID.randomUUID().toString();File dest=new File(directory(c),id+".media");JSONObject saved=new JSONObject(info.toString()).put("attachmentId",id);boolean success=false;
  try{try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(dest)){FilesUtil.copy(in,out);}GeminiCheckpoint.save(new File(directory(c),id+".json"),saved);success=true;return saved;}finally{if(!success){dest.delete();new File(directory(c),id+".json").delete();}}
 }
 static JSONObject info(Context c,String id)throws Exception{resolve(c,id);JSONObject info=GeminiCheckpoint.read(new File(directory(c),id+".json"));if(info==null)throw new IOException("تعذر استعادة بيانات المرفق");return info;}
 static void delete(Context c,String id)throws Exception{File f=resolve(c,id);if(!f.delete())throw new IOException("تعذر حذف نسخة المرفق");new AtomicFile(new File(directory(c),id+".json")).delete();}
}
