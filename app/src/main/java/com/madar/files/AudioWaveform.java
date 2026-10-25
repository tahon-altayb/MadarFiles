package com.madar.files;

import android.media.*;
import java.io.*;
import java.nio.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Streaming peak envelope. Does not save PCM or invoke separation/AI engines. */
final class AudioWaveform {
 static JSONObject read(File source,BooleanSupplier cancelled)throws Exception{return read(source,cancelled,2400);}
 static JSONObject read(File source,BooleanSupplier cancelled,int maxBins)throws Exception{
  MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;
  try{
   check(cancelled);extractor.setDataSource(source.getPath());int track=-1;
   for(int i=0;i<extractor.getTrackCount();i++)if(extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/")){track=i;break;}
   if(track<0)return new JSONObject().put("hasAudio",false).put("peaks",new JSONArray());
   extractor.selectTrack(track);MediaFormat format=extractor.getTrackFormat(track);long duration=format.containsKey(MediaFormat.KEY_DURATION)?format.getLong(MediaFormat.KEY_DURATION):0;
   try(MediaMetadataRetriever metadata=new MediaMetadataRetriever()){metadata.setDataSource(source.getPath());String value=metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);if(value!=null)duration=Math.max(duration,Long.parseLong(value)*1000L);}
   if(duration<=0||duration>7200000000L)throw new IOException("الموجة متاحة لملف مدته حتى ساعتين");
   int count=(int)Math.max(32,Math.min(maxBins,duration/50000));float[] peaks=new float[count];
   int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),encoding=AudioFormat.ENCODING_PCM_16BIT;
   codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));codec.configure(format,null,null,0);codec.start();
   boolean inputDone=false,outputDone=false;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long began=android.os.SystemClock.elapsedRealtime();
   while(!outputDone){
    check(cancelled);if(android.os.SystemClock.elapsedRealtime()-began>120000)throw new IOException("استغرق تحليل الصوت وقتًا طويلًا؛ جرّب مقطعًا أقصر");
    if(!inputDone){int index=codec.dequeueInputBuffer(10000);if(index>=0){ByteBuffer buffer=codec.getInputBuffer(index);buffer.clear();int n=extractor.readSampleData(buffer,0);long pts=extractor.getSampleTime();if(n<0){codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{codec.queueInputBuffer(index,0,n,pts,0);extractor.advance();}}}
    int index=codec.dequeueOutputBuffer(info,10000);
    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat output=codec.getOutputFormat();rate=output.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=output.getInteger(MediaFormat.KEY_CHANNEL_COUNT);encoding=output.containsKey(MediaFormat.KEY_PCM_ENCODING)?output.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;}
    else if(index>=0){try{if(info.size>0){if(rate<=0||channels<1||channels>8||(encoding!=AudioFormat.ENCODING_PCM_16BIT&&encoding!=AudioFormat.ENCODING_PCM_FLOAT))throw new IOException("صيغة الصوت غير مدعومة للموجة");ByteBuffer buffer=codec.getOutputBuffer(index).duplicate().order(ByteOrder.LITTLE_ENDIAN);buffer.position(info.offset);buffer.limit(info.offset+info.size);accumulate(peaks,buffer,channels,rate,encoding==AudioFormat.ENCODING_PCM_FLOAT,info.presentationTimeUs,duration);}
     outputDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
    }finally{codec.releaseOutputBuffer(index,false);}}
   }
   check(cancelled);JSONArray values=new JSONArray();for(float peak:peaks)values.put(Math.round(peak*1000)/1000.0);
   return new JSONObject().put("hasAudio",true).put("duration",duration/1000000.0).put("peaks",values);
  }finally{if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}extractor.release();}
 }
 static void accumulate(float[] peaks,ByteBuffer buffer,int channels,int rate,boolean floating,long startUs,long durationUs){
  int stride=(floating?4:2)*channels,frames=buffer.remaining()/stride;
  for(int frame=0;frame<frames;frame++){float peak=0;for(int ch=0;ch<channels;ch++){float sample=floating?buffer.getFloat():buffer.getShort()/32768f;if(Float.isFinite(sample))peak=Math.max(peak,Math.min(1,Math.abs(sample)));}long time=startUs+frame*1000000L/rate;if(time>=0&&time<durationUs){int bin=(int)(time*peaks.length/durationUs);peaks[bin]=Math.max(peaks[bin],peak);}}
 }
 private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();}
}
