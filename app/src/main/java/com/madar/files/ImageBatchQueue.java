package com.madar.files;
import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** A private durable journal. Checkpoints are at completed-file boundaries. */
final class ImageBatchQueue {
 private static File directory(Context c){File d=new File(c.getFilesDir(),"image-batch");if(!d.isDirectory()&&!d.mkdirs())throw new IllegalStateException("تعذر فتح مجلد الطابور");return d;}
 static synchronized JSONObject read(Context c)throws Exception{AtomicFile file=new AtomicFile(new File(directory(c),"state.json"));try(InputStream in=file.openRead()){return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}catch(FileNotFoundException e){return null;}}
 static synchronized void save(Context c,JSONObject state)throws Exception{AtomicFile file=new AtomicFile(new File(directory(c),"state.json"));FileOutputStream out=null;try{out=file.startWrite();out.write(state.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}}
 static synchronized JSONObject create(Context c,List<File> files,JSONArray names,JSONObject options)throws Exception{
  JSONObject previous=read(c);if(previous!=null&&!previous.optString("status").equals("complete"))throw new IOException("يوجد طابور سابق؛ استأنفه أو امسحه أولًا");
  if(files.isEmpty()||files.size()>20)throw new IOException("اختر من صورة واحدة إلى 20 صورة");
  JSONObject safe=validate(options);clear(c);JSONArray jobs=new JSONArray();List<File> copies=new ArrayList<>();
  try{for(int i=0;i<files.size();i++){File copy=new File(directory(c),UUID.randomUUID()+".input");copies.add(copy);try(InputStream in=new FileInputStream(files.get(i));OutputStream out=new FileOutputStream(copy)){FilesUtil.copy(in,out);}jobs.put(new JSONObject().put("input",copy.getName()).put("name",names.getString(i)).put("status","pending"));}
   JSONObject state=new JSONObject().put("status","paused").put("options",safe).put("jobs",jobs);save(c,state);return state;
  }catch(Exception|OutOfMemoryError e){for(File file:copies)file.delete();throw e;}
 }
 static JSONObject validate(JSONObject options)throws Exception{
  String tool=options.optString("tool","compress");if(!Arrays.asList("compress","convert","resize","targetImage").contains(tool))throw new IOException("أداة دفعات غير مدعومة");String format=options.optString("format","jpg");if(!format.matches("jpg|png|webp"))throw new IOException("صيغة الصور غير صالحة");
  JSONObject safe=new JSONObject().put("kind","image").put("tool",tool).put("format",format);
  safe.put("quality",(int)FilesUtil.bounded(options.optDouble("quality",80),10,100));
  if(tool.equals("resize"))safe.put("width",(int)FilesUtil.bounded(options.getDouble("width"),32,4096));
  if(tool.equals("targetImage"))safe.put("targetKb",(int)FilesUtil.bounded(options.getDouble("targetKb"),20,20000));return safe;
 }
 static File input(Context c,String name)throws IOException{File root=directory(c),file=new File(root,name);if(!file.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())||!name.endsWith(".input")||!file.isFile())throw new IOException("صورة الطابور غير موجودة");return file;}
 static synchronized void clear(Context c)throws IOException{File[] files=directory(c).listFiles();if(files!=null)for(File file:files)if(file.isFile()&&!file.delete())throw new IOException("تعذر حذف ملفات الطابور");}
 static JSONObject view(JSONObject state,boolean active)throws Exception{JSONObject result=new JSONObject(state.toString());result.remove("options");JSONArray jobs=result.getJSONArray("jobs");for(int i=0;i<jobs.length();i++)jobs.getJSONObject(i).remove("input");if(!active&&result.optString("status").equals("running"))result.put("status","paused");return result.put("type","imageBatch").put("active",active);}
}
