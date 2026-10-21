package com.madar.files;
import android.content.Context;
/** A speech classifier, rather than audio energy, keeps long music/silence gaps out of ASR. */
final class SpeechActivity implements AutoCloseable {
 static{System.loadLibrary("madar_audio");}
 private long handle;
 private static native long open(String model);
 private static native int[] regions(long handle,float[] samples);
 private static native void release(long handle);
 SpeechActivity(Context c)throws Exception{handle=open(OfflineAssets.file(c,"silero_vad.onnx").getPath());}
 int[] regions(float[] samples){if(handle==0)throw new IllegalStateException("كاشف الكلام مغلق");return regions(handle,samples);}
 @Override public void close(){if(handle!=0){release(handle);handle=0;}}
}
