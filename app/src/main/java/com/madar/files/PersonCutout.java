package com.madar.files;
import android.graphics.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.*;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;
import com.google.android.gms.tasks.Tasks;
import java.util.concurrent.TimeUnit;
final class PersonCutout {
 static Bitmap cut(Bitmap source,boolean transparent,int background)throws Exception{
  Segmenter engine=Segmentation.getClient(new SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).enableRawSizeMask().build());
  try{SegmentationMask mask=Tasks.await(engine.process(InputImage.fromBitmap(source,0)),60,TimeUnit.SECONDS);int mw=mask.getWidth(),mh=mask.getHeight(),w=source.getWidth(),h=source.getHeight();float[] confidence=new float[mw*mh];mask.getBuffer().rewind();mask.getBuffer().asFloatBuffer().get(confidence);Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);int[] row=new int[w];
   for(int y=0;y<h;y++){source.getPixels(row,0,w,0,y,w,1);float sy=(y+.5f)*mh/h-.5f;int y0=Math.max(0,Math.min(mh-1,(int)Math.floor(sy))),y1=Math.min(mh-1,y0+1);float fy=Math.max(0,Math.min(1,sy-y0));for(int x=0;x<w;x++){float sx=(x+.5f)*mw/w-.5f;int x0=Math.max(0,Math.min(mw-1,(int)Math.floor(sx))),x1=Math.min(mw-1,x0+1);float fx=Math.max(0,Math.min(1,sx-x0));float a=confidence[y0*mw+x0]*(1-fx)*(1-fy)+confidence[y0*mw+x1]*fx*(1-fy)+confidence[y1*mw+x0]*(1-fx)*fy+confidence[y1*mw+x1]*fx*fy;a=Math.max(0,Math.min(1,(a-.15f)/.7f))*Color.alpha(row[x])/255f;int color=row[x];row[x]=transparent?Color.argb(Math.round(a*255),Color.red(color),Color.green(color),Color.blue(color)):Color.rgb(Math.round(Color.red(color)*a+Color.red(background)*(1-a)),Math.round(Color.green(color)*a+Color.green(background)*(1-a)),Math.round(Color.blue(color)*a+Color.blue(background)*(1-a)));}out.setPixels(row,0,w,0,y,w,1);}return out;
  }finally{engine.close();}
 }
}
