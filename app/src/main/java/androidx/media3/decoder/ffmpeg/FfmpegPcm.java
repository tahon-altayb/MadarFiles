package androidx.media3.decoder.ffmpeg;

import android.media.*;
import androidx.media3.common.*;
import androidx.media3.decoder.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Madar's file-only PCM adapter for the official Media3 decoder. */
@androidx.media3.common.util.UnstableApi
public final class FfmpegPcm {
 public static final class Result {public final int rate;public final long offsetUs;Result(int r,long o){rate=r;offsetUs=o;}}
 public static Result decode(File source,File output,long startUs,long endUs,boolean exact,AtomicBoolean cancel)throws Exception{
  return decodeInput(ex->ex.setDataSource(source.getPath()),output,startUs,endUs,exact,cancel);
 }
 public static Result decodeRemote(android.net.Uri uri,Map<String,String> headers,File output,long startUs,long endUs,AtomicBoolean cancel)throws Exception{
  return decodeInput(ex->ex.setDataSource(uri.toString(),headers),output,startUs,endUs,true,cancel);
 }
 private interface Input {void open(MediaExtractor extractor)throws IOException;}
 private static Result decodeInput(Input source,File output,long startUs,long endUs,boolean exact,AtomicBoolean cancel)throws Exception{
  MediaExtractor extractor=new MediaExtractor();FfmpegAudioDecoder decoder=null;
  try(OutputStream out=new BufferedOutputStream(new FileOutputStream(output))){
   source.open(extractor);int track=-1;for(int i=0;i<extractor.getTrackCount();i++)if(extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/")){track=i;break;}if(track<0)throw new IOException("الملف لا يحتوي على مسار صوت قابل للقراءة");extractor.selectTrack(track);MediaFormat media=extractor.getTrackFormat(track);String mime=media.getString(MediaFormat.KEY_MIME);if(!FfmpegLibrary.supportsFormat(mime))throw new IOException("ترميز الصوت غير مدعوم: "+mime);
   List<byte[]> initialization=new ArrayList<>();for(int i=0;i<8&&media.containsKey("csd-"+i);i++){ByteBuffer data=media.getByteBuffer("csd-"+i).duplicate();byte[] bytes=new byte[data.remaining()];data.get(bytes);initialization.add(bytes);}
   Format format=new Format.Builder().setSampleMimeType(mime).setSampleRate(media.getInteger(MediaFormat.KEY_SAMPLE_RATE)).setChannelCount(media.getInteger(MediaFormat.KEY_CHANNEL_COUNT)).setInitializationData(initialization).build();decoder=new FfmpegAudioDecoder(format,4,4,262144,false);if(startUs>0)extractor.seekTo(Math.max(0,startUs-1000000),MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
   boolean sentEnd=false,finished=false;int rate=format.sampleRate;long offset=-1,deadline=android.os.SystemClock.elapsedRealtime()+30L*60*1000;
   while(!finished){if(cancel.get()||Thread.currentThread().isInterrupted())throw new CancellationException();if(android.os.SystemClock.elapsedRealtime()>deadline)throw new IOException("انتهت مهلة فك الصوت البرمجي");boolean progressed=false;
    if(!sentEnd){DecoderInputBuffer input=decoder.dequeueInputBuffer();if(input!=null){progressed=true;input.clear();input.ensureSpaceForWrite(262144);long pts=extractor.getSampleTime();int size=pts<0||endUs>0&&pts>=endUs?-1:extractor.readSampleData(input.data,0);if(size<0){input.addFlag(C.BUFFER_FLAG_END_OF_STREAM);sentEnd=true;}else{input.timeUs=pts;input.data.position(size);extractor.advance();}input.flip();decoder.queueInputBuffer(input);}}
    SimpleDecoderOutputBuffer decoded=decoder.dequeueOutputBuffer();if(decoded!=null){progressed=true;try{if(decoded.isEndOfStream()){finished=true;continue;}int channels=decoder.getChannelCount();rate=decoder.getSampleRate();if(channels<1||channels>32||rate<8000||rate>384000)throw new IOException("صيغة صوت برمجية غير صالحة");ByteBuffer bytes=decoded.data.duplicate().order(ByteOrder.LITTLE_ENDIAN);int count=bytes.remaining()/(2*channels),skip=exact?(int)Math.max(0,Math.min(count,Math.ceil((startUs-decoded.timeUs)*rate/1000000.0))):0,stop=exact?(int)Math.max(skip,Math.min(count,Math.ceil((endUs-decoded.timeUs)*rate/1000000.0))):count;bytes.position(bytes.position()+skip*channels*2);int keep=stop-skip;if(keep>0&&offset<0)offset=Math.max(0,decoded.timeUs+Math.round(skip*1000000.0/rate));byte[] pcm=new byte[keep*4];for(int frame=0;frame<keep;frame++){float left=0,right=0;for(int ch=0;ch<channels;ch++){short value=bytes.getShort();if(ch==0)left+=value;else if(ch==1)right+=value;else if(ch==2){left+=.7071f*value;right+=.7071f*value;}else if(ch==3){left+=.25f*value;right+=.25f*value;}else if(ch%2==0)left+=.5f*value;else right+=.5f*value;}if(channels==1)right=left;float scale=channels>2?1f/(1f+.7071f+(channels>3?.25f:0)+(channels>4?.5f:0)+(channels>6?.5f*(channels-6):0)):1;short l=(short)Math.round(Math.max(-32767,Math.min(32767,left*scale))),r=(short)Math.round(Math.max(-32767,Math.min(32767,right*scale)));pcm[frame*4]=(byte)l;pcm[frame*4+1]=(byte)(l>>8);pcm[frame*4+2]=(byte)r;pcm[frame*4+3]=(byte)(r>>8);}out.write(pcm);}finally{decoded.release();}}
    if(!progressed)Thread.sleep(1);
   }
   out.flush();if(output.length()==0)throw new IOException("لم ينتج فك الترميز البرمجي صوتًا");return new Result(rate,Math.max(0,offset));
  }catch(Exception error){output.delete();throw error;}finally{if(decoder!=null)decoder.release();extractor.release();}
 }
 private FfmpegPcm(){}
}
