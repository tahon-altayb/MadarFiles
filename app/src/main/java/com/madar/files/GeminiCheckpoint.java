package com.madar.files;
import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
/** Only validated results are cached privately. Different audio/options/model cannot share a result. */
final class GeminiCheckpoint {
 static File file(Context c,JSONObject request)throws Exception{
  File dir=new File(c.getCacheDir(),"gemini-checkpoints-v1");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("تعذر حفظ تقدم Gemini");
  long expired=System.currentTimeMillis()-7L*24*3600*1000;File[] files=dir.listFiles();if(files!=null){java.util.Arrays.sort(files,java.util.Comparator.comparingLong(File::lastModified).reversed());long kept=0;for(File f:files){kept+=f.length();if(f.lastModified()<expired||kept>128L*1024*1024)f.delete();}}
  MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(TranslationSettings.model(c).getBytes(StandardCharsets.UTF_8));byte[] hash=digest.digest(request.toString().getBytes(StandardCharsets.UTF_8));StringBuilder key=new StringBuilder();for(byte b:hash)key.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return new File(dir,key+".json");
 }
 static JSONObject read(File f){try{if(!f.isFile()||f.length()>2_000_000)return null;return new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));}catch(Exception ignored){f.delete();return null;}}
 static void save(File f,JSONObject response)throws IOException{AtomicFile atomic=new AtomicFile(f);FileOutputStream out=null;try{out=atomic.startWrite();out.write(response.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(IOException e){if(out!=null)atomic.failWrite(out);throw e;}}
}
