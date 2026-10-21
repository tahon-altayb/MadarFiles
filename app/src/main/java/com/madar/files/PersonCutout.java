package com.madar.files;
import android.graphics.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.*;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;
import com.google.android.gms.tasks.Tasks;
import java.util.concurrent.TimeUnit;
final class PersonCutout {
 static Bitmap cut(android.content.Context context,Bitmap source,boolean transparent,int background,String model,double threshold,double softness)throws Exception{
  if(model.equals("fast"))return cut(source,transparent,background);
  int width=source.getWidth(),height=source.getHeight();float scale=Math.min(512f/Math.min(width,height),1024f/Math.max(width,height));int mw=Math.max(32,Math.round(width*scale/32)*32),mh=Math.max(32,Math.round(height*scale/32)*32);Bitmap small=Bitmap.createScaledBitmap(source,mw,mh,true);int[] pixels=new int[mw*mh];small.getPixels(pixels,0,mw,0,0,mw,mh);if(small!=source)small.recycle();float[] input=new float[3*pixels.length];for(int i=0;i<pixels.length;i++){input[i]=Color.red(pixels[i])/127.5f-1;input[pixels.length+i]=Color.green(pixels[i])/127.5f-1;input[2*pixels.length+i]=Color.blue(pixels[i])/127.5f-1;}
  float[] alpha;try(VisionSession session=new VisionSession(OfflineAssets.file(context,"portrait-matte.onnx"))){alpha=session.infer(input,1,3,mh,mw);long[] shape=session.dimensions();if(shape.length!=4||shape[2]!=mh||shape[3]!=mw||alpha.length!=mw*mh)throw new java.io.IOException("قناع عزل غير صالح");}
  Bitmap output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);int[] row=new int[width];float shift=(float)(FilesUtil.bounded(threshold,0,100)-50)/100f,contrast=(float)(50/FilesUtil.bounded(softness,10,100));for(int y=0;y<height;y++){source.getPixels(row,0,width,0,y,width,1);float sy=Math.max(0,Math.min(mh-1,(y+.5f)*mh/height-.5f));int y0=(int)sy,y1=Math.min(mh-1,y0+1);float fy=sy-y0;for(int x=0;x<width;x++){float sx=Math.max(0,Math.min(mw-1,(x+.5f)*mw/width-.5f));int x0=(int)sx,x1=Math.min(mw-1,x0+1);float fx=sx-x0;float a=alpha[y0*mw+x0]*(1-fx)*(1-fy)+alpha[y0*mw+x1]*fx*(1-fy)+alpha[y1*mw+x0]*(1-fx)*fy+alpha[y1*mw+x1]*fx*fy;a=Math.max(0,Math.min(1,(a-.5f-shift)*contrast+.5f))*Color.alpha(row[x])/255f;int color=row[x];row[x]=transparent?Color.argb(Math.round(a*255),Color.red(color),Color.green(color),Color.blue(color)):Color.rgb(Math.round(Color.red(color)*a+Color.red(background)*(1-a)),Math.round(Color.green(color)*a+Color.green(background)*(1-a)),Math.round(Color.blue(color)*a+Color.blue(background)*(1-a)));}output.setPixels(row,0,width,0,y,width,1);}return output;
 }
 static Bitmap cut(Bitmap source,boolean transparent,int background)throws Exception{
  Segmenter engine=Segmentation.getClient(new SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).enableRawSizeMask().build());
  try{SegmentationMask mask=Tasks.await(engine.process(InputImage.fromBitmap(source,0)),60,TimeUnit.SECONDS);int mw=mask.getWidth(),mh=mask.getHeight(),w=source.getWidth(),h=source.getHeight();float[] confidence=new float[mw*mh];mask.getBuffer().rewind();mask.getBuffer().asFloatBuffer().get(confidence);Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);int[] row=new int[w];
   for(int y=0;y<h;y++){source.getPixels(row,0,w,0,y,w,1);float sy=(y+.5f)*mh/h-.5f;int y0=Math.max(0,Math.min(mh-1,(int)Math.floor(sy))),y1=Math.min(mh-1,y0+1);float fy=Math.max(0,Math.min(1,sy-y0));for(int x=0;x<w;x++){float sx=(x+.5f)*mw/w-.5f;int x0=Math.max(0,Math.min(mw-1,(int)Math.floor(sx))),x1=Math.min(mw-1,x0+1);float fx=Math.max(0,Math.min(1,sx-x0));float a=confidence[y0*mw+x0]*(1-fx)*(1-fy)+confidence[y0*mw+x1]*fx*(1-fy)+confidence[y1*mw+x0]*(1-fx)*fy+confidence[y1*mw+x1]*fx*fy;a=Math.max(0,Math.min(1,(a-.15f)/.7f))*Color.alpha(row[x])/255f;int color=row[x];row[x]=transparent?Color.argb(Math.round(a*255),Color.red(color),Color.green(color),Color.blue(color)):Color.rgb(Math.round(Color.red(color)*a+Color.red(background)*(1-a)),Math.round(Color.green(color)*a+Color.green(background)*(1-a)),Math.round(Color.blue(color)*a+Color.blue(background)*(1-a)));}out.setPixels(row,0,w,0,y,w,1);}return out;
  }finally{engine.close();}
 }
}
