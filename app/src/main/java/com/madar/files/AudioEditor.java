package com.madar.files;
import android.content.Context;
import android.media.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
final class AudioEditor {
 static File render(Context c,File source,JSONObject o,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception{
  AudioPipeline.Decoded decoded=null;File pcm=null,encoded=null,result=null;
  try{decoded=AudioPipeline.decode(c,source,0,cancel,progress);long total=decoded.pcm.length()/4;double duration=total/(double)decoded.sampleRate;
   double start=o.optDouble("start",0),end=o.optDouble("end",duration);if(!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<=start||end>duration+.2)throw new IOException("حدود الصوت غير صالحة");
   if(o.optString("kind").equals("video")&&(start>.001||end<duration-.2))throw new IOException("تعديل مستوى صوت الفيديو يحافظ على مدته؛ استخدم المحرر للقص");
   double gain=FilesUtil.bounded(o.optDouble("gain",100),0,200)/100,fade=FilesUtil.bounded(o.optDouble("fade",0),0,10);long from=Math.round(start*44100),frames=Math.min(Math.round(end*44100),Math.round(duration*44100))-from;
   pcm=File.createTempFile("madar_audioedit_",".pcm",c.getCacheDir());try(RandomAccessFile in=new RandomAccessFile(decoded.pcm,"r");OutputStream out=new BufferedOutputStream(new FileOutputStream(pcm))){for(long at=0;at<frames;at+=44100){MusicSeparator.check(cancel);int count=(int)Math.min(44100,frames-at);float[] f=MusicSeparator.readResampled(in,decoded.sampleRate,from+at,count,total);byte[] bytes=new byte[count*4];for(int i=0;i<count;i++){double edge=fade<=0?1:Math.min(1,Math.min((at+i)/(44100*fade),(frames-at-i)/(44100*fade)));for(int ch=0;ch<2;ch++){short v=(short)Math.round(Math.max(-1,Math.min(1,f[i*2+ch]*gain*edge))*32767);bytes[i*4+ch*2]=(byte)v;bytes[i*4+ch*2+1]=(byte)(v>>8);}}out.write(bytes);progress.update(10+(int)(70*(at+count)/Math.max(1,frames)),"تعديل الصوت محليًا");}}
   String format=o.optString("format","m4a");boolean video=o.optString("kind").equals("video");if(video)format="mp4";if(!format.matches("wav|m4a|aac|mp4"))throw new IOException("صيغة الصوت غير مدعومة");result=FilesUtil.output(c,format);
   if(format.equals("wav")){wave(pcm,result);return result;}
   encoded=format.equals("aac")?File.createTempFile("madar_aac_",".m4a",c.getCacheDir()):result;
   AudioPipeline.mux(source,pcm,encoded,video?decoded.offsetUs:0,0,!video,cancel,progress);
   if(format.equals("aac"))adts(encoded,result,cancel);return result;
  }catch(Exception|OutOfMemoryError e){if(result!=null)result.delete();throw e;}finally{if(decoded!=null)decoded.pcm.delete();if(pcm!=null)pcm.delete();if(encoded!=null&&encoded!=result)encoded.delete();}
 }
 static void wave(File pcm,File output)throws IOException{long size=pcm.length();if(size>0xffffffffL-36)throw new IOException("WAV يتجاوز حد 4 جيجابايت");try(OutputStream out=new BufferedOutputStream(new FileOutputStream(output));InputStream in=new FileInputStream(pcm)){out.write("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));le(out,size+36,4);out.write("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));le(out,16,4);le(out,1,2);le(out,2,2);le(out,44100,4);le(out,176400,4);le(out,4,2);le(out,16,2);out.write("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));le(out,size,4);FilesUtil.copy(in,out);}}
 private static void le(OutputStream out,long value,int bytes)throws IOException{for(int i=0;i<bytes;i++)out.write((int)(value>>(8*i))&255);}
 private static void adts(File source,File output,AtomicBoolean cancel)throws Exception{MediaExtractor ex=new MediaExtractor();try(OutputStream out=new BufferedOutputStream(new FileOutputStream(output))){ex.setDataSource(source.getAbsolutePath());int track=AudioPipeline.track(ex,"audio/");ex.selectTrack(track);ByteBuffer b=ByteBuffer.allocate(1024*1024);while(true){MusicSeparator.check(cancel);b.clear();int n=ex.readSampleData(b,0);if(n<0)break;int len=n+7;byte[] h={(byte)255,(byte)241,(byte)((1<<6)|(4<<2)),(byte)((2<<6)|(len>>11)),(byte)(len>>3),(byte)(((len&7)<<5)|31),(byte)252};out.write(h);byte[] sample=new byte[n];b.position(0);b.get(sample);out.write(sample);ex.advance();}}finally{ex.release();}}
}
