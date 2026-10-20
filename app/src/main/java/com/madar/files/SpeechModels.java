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
  boolean maximum=quality.equals("maximum");String root=maximum?"https://huggingface.co/csukuangfj/sherpa-onnx-whisper-medium/resolve/8c31d28503847560985df21f90e14f0c736e075e/":ROOT;String[] names=maximum?new String[]{"medium-encoder.int8.onnx","medium-decoder.int8.onnx","medium-tokens.txt"}:NAMES;long[] sizes=maximum?new long[]{374196283L,571059257L,816730L}:SIZES;String[] hashes=maximum?new String[]{"1c54582b4d829de0089f6cb63bbbdb3bf7555398bacaf855fbecf1a84dfd193e","595d00a338a365a7bfa0ca7f296cabc639583bef770ab6130df90f49a6412747","b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"}:HASHES;
  File dir=new File(c.getFilesDir(),maximum?"speech-medium":"speech-small");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("تعذر تجهيز مجلد النموذج");File[] files=new File[3];
  for(int i=0;i<3;i++){files[i]=new File(dir,names[i]);if(files[i].isFile()&&files[i].length()==sizes[i]&&digest(files[i]).equals(hashes[i]))continue;if(dir.getUsableSpace()<sizes[i]+64L*1024*1024)throw new IOException("المساحة لا تكفي لتنزيل نموذج التفريغ");File temp=new File(dir,names[i]+".partial");HttpURLConnection connection=(HttpURLConnection)new URL(root+names[i]).openConnection();connection.setConnectTimeout(30000);connection.setReadTimeout(30000);try{if(connection.getResponseCode()!=200)throw new IOException("تعذر تنزيل نموذج الدقة الأعلى");try(InputStream in=connection.getInputStream();OutputStream out=new FileOutputStream(temp)){byte[] b=new byte[65536];long count=0,last=0;int n;while((n=in.read(b))!=-1){MusicSeparator.check(cancel);count+=n;if(count>sizes[i])throw new IOException("حجم نموذج غير متوقع");out.write(b,0,n);long now=android.os.SystemClock.elapsedRealtime();if(now-last>500){progress.update((int)(100*count/sizes[i]),"تنزيل نموذج الدقة الأعلى · "+(i+1)+" / 3");last=now;}}}if(temp.length()!=sizes[i]||!digest(temp).equals(hashes[i]))throw new IOException("فشل التحقق من النموذج؛ أعد التنزيل");if(files[i].exists()&&!files[i].delete())throw new IOException("تعذر تحديث النموذج");if(!temp.renameTo(files[i]))throw new IOException("تعذر حفظ النموذج");}finally{connection.disconnect();temp.delete();}}
  return files;
 }
 private static String digest(File f)throws Exception{MessageDigest sha=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)sha.update(b,0,n);}StringBuilder out=new StringBuilder();for(byte v:sha.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return out.toString();}
}
