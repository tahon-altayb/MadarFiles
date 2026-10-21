#include <jni.h>
#include <vector>
#include <string>
#include <algorithm>
#include "c-api.h"
static void fail(JNIEnv* e,const char* text){e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),text);}
extern "C" JNIEXPORT jlong JNICALL Java_com_madar_files_MusicSeparator_open(JNIEnv* e,jclass,jstring path){
 const char* p=e->GetStringUTFChars(path,nullptr);SherpaOnnxOfflineSourceSeparationConfig config{};
 config.model.uvr.model=p;config.model.num_threads=2;config.model.provider="cpu";
 auto* engine=SherpaOnnxCreateOfflineSourceSeparation(&config);e->ReleaseStringUTFChars(path,p);
 if(!engine)fail(e,"تعذر تحميل نموذج فصل الموسيقى");return reinterpret_cast<jlong>(engine);
}
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_madar_files_MusicSeparator_process(JNIEnv* e,jclass,jlong handle,jfloatArray samples){
 auto* engine=reinterpret_cast<const SherpaOnnxOfflineSourceSeparation*>(handle);if(!engine){fail(e,"محرك الفصل غير متاح");return nullptr;}
 const int count=e->GetArrayLength(samples),frames=count/2;std::vector<float> inter(count),left(frames),right(frames);e->GetFloatArrayRegion(samples,0,count,inter.data());
 for(int i=0;i<frames;i++){left[i]=inter[2*i];right[i]=inter[2*i+1];}const float* channels[]={left.data(),right.data()};
 auto* output=SherpaOnnxOfflineSourceSeparationProcess(engine,channels,2,frames,44100);
 if(!output||output->num_stems<1){if(output)SherpaOnnxDestroySourceSeparationOutput(output);fail(e,"لم يكتمل فصل الصوت");return nullptr;}
 // UVR vocal models expose vocals as stem zero; retain its original time alignment.
 const auto& stem=output->stems[0];if(output->sample_rate!=44100||stem.n<frames||stem.num_channels<1){SherpaOnnxDestroySourceSeparationOutput(output);fail(e,"صيغة صوت غير متوقعة من النموذج");return nullptr;}
 for(int i=0;i<frames;i++){inter[2*i]=stem.samples[0][i];inter[2*i+1]=stem.samples[std::min(1,stem.num_channels-1)][i];}
 jfloatArray result=e->NewFloatArray(count);if(result)e->SetFloatArrayRegion(result,0,count,inter.data());SherpaOnnxDestroySourceSeparationOutput(output);return result;
}
extern "C" JNIEXPORT void JNICALL Java_com_madar_files_MusicSeparator_close(JNIEnv*,jclass,jlong handle){if(handle)SherpaOnnxDestroyOfflineSourceSeparation(reinterpret_cast<const SherpaOnnxOfflineSourceSeparation*>(handle));}
extern "C" JNIEXPORT jlong JNICALL Java_com_madar_files_SpeechProcessor_open(JNIEnv* e,jclass,jstring encoder,jstring decoder,jstring tokens,jstring language){
 const char* a=e->GetStringUTFChars(encoder,nullptr);const char* b=e->GetStringUTFChars(decoder,nullptr);const char* t=e->GetStringUTFChars(tokens,nullptr);const char* l=e->GetStringUTFChars(language,nullptr);
 SherpaOnnxOfflineRecognizerConfig c{};c.feat_config.sample_rate=16000;c.feat_config.feature_dim=80;c.model_config.whisper.encoder=a;c.model_config.whisper.decoder=b;c.model_config.whisper.language=l;c.model_config.whisper.task="transcribe";c.model_config.whisper.tail_paddings=-1;c.model_config.whisper.enable_segment_timestamps=1;c.model_config.tokens=t;c.model_config.provider="cpu";c.model_config.num_threads=2;c.decoding_method="greedy_search";
 auto* r=SherpaOnnxCreateOfflineRecognizer(&c);e->ReleaseStringUTFChars(encoder,a);e->ReleaseStringUTFChars(decoder,b);e->ReleaseStringUTFChars(tokens,t);e->ReleaseStringUTFChars(language,l);if(!r)fail(e,"تعذر تحميل نموذج التفريغ");return reinterpret_cast<jlong>(r);
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_madar_files_SpeechProcessor_recognize(JNIEnv* e,jclass,jlong handle,jfloatArray audio){
 auto* r=reinterpret_cast<const SherpaOnnxOfflineRecognizer*>(handle);auto* stream=SherpaOnnxCreateOfflineStream(r);int n=e->GetArrayLength(audio);std::vector<float> samples(n);e->GetFloatArrayRegion(audio,0,n,samples.data());SherpaOnnxAcceptWaveformOffline(stream,16000,samples.data(),n);SherpaOnnxDecodeOfflineStream(r,stream);auto* result=SherpaOnnxGetOfflineStreamResult(stream);std::vector<std::string> values;
 values.push_back(result&&result->lang?result->lang:"");
 if(result){if(result->segment_count>0&&result->segment_timestamps&&result->segment_durations){for(int i=0;i<result->segment_count;i++){values.push_back(std::to_string(result->segment_timestamps[i]));values.push_back(std::to_string(result->segment_durations[i]));values.push_back(result->segment_texts_arr[i]?result->segment_texts_arr[i]:"");}}else{values.insert(values.end(),{"0",std::to_string(n/16000.0),result->text?result->text:""});}}
 auto out=e->NewObjectArray(values.size(),e->FindClass("java/lang/String"),nullptr);for(int i=0;i<(int)values.size();i++){jstring s=e->NewStringUTF(values[i].c_str());e->SetObjectArrayElement(out,i,s);e->DeleteLocalRef(s);}if(result)SherpaOnnxDestroyOfflineRecognizerResult(result);SherpaOnnxDestroyOfflineStream(stream);return out;
}
extern "C" JNIEXPORT void JNICALL Java_com_madar_files_SpeechProcessor_close(JNIEnv*,jclass,jlong handle){if(handle)SherpaOnnxDestroyOfflineRecognizer(reinterpret_cast<const SherpaOnnxOfflineRecognizer*>(handle));}
extern "C" JNIEXPORT jlong JNICALL Java_com_madar_files_SpeechActivity_open(JNIEnv* e,jclass,jstring model){
 const char* path=e->GetStringUTFChars(model,nullptr);SherpaOnnxVadModelConfig c{};c.silero_vad.model=path;c.silero_vad.threshold=.5f;c.silero_vad.min_silence_duration=.35f;c.silero_vad.min_speech_duration=.15f;c.silero_vad.max_speech_duration=20;c.silero_vad.window_size=512;c.sample_rate=16000;c.num_threads=1;c.provider="cpu";
 auto* vad=SherpaOnnxCreateVoiceActivityDetector(&c,30);e->ReleaseStringUTFChars(model,path);if(!vad)fail(e,"تعذر تحميل كاشف الكلام");return reinterpret_cast<jlong>(vad);
}
extern "C" JNIEXPORT jintArray JNICALL Java_com_madar_files_SpeechActivity_regions(JNIEnv* e,jclass,jlong handle,jfloatArray audio){
 auto* vad=reinterpret_cast<const SherpaOnnxVoiceActivityDetector*>(handle);if(!vad){fail(e,"كاشف الكلام غير متاح");return nullptr;}int n=e->GetArrayLength(audio);if(n>320000){fail(e,"نافذة كشف الكلام طويلة");return nullptr;}std::vector<float> samples(n);e->GetFloatArrayRegion(audio,0,n,samples.data());SherpaOnnxVoiceActivityDetectorReset(vad);std::vector<jint> ranges;
 for(int at=0;at<n;at+=512)SherpaOnnxVoiceActivityDetectorAcceptWaveform(vad,samples.data()+at,std::min(512,n-at));SherpaOnnxVoiceActivityDetectorFlush(vad);
 while(!SherpaOnnxVoiceActivityDetectorEmpty(vad)){auto* part=SherpaOnnxVoiceActivityDetectorFront(vad);if(part){ranges.push_back(std::max(0,part->start));ranges.push_back(std::min(n,part->start+part->n));SherpaOnnxDestroySpeechSegment(part);}SherpaOnnxVoiceActivityDetectorPop(vad);}
 auto out=e->NewIntArray(ranges.size());if(out&&!ranges.empty())e->SetIntArrayRegion(out,0,ranges.size(),ranges.data());return out;
}
extern "C" JNIEXPORT void JNICALL Java_com_madar_files_SpeechActivity_release(JNIEnv*,jclass,jlong handle){if(handle)SherpaOnnxDestroyVoiceActivityDetector(reinterpret_cast<const SherpaOnnxVoiceActivityDetector*>(handle));}
#include <cstdio>
#include "lame.h"
extern "C" JNIEXPORT void JNICALL Java_com_madar_files_AudioEditor_mp3(JNIEnv* e,jclass,jstring input,jstring output,jobject cancel){
 const char* a=e->GetStringUTFChars(input,nullptr);const char* b=e->GetStringUTFChars(output,nullptr);FILE* in=std::fopen(a,"rb");FILE* out=std::fopen(b,"wb");e->ReleaseStringUTFChars(input,a);e->ReleaseStringUTFChars(output,b);
 if(!in||!out){if(in)fclose(in);if(out)fclose(out);fail(e,"تعذر فتح ملفات MP3");return;}auto encoder=lame_init();if(!encoder){fclose(in);fclose(out);fail(e,"تعذر تحميل مرمز MP3");return;}lame_set_in_samplerate(encoder,44100);lame_set_num_channels(encoder,2);lame_set_brate(encoder,192);lame_set_quality(encoder,2);lame_set_write_id3tag_automatic(encoder,0);bool ok=lame_init_params(encoder)>=0;std::vector<short> pcm(1152*2);std::vector<unsigned char> bytes(16384);auto method=e->GetMethodID(e->GetObjectClass(cancel),"get","()Z");
 while(ok){if(e->CallBooleanMethod(cancel,method)){ok=false;break;}size_t frames=fread(pcm.data(),sizeof(short)*2,1152,in);if(!frames)break;int n=lame_encode_buffer_interleaved(encoder,pcm.data(),frames,bytes.data(),bytes.size());if(n<0||fwrite(bytes.data(),1,n,out)!=(size_t)n){ok=false;break;}}
 if(ok){int n=lame_encode_flush(encoder,bytes.data(),bytes.size());ok=n>=0&&fwrite(bytes.data(),1,n,out)==(size_t)n;}lame_close(encoder);fclose(in);if(fclose(out)!=0)ok=false;if(!ok)fail(e,"لم يكتمل ترميز MP3");
}
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
}
extern "C" JNIEXPORT void JNICALL Java_com_madar_files_ContainerTools_remux(JNIEnv* e,jclass,jstring input,jstring output,jstring format,jobject cancel){
 const char* a=e->GetStringUTFChars(input,nullptr);const char* b=e->GetStringUTFChars(output,nullptr);const char* f=e->GetStringUTFChars(format,nullptr);AVFormatContext* in=nullptr;AVFormatContext* out=nullptr;int error=avformat_open_input(&in,a,nullptr,nullptr);bool opened=false;
 if(error>=0){avformat_find_stream_info(in,nullptr);error=avformat_alloc_output_context2(&out,nullptr,f,b);}
 std::vector<int> map;if(error>=0){map.resize(in->nb_streams,-1);for(unsigned i=0;i<in->nb_streams;i++){auto type=in->streams[i]->codecpar->codec_type;if(type!=AVMEDIA_TYPE_VIDEO&&type!=AVMEDIA_TYPE_AUDIO)continue;auto* stream=avformat_new_stream(out,nullptr);if(!stream){error=AVERROR(ENOMEM);break;}map[i]=stream->index;error=avcodec_parameters_copy(stream->codecpar,in->streams[i]->codecpar);if(error<0)break;stream->codecpar->codec_tag=0;stream->time_base=in->streams[i]->time_base;av_dict_copy(&stream->metadata,in->streams[i]->metadata,0);}}
 if(error>=0){error=avio_open(&out->pb,b,AVIO_FLAG_WRITE);opened=error>=0;}if(error>=0)error=avformat_write_header(out,nullptr);auto method=e->GetMethodID(e->GetObjectClass(cancel),"get","()Z");AVPacket* packet=av_packet_alloc();if(!packet)error=AVERROR(ENOMEM);
 while(error>=0){if(e->CallBooleanMethod(cancel,method)){error=AVERROR_EXIT;break;}int n=av_read_frame(in,packet);if(n==AVERROR_EOF)break;if(n<0){error=n;break;}int from=packet->stream_index,to=map[from];if(to<0){av_packet_unref(packet);continue;}av_packet_rescale_ts(packet,in->streams[from]->time_base,out->streams[to]->time_base);packet->stream_index=to;packet->pos=-1;error=av_interleaved_write_frame(out,packet);av_packet_unref(packet);}
 if(error>=0)error=av_write_trailer(out);if(packet)av_packet_free(&packet);if(in)avformat_close_input(&in);if(out){if(opened)avio_closep(&out->pb);avformat_free_context(out);}e->ReleaseStringUTFChars(input,a);e->ReleaseStringUTFChars(output,b);e->ReleaseStringUTFChars(format,f);if(error<0){char text[256];av_strerror(error,text,sizeof(text));fail(e,text);}
}
