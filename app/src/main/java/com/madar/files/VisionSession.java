package com.madar.files;
import java.io.File;
final class VisionSession implements AutoCloseable {
 static{System.loadLibrary("madar_vision");}
 private long handle;
 VisionSession(File model)throws java.io.IOException{handle=open(model.getPath());}
 float[] infer(float[] data,long... dimensions)throws java.io.IOException{if(handle==0)throw new java.io.IOException("نموذج مغلق");return run(handle,data,dimensions);}
 long[] dimensions(){return shape(handle);}
 @Override public void close(){if(handle!=0){close(handle);handle=0;}}
 private static native long open(String path)throws java.io.IOException;
 private static native float[] run(long handle,float[] data,long[] dimensions)throws java.io.IOException;
 private static native long[] shape(long handle);
 private static native void close(long handle);
}
