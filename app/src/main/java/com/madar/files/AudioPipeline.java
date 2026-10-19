package com.madar.files;

import android.content.Context;
import android.media.*;
import java.io.*;
import java.nio.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class AudioPipeline {
 static final class Decoded {File pcm;int sampleRate;long offsetUs;Decoded(File f,int r,long o){pcm=f;sampleRate=r;offsetUs=o;}}
 static int track(MediaExtractor ex,String prefix){for(int i=0;i<ex.getTrackCount();i++)if(ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith(prefix))return i;return -1;}
 static Decoded decode(Context c,File source,long limitUs,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception{
  MediaExtractor ex=new MediaExtractor();MediaCodec codec=null;File raw=File.createTempFile("madar_decode_",".pcm",c.getCacheDir());
  try(OutputStream out=new BufferedOutputStream(new FileOutputStream(raw))){
   ex.setDataSource(source.getAbsolutePath());int t=track(ex,"audio/");if(t<0)throw new IOException("الفيديو لا يحتوي على مسار صوت");ex.selectTrack(t);MediaFormat format=ex.getTrackFormat(t);int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),encoding=android.media.AudioFormat.ENCODING_PCM_16BIT;
   codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));codec.configure(format,null,null,0);codec.start();boolean inputDone=false,outputDone=false;long offset=-1;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long began=System.currentTimeMillis();
   while(!outputDone){MusicSeparator.check(cancel);if(System.currentTimeMillis()-began>30L*60*1000)throw new IOException("استغرق فك الصوت وقتًا طويلًا");
    if(!inputDone){int index=codec.dequeueInputBuffer(10000);if(index>=0){ByteBuffer buffer=codec.getInputBuffer(index);buffer.clear();int n=ex.readSampleData(buffer,0);long time=ex.getSampleTime();if(n<0||(limitUs>0&&time>=limitUs)){codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{codec.queueInputBuffer(index,0,n,time,0);ex.advance();}}}
    int index=codec.dequeueOutputBuffer(info,10000);if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat f=codec.getOutputFormat();rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);encoding=f.containsKey(MediaFormat.KEY_PCM_ENCODING)?f.getInteger(MediaFormat.KEY_PCM_ENCODING):android.media.AudioFormat.ENCODING_PCM_16BIT;if(encoding!=android.media.AudioFormat.ENCODING_PCM_16BIT&&encoding!=android.media.AudioFormat.ENCODING_PCM_FLOAT)throw new IOException("ترميز PCM غير مدعوم");}
    else if(index>=0){if(info.size>0){if(offset<0)offset=Math.max(0,info.presentationTimeUs);ByteBuffer b=codec.getOutputBuffer(index).duplicate().order(ByteOrder.LITTLE_ENDIAN);b.position(info.offset);b.limit(info.offset+info.size);int stride=encoding==android.media.AudioFormat.ENCODING_PCM_FLOAT?4:2;int frames=b.remaining()/(stride*channels);byte[] converted=new byte[frames*4];for(int i=0;i<frames;i++){float left=0,right=0;for(int ch=0;ch<channels;ch++){float v=encoding==android.media.AudioFormat.ENCODING_PCM_FLOAT?Math.max(-1,Math.min(1,b.getFloat()))*32767:b.getShort();if(ch==0)left+=v;else if(ch==1)right+=v;else if(ch==2){left+=.7071f*v;right+=.7071f*v;}else if(ch==3){left+=.25f*v;right+=.25f*v;}else if(ch%2==0)left+=.5f*v;else right+=.5f*v;}if(channels==1)right=left;float scale=channels>2?1f/(1f+.7071f+(channels>3?.25f:0)+(channels>4?.5f:0)+(channels>6?.5f*(channels-6):0)):1;short l=(short)Math.round(Math.max(-32767,Math.min(32767,left*scale))),r=(short)Math.round(Math.max(-32767,Math.min(32767,right*scale)));converted[4*i]=(byte)l;converted[4*i+1]=(byte)(l>>8);converted[4*i+2]=(byte)r;converted[4*i+3]=(byte)(r>>8);}out.write(converted);}outputDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(index,false);}
   }
   out.flush();if(raw.length()==0)throw new IOException("لم ينتج ملف صوت صالح");progress.update(8,"اكتمل فك الصوت");return new Decoded(raw,rate,Math.max(0,offset));
  }catch(Exception|OutOfMemoryError e){raw.delete();throw e;}finally{if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}ex.release();}
 }
 static void mux(File source,File clean,File output,long audioOffsetUs,long limitUs,boolean audioOnly,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception{
  MediaExtractor video=new MediaExtractor();MediaCodec encoder=null;MediaMuxer muxer=null;boolean muxStarted=false;
  try(InputStream pcm=new BufferedInputStream(new FileInputStream(clean))){
   video.setDataSource(source.getAbsolutePath());int videoIndex=audioOnly?-1:track(video,"video/");
   muxer=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);int videoTrack=-1;if(videoIndex>=0){video.selectTrack(videoIndex);MediaFormat f=video.getTrackFormat(videoIndex);videoTrack=muxer.addTrack(f);if(f.containsKey(MediaFormat.KEY_ROTATION))muxer.setOrientationHint(f.getInteger(MediaFormat.KEY_ROTATION));}
   MediaFormat format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,44100,2);format.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);format.setInteger(MediaFormat.KEY_BIT_RATE,192000);format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384);
   encoder=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start();boolean inputDone=false,outputDone=false;long frames=0;int audioTrack=-1;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();byte[] bytes=new byte[16384];
   while(!outputDone){MusicSeparator.check(cancel);
    if(!inputDone){int index=encoder.dequeueInputBuffer(10000);if(index>=0){ByteBuffer buffer=encoder.getInputBuffer(index);buffer.clear();int n=pcm.read(bytes,0,Math.min(bytes.length,buffer.remaining()/4*4));long pts=audioOffsetUs+frames*1000000L/44100;if(n<0){encoder.queueInputBuffer(index,0,0,pts,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{buffer.put(bytes,0,n);encoder.queueInputBuffer(index,0,n,pts,0);frames+=n/4;}}}
    int index=encoder.dequeueOutputBuffer(info,10000);if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){audioTrack=muxer.addTrack(encoder.getOutputFormat());muxer.start();muxStarted=true;}
    else if(index>=0){if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0)info.size=0;if(info.size>0){ByteBuffer buffer=encoder.getOutputBuffer(index);buffer.position(info.offset);buffer.limit(info.offset+info.size);muxer.writeSampleData(audioTrack,buffer,info);}outputDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;encoder.releaseOutputBuffer(index,false);}
   }
   if(videoTrack>=0){ByteBuffer buffer=ByteBuffer.allocateDirect(16*1024*1024);while(true){MusicSeparator.check(cancel);long pts=video.getSampleTime();if(pts<0||(limitUs>0&&pts>=limitUs))break;int n=video.readSampleData(buffer,0);if(n<0)break;if((video.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)throw new IOException("لا يمكن معالجة الفيديو المحمي");int flags=(video.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0;info.set(0,n,pts,flags);muxer.writeSampleData(videoTrack,buffer,info);video.advance();}}
   progress.update(99,"حفظ الملف بالصوت المعالج");
  }finally{if(encoder!=null){try{encoder.stop();}catch(Exception ignored){}encoder.release();}video.release();if(muxer!=null){try{if(muxStarted)muxer.stop();}finally{muxer.release();}}}
 }
}
