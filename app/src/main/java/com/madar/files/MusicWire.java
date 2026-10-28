package com.madar.files;
import java.io.*;
import java.util.Arrays;
import java.util.zip.*;
import org.json.JSONObject;
/** Lossless, bounded PCM transport. Hashes cover both the wire bytes and original samples. */
final class MusicWire {
 static final int MAX_BYTES=16*44100*4;
 static final class Packed {final byte[] data;final JSONObject metadata;final boolean compressed;Packed(byte[] d,JSONObject m,boolean z){data=d;metadata=m;compressed=z;}}
 static Packed encode(byte[] pcm)throws Exception{
  if(pcm.length<=0||pcm.length>MAX_BYTES)throw new IOException("Colab · MUSIC_WIRE_LENGTH");
  ByteArrayOutputStream out=new ByteArrayOutputStream();Deflater engine=new Deflater(Deflater.BEST_SPEED);
  try(DeflaterOutputStream zip=new DeflaterOutputStream(out,engine,8192)){zip.write(pcm);}finally{engine.end();}
  byte[] encoded=out.toByteArray();JSONObject part=new JSONObject().put("bytes",pcm.length).put("sha256",ColabMusic.sha(pcm));
  if(encoded.length+512>=pcm.length)return new Packed(pcm,part,false);
  part.put("wire_encoding","zlib").put("wire_bytes",encoded.length).put("wire_sha256",ColabMusic.sha(encoded));return new Packed(encoded,part,true);
 }
 static byte[] fetch(ColabRuntime runtime,String name,JSONObject part,int expected)throws Exception{
  String encoding=part.optString("wire_encoding","pcm");if(!encoding.equals("pcm")&&!encoding.equals("zlib"))throw new IOException("Colab · MUSIC_WIRE_ENCODING");
  return decode(runtime.getBinary(name+(encoding.equals("zlib")?".zlib":""),8*1024*1024),part,expected);
 }
 static byte[] decode(byte[] wire,JSONObject part,int expected)throws Exception{
  if(expected<=0||expected>MAX_BYTES||part.optLong("bytes",-1)!=expected)throw new IOException("Colab · MUSIC_WIRE_LENGTH");String encoding=part.optString("wire_encoding","pcm");byte[] pcm;
  if(encoding.equals("pcm"))pcm=wire;
  else if(encoding.equals("zlib")){
   long size=part.optLong("wire_bytes",-1);if(size<=0||size>=expected||wire.length!=size)throw new IOException("Colab · MUSIC_WIRE_LENGTH");
   if(!ColabMusic.sha(wire).equals(part.optString("wire_sha256")))throw new IOException("Colab · MUSIC_WIRE_HASH");
   Inflater engine=new Inflater();byte[] output=new byte[expected+1];int written=0;engine.setInput(wire);
   try{while(!engine.finished()){
    if(written>=output.length)throw new IOException("Colab · MUSIC_WIRE_LENGTH");int n=engine.inflate(output,written,output.length-written);written+=n;
    if(n==0&&!engine.finished())throw new IOException("Colab · MUSIC_WIRE_LENGTH");
   }if(written!=expected||engine.getRemaining()!=0)throw new IOException("Colab · MUSIC_WIRE_LENGTH");pcm=Arrays.copyOf(output,written);
   }catch(DataFormatException invalid){throw new IOException("Colab · MUSIC_WIRE_HASH",invalid);}finally{engine.end();}
  }else throw new IOException("Colab · MUSIC_WIRE_ENCODING");
  if(pcm.length!=expected)throw new IOException("Colab · MUSIC_OUTPUT_LENGTH");if(!ColabMusic.sha(pcm).equals(part.optString("sha256")))throw new IOException("Colab · MUSIC_OUTPUT_HASH");return pcm;
 }
 private MusicWire(){}
}
