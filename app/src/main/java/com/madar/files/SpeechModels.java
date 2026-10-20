package com.madar.files;
import android.content.Context;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** Optional multilingual model pinned to a public upstream revision and SHA-256. */
final class SpeechModels {
 private static final String ROOT="https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/8f3c18b358db4d1f2fc1eae49d75cd20989e4309/";
 private static final String[] NAMES={"small-encoder.int8.onnx","small-decoder.int8.onnx","small-tokens.txt"};
 private static final long[] SIZES={112442483L,262226114L,816730L};
 private static final String[] HASHES={"4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9","acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee","b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"};
 static File[] get(Context c,String quality,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception{
  boolean maximum=quality.equals("maximum");String root=maximum?"https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/2ca6ff69fc878651b770880507669577ac41c2ff/":ROOT;String[] names=maximum?new String[]{"turbo-encoder.int8.onnx","turbo-decoder.int8.onnx","turbo-tokens.txt"}:NAMES;long[] sizes=maximum?new long[]{674716297L,361080764L,816730L}:SIZES;String[] hashes=maximum?new String[]{"b02dcdf54f348741e93fe732b67d933c8dcb6735655f710640143081db38878b","20accd02388482eb3a46bd615631adfdc85e1eb2c7db9ea3f02a40ffe6b81547","b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"}:HASHES;
  File dir=new File(c.getFilesDir(),maximum?"speech-turbo":"speech-small");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("تعذر تجهيز مجلد النموذج");File[] files=new File[3];long totalBytes=sizes[0]+sizes[1]+sizes[2],completedBytes=0;
  for(int i=0;i<3;i++){files[i]=new File(dir,names[i]);if(files[i].isFile()&&files[i].length()==sizes[i]&&digest(files[i]).equals(hashes[i])){completedBytes+=sizes[i];continue;}if(dir.getUsableSpace()<sizes[i]+64L*1024*1024)throw new IOException("المساحة لا تكفي لتنزيل نموذج التفريغ");File temp=new File(dir,names[i]+".partial");HttpURLConnection connection=(HttpURLConnection)new URL(root+names[i]).openConnection();connection.setConnectTimeout(30000);connection.setReadTimeout(30000);try{if(connection.getResponseCode()!=200)throw new IOException("تعذر تنزيل نموذج الدقة الأعلى");try(InputStream in=connection.getInputStream();OutputStream out=new FileOutputStream(temp)){byte[] b=new byte[65536];long count=0,last=0;int n;while((n=in.read(b))!=-1){MusicSeparator.check(cancel);count+=n;if(count>sizes[i])throw new IOException("حجم نموذج غير متوقع");out.write(b,0,n);long now=android.os.SystemClock.elapsedRealtime();if(now-last>500){progress.update((int)(100*(completedBytes+count)/totalBytes),"تنزيل نموذج الدقة الأعلى · "+(i+1)+" / 3");last=now;}}}if(temp.length()!=sizes[i]||!digest(temp).equals(hashes[i]))throw new IOException("فشل التحقق من النموذج؛ أعد التنزيل");if(files[i].exists()&&!files[i].delete())throw new IOException("تعذر تحديث النموذج");if(!temp.renameTo(files[i]))throw new IOException("تعذر حفظ النموذج");}finally{connection.disconnect();temp.delete();}completedBytes+=sizes[i];}
  return files;
 }
 private static String digest(File f)throws Exception{MessageDigest sha=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)sha.update(b,0,n);}StringBuilder out=new StringBuilder();for(byte v:sha.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return out.toString();}
}
