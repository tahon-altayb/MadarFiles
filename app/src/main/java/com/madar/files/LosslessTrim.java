package com.madar.files;
import android.content.Context;
import android.media.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
final class LosslessTrim {
 static final class Result{File file;long startUs;Result(File f,long start){file=f;startUs=start;}}
 static Result trim(Context c,File source,double start,double end,AtomicBoolean cancel)throws Exception{
  if(!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<=start)throw new IOException("حدود القص غير صالحة");MediaExtractor probe=new MediaExtractor();File output=FilesUtil.output(c,"mp4");MediaMuxer mux=null;boolean started=false;
  try{probe.setDataSource(source.getPath());int video=AudioPipeline.track(probe,"video/");if(video<0)throw new IOException("لا يوجد فيديو");probe.selectTrack(video);probe.seekTo(Math.round(start*1000000),MediaExtractor.SEEK_TO_PREVIOUS_SYNC);long base=probe.getSampleTime();if(base<0)throw new IOException("تعذر إيجاد بداية صالحة");long endUs=Math.round(end*1000000);mux=new MediaMuxer(output.getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);List<Integer> inputs=new ArrayList<>(),outputs=new ArrayList<>();
   for(int i=0;i<probe.getTrackCount();i++){MediaFormat f=probe.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(!mime.startsWith("video/")&&!mime.startsWith("audio/"))continue;inputs.add(i);outputs.add(mux.addTrack(f));if(mime.startsWith("video/")&&f.containsKey(MediaFormat.KEY_ROTATION))mux.setOrientationHint(f.getInteger(MediaFormat.KEY_ROTATION));}mux.start();started=true;ByteBuffer bytes=ByteBuffer.allocateDirect(16*1024*1024);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
   for(int index=0;index<inputs.size();index++){MediaExtractor ex=new MediaExtractor();try{ex.setDataSource(source.getPath());ex.selectTrack(inputs.get(index));ex.seekTo(base,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);while(true){MusicSeparator.check(cancel);long pts=ex.getSampleTime();if(pts<0||pts>=endUs)break;if(pts<base){ex.advance();continue;}bytes.clear();int n=ex.readSampleData(bytes,0);if(n<0)break;if((ex.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)throw new IOException("الفيديو محمي");info.set(0,n,pts-base,(ex.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0);mux.writeSampleData(outputs.get(index),bytes,info);ex.advance();}}finally{ex.release();}}
   mux.stop();started=false;mux.release();mux=null;return new Result(output,base);
  }catch(Exception e){output.delete();throw e;}finally{probe.release();if(mux!=null){try{if(started)mux.stop();}catch(Exception ignored){}mux.release();}}
 }
}
