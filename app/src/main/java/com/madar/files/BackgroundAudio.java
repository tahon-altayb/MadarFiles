package com.madar.files;
import android.content.Context;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
/** Mixes a chosen music track into the final video clock, retaining the original picture. */
final class BackgroundAudio {
 static File resolve(Context c,String name)throws IOException{if(!name.matches("madar_bed_[0-9]+\\.media"))throw new IOException("مرفق الموسيقى غير صالح");File f=new File(c.getCacheDir(),name);if(!f.isFile())throw new IOException("أرفق ملف الموسيقى مجددًا");return f;}
 static float[] samples(RandomAccessFile input,int rate,long from,int count,long total)throws IOException{
  float[] result=new float[count*2];long frames=(long)Math.floor(total*44100.0/rate);int leading=(int)Math.min(count,Math.max(0,-from)),valid=(int)Math.max(0,Math.min(count-leading,frames-Math.max(0,from)));if(valid>0){float[] values=PcmSamples.read(input,rate,Math.max(0,from),valid,total);System.arraycopy(values,0,result,leading*2,values.length);}return result;
 }
 static File mix(Context c,File video,JSONObject options,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception{
  AudioPipeline.Decoded original=null,bed=null;File pcm=null,out=null;boolean success=false;
  try{File music=resolve(c,options.getString("bedName"));double start=options.optDouble("bedStart",0),gain=options.optDouble("bedGain",25)/100;if(!Double.isFinite(start)||start<0||!Double.isFinite(gain)||gain<0||gain>2)throw new IOException("إعدادات الموسيقى غير صالحة");
   double duration;try(android.media.MediaMetadataRetriever mmr=new android.media.MediaMetadataRetriever()){mmr.setDataSource(video.getPath());duration=Double.parseDouble(mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION))/1000;}
   double end=options.optDouble("bedEnd",duration);if(!Double.isFinite(end)||end<=start||end>duration+.15||start>=duration)throw new IOException("وقت الموسيقى خارج مدة المونتاج");end=Math.min(end,duration);
   android.media.MediaExtractor check=new android.media.MediaExtractor();boolean hasAudio;try{check.setDataSource(video.getPath());hasAudio=AudioPipeline.track(check,"audio/")>=0;}finally{check.release();}
   if(hasAudio)original=AudioPipeline.decode(c,video,0,cancel,progress);bed=AudioPipeline.decode(c,music,Math.round((end-start)*1000000),cancel,progress);pcm=File.createTempFile("madar_mix_",".pcm",c.getCacheDir());long frames=Math.round(duration*44100),bedFrames=bed.pcm.length()/4,originalFrames=original==null?0:original.pcm.length()/4;long offset=original==null?0:Math.round(original.offsetUs*44100/1000000.0);
   try(RandomAccessFile main=original==null?null:new RandomAccessFile(original.pcm,"r");RandomAccessFile overlay=new RandomAccessFile(bed.pcm,"r");OutputStream output=new BufferedOutputStream(new FileOutputStream(pcm))){for(long at=0;at<frames;at+=4410){ColabProbe.check(cancel);int count=(int)Math.min(4410,frames-at);float[] base=main==null?new float[count*2]:samples(main,original.sampleRate,at-offset,count,originalFrames);long bedAt=at-Math.round(start*44100);float[] added=samples(overlay,bed.sampleRate,bedAt,count,bedFrames);byte[] bytes=new byte[count*4];for(int i=0;i<count;i++){double time=(at+i)/44100.0,fade=Math.max(0,Math.min(1,Math.min((time-start)/.15,(end-time)/.15)));for(int channel=0;channel<2;channel++){short v=(short)Math.round(Math.max(-1,Math.min(1,base[i*2+channel]+added[i*2+channel]*gain*fade))*32767);bytes[i*4+channel*2]=(byte)v;bytes[i*4+channel*2+1]=(byte)(v>>8);}}output.write(bytes);progress.update((int)(85*at/Math.max(1,frames)),"مزج الموسيقى مع المونتاج");}}
   out=FilesUtil.output(c,"mp4");AudioPipeline.mux(video,pcm,out,0,0,false,cancel,progress);ColabProbe.check(cancel);success=true;return out;
  }finally{if(original!=null)original.pcm.delete();if(bed!=null)bed.pcm.delete();if(pcm!=null)pcm.delete();if(!success&&out!=null)out.delete();}
 }
}
