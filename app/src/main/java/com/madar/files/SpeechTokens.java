package com.madar.files;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
/**
 * sherpa-onnx 1.13.8 normalizes each decoded BPE piece before joining it.
 * A piece can end midway through UTF-8, so that normalization loses valid bytes.
 * Keep model token IDs unchanged, transport their bytes as ASCII hex, and decode
 * the assembled text in JNI. This is reversible encoding, never a text correction.
 */
final class SpeechTokens {
 static String safePiece(String encoded){
  byte[] raw=encoded.equals("=")?new byte[0]:Base64.getDecoder().decode(encoded);
  char[] hex=new char[raw.length*2];String digits="0123456789abcdef";
  for(int i=0;i<raw.length;i++){int value=raw[i]&255;hex[i*2]=digits.charAt(value>>>4);hex[i*2+1]=digits.charAt(value&15);}
  String out=Base64.getEncoder().encodeToString(new String(hex).getBytes(StandardCharsets.US_ASCII));return out.isEmpty()?"=":out;
 }
 static File safeVocabulary(Context c,File original)throws Exception{
  MessageDigest sha=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(original)){byte[] bytes=new byte[32768];int count;while((count=in.read(bytes))!=-1)sha.update(bytes,0,count);}
  StringBuilder name=new StringBuilder();for(byte value:sha.digest())name.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
  File directory=new File(c.getFilesDir(),"speech-vocabulary");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("تعذر تجهيز قاموس الكلام");
  File target=new File(directory,name+"-hex-v1.txt");if(target.isFile()&&target.length()>original.length())return target;
  File part=new File(directory,target.getName()+".partial");
  try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(original),StandardCharsets.UTF_8));Writer out=new OutputStreamWriter(new FileOutputStream(part),StandardCharsets.UTF_8)){
   String line;int expected=0;while((line=in.readLine())!=null){int split=line.lastIndexOf(' ');if(split<1||Integer.parseInt(line.substring(split+1))!=expected++)throw new IOException("قاموس الكلام غير صالح");out.write(safePiece(line.substring(0,split)));out.write(line.substring(split));out.write('\n');}
   if(expected<50000)throw new IOException("قاموس الكلام غير مكتمل");
  }catch(Exception e){part.delete();throw e;}
  if(target.exists()&&!target.delete()){part.delete();throw new IOException("تعذر تحديث قاموس الكلام");}
  if(!part.renameTo(target)){part.delete();throw new IOException("تعذر حفظ قاموس الكلام");}return target;
 }
}
