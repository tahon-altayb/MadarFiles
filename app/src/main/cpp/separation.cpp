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
