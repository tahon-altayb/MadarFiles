package com.madar.files;
import android.content.Context;
import android.graphics.*;
import java.io.*;
final class ImageSizeTarget {
 static File export(Context c,File source,int targetKb)throws Exception {
  if(targetKb<20||targetKb>20000)throw new IOException("اختر حجمًا من 20 إلى 20000 كيلوبايت");
  long target=targetKb*1024L;Bitmap bitmap=FilesUtil.image(source,4096);File destination=FilesUtil.output(c,"jpg"),output=new File(destination.getPath()+".partial");
  try {
   for(int attempt=0;attempt<12;attempt++){
    int low=20,high=95,best=-1;
    while(low<=high){int quality=(low+high)/2;ImageProcessor.writeImage(bitmap,output,"jpg",quality);if(output.length()<=target){best=quality;low=quality+1;}else high=quality-1;}
    if(best>=0){ImageProcessor.writeImage(bitmap,output,"jpg",best);if(output.length()==0||!output.renameTo(destination))throw new IOException("تعذر حفظ الصورة");return destination;}
    if(Math.max(bitmap.getWidth(),bitmap.getHeight())<=64)break;
    Bitmap next=Bitmap.createScaledBitmap(bitmap,Math.max(1,(int)(bitmap.getWidth()*.8)),Math.max(1,(int)(bitmap.getHeight()*.8)),true);if(next!=bitmap)bitmap.recycle();bitmap=next;
   }
   throw new IOException("تعذر الوصول للحجم المطلوب؛ جرّب حجمًا أكبر");
  }catch(Exception|OutOfMemoryError e){output.delete();throw e;}finally{bitmap.recycle();}
 }
}
