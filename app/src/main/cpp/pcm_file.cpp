// Madar's bounded file-only fallback for containers whose audio Android omits.
#include <jni.h>
#include <cstdio>
#include <climits>
#include <cmath>
#include <vector>
#include <algorithm>
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libswresample/swresample.h>
}

extern "C" JNIEXPORT jlong JNICALL
Java_androidx_media3_decoder_ffmpeg_FfmpegPcm_decodeFile(
    JNIEnv* env,jclass,jstring source,jstring output,jlong start,jlong end,jobject cancel) {
 AVFormatContext* format=nullptr;AVCodecContext* decoder=nullptr;SwrContext* resampler=nullptr;
 AVPacket* packet=nullptr;AVFrame* frame=nullptr;FILE* file=nullptr;
 const char* inputPath=env->GetStringUTFChars(source,nullptr);
 const char* outputPath=env->GetStringUTFChars(output,nullptr);
 const char* failure=nullptr;bool stopped=false;int64_t first=-1,next=0,written=0;
 jclass cancelClass=env->GetObjectClass(cancel);jmethodID get=env->GetMethodID(cancelClass,"get","()Z");
 auto cancelled=[&](){return env->CallBooleanMethod(cancel,get)||env->ExceptionCheck();};
 do {
  if(start<0||end<=start){failure="Invalid audio range";break;}
  // The FFmpeg build enables only the file protocol. Never accept a URL here.
  if(avformat_open_input(&format,inputPath,nullptr,nullptr)<0||avformat_find_stream_info(format,nullptr)<0){failure="Cannot read audio container";break;}
  int track=av_find_best_stream(format,AVMEDIA_TYPE_AUDIO,-1,-1,nullptr,0);
  if(track<0){failure="No decodable audio track";break;}
  AVStream* stream=format->streams[track];const AVCodec* codec=avcodec_find_decoder(stream->codecpar->codec_id);
  if(!codec){failure="Audio codec unavailable";break;}
  decoder=avcodec_alloc_context3(codec);
  if(!decoder||avcodec_parameters_to_context(decoder,stream->codecpar)<0||avcodec_open2(decoder,codec,nullptr)<0){failure="Cannot initialize audio decoder";break;}
  if(start>0){int64_t seek=av_rescale_q(std::max<int64_t>(0,start-1000000),AV_TIME_BASE_Q,stream->time_base);avformat_seek_file(format,track,INT64_MIN,seek,seek,0);avcodec_flush_buffers(decoder);}
  file=fopen(outputPath,"wb");packet=av_packet_alloc();frame=av_frame_alloc();
  if(!file||!packet||!frame){failure="Cannot create audio output";break;}
  bool done=false,flushed=false;
  while(!done&&!failure){
   if(cancelled()){stopped=true;break;}
   int read=av_read_frame(format,packet);
   if(read>=0&&packet->stream_index!=track){av_packet_unref(packet);continue;}
   if(read<0&&flushed)break;
   int sent=avcodec_send_packet(decoder,read<0?nullptr:packet);av_packet_unref(packet);if(read<0)flushed=true;
   if(sent<0&&sent!=AVERROR_EOF){failure="Cannot decode audio packet";break;}
   int received;
   while((received=avcodec_receive_frame(decoder,frame))>=0){
    if(cancelled()){stopped=true;done=true;break;}
    if(!resampler){AVChannelLayout stereo=AV_CHANNEL_LAYOUT_STEREO;
     if(swr_alloc_set_opts2(&resampler,&stereo,AV_SAMPLE_FMT_S16,44100,&frame->ch_layout,static_cast<AVSampleFormat>(frame->format),frame->sample_rate,0,nullptr)<0||swr_init(resampler)<0){failure="Cannot convert decoded audio";break;}}
    int64_t delayed=av_rescale_q(swr_get_delay(resampler,frame->sample_rate),AVRational{1,frame->sample_rate},AV_TIME_BASE_Q);
    int capacity=swr_get_out_samples(resampler,frame->nb_samples);std::vector<int16_t> pcm(static_cast<size_t>(capacity)*2);uint8_t* bytes=reinterpret_cast<uint8_t*>(pcm.data());
    int count=swr_convert(resampler,&bytes,capacity,const_cast<const uint8_t**>(frame->extended_data),frame->nb_samples);
    if(count<0){failure="Cannot resample decoded audio";break;}
    int64_t timestamp=frame->best_effort_timestamp==AV_NOPTS_VALUE?next:av_rescale_q(frame->best_effort_timestamp,stream->time_base,AV_TIME_BASE_Q);
    // Account for the resampler's retained input so boundaries stay on the media clock.
    int64_t at=timestamp-delayed;int skip=static_cast<int>(std::clamp<int64_t>(av_rescale_rnd(start-at,44100,1000000,AV_ROUND_UP),0,count));
    int keep=static_cast<int>(std::clamp<int64_t>(end==LLONG_MAX?count:av_rescale_rnd(end-at,44100,1000000,AV_ROUND_UP),skip,count))-skip;
    if(keep>0){if(first<0)first=std::max<int64_t>(0,at+av_rescale_q(skip,AVRational{1,44100},AV_TIME_BASE_Q));if(fwrite(pcm.data()+2*skip,4,keep,file)!=static_cast<size_t>(keep)){failure="Cannot write decoded audio";break;}written+=keep;}
    next=timestamp+av_rescale_q(frame->nb_samples,AVRational{1,frame->sample_rate},AV_TIME_BASE_Q);if(next>=end)done=true;av_frame_unref(frame);
   }
   if(received!=AVERROR(EAGAIN)&&received!=AVERROR_EOF&&received<0&&!failure)failure="Cannot read decoded audio";
  }
  if(!failure&&!stopped&&written==0)failure="Decoded audio is empty";
 } while(false);
 if(file)fclose(file);av_frame_free(&frame);av_packet_free(&packet);swr_free(&resampler);avcodec_free_context(&decoder);avformat_close_input(&format);
 if(failure||stopped)remove(outputPath);env->ReleaseStringUTFChars(source,inputPath);env->ReleaseStringUTFChars(output,outputPath);env->DeleteLocalRef(cancelClass);
 if(!env->ExceptionCheck()&&(failure||stopped))env->ThrowNew(env->FindClass(stopped?"java/util/concurrent/CancellationException":"java/io/IOException"),stopped?"Audio decoding cancelled":failure);
 return std::max<int64_t>(0,first);
}
