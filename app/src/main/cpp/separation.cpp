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
 if(result){if(result->segment_count>0&&result->segment_timestamps&&result->segment_durations){for(int i=0;i<result->segment_count;i++){values.push_back(std::to_string(result->segment_timestamps[i]));values.push_back(std::to_string(result->segment_durations[i]));values.push_back(result->segment_texts_arr[i]?result->segment_texts_arr[i]:"");}}else{values={"0",std::to_string(n/16000.0),result->text?result->text:""};}}
 auto out=e->NewObjectArray(values.size(),e->FindClass("java/lang/String"),nullptr);for(int i=0;i<(int)values.size();i++){jstring s=e->NewStringUTF(values[i].c_str());e->SetObjectArrayElement(out,i,s);e->DeleteLocalRef(s);}if(result)SherpaOnnxDestroyOfflineRecognizerResult(result);SherpaOnnxDestroyOfflineStream(stream);return out;
}
extern "C" JNIEXPORT void JNICALL Java_com_madar_files_SpeechProcessor_close(JNIEnv*,jclass,jlong handle){if(handle)SherpaOnnxDestroyOfflineRecognizer(reinterpret_cast<const SherpaOnnxOfflineRecognizer*>(handle));}
#include <cstdio>
#include "lame.h"
extern "C" JNIEXPORT void JNICALL Java_com_madar_files_AudioEditor_mp3(JNIEnv* e,jclass,jstring input,jstring output,jobject cancel){
 const char* a=e->GetStringUTFChars(input,nullptr);const char* b=e->GetStringUTFChars(output,nullptr);FILE* in=std::fopen(a,"rb");FILE* out=std::fopen(b,"wb");e->ReleaseStringUTFChars(input,a);e->ReleaseStringUTFChars(output,b);
 if(!in||!out){if(in)fclose(in);if(out)fclose(out);fail(e,"تعذر فتح ملفات MP3");return;}auto encoder=lame_init();if(!encoder){fclose(in);fclose(out);fail(e,"تعذر تحميل مرمز MP3");return;}lame_set_in_samplerate(encoder,44100);lame_set_num_channels(encoder,2);lame_set_brate(encoder,192);lame_set_quality(encoder,2);lame_set_write_id3tag_automatic(encoder,0);bool ok=lame_init_params(encoder)>=0;std::vector<short> pcm(1152*2);std::vector<unsigned char> bytes(16384);auto method=e->GetMethodID(e->GetObjectClass(cancel),"get","()Z");
 while(ok){if(e->CallBooleanMethod(cancel,method)){ok=false;break;}size_t frames=fread(pcm.data(),sizeof(short)*2,1152,in);if(!frames)break;int n=lame_encode_buffer_interleaved(encoder,pcm.data(),frames,bytes.data(),bytes.size());if(n<0||fwrite(bytes.data(),1,n,out)!=(size_t)n){ok=false;break;}}
 if(ok){int n=lame_encode_flush(encoder,bytes.data(),bytes.size());ok=n>=0&&fwrite(bytes.data(),1,n,out)==(size_t)n;}lame_close(encoder);fclose(in);if(fclose(out)!=0)ok=false;if(!ok)fail(e,"لم يكتمل ترميز MP3");
}
