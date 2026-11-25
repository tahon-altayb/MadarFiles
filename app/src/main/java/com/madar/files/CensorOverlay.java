package com.madar.files;

import android.graphics.*;
import androidx.media3.common.util.Size;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.effect.*;

/** Mask is composited into the video frame, before FIT/FILL/ZOOM and system PiP. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class CensorOverlay extends BitmapOverlay {
 volatile CensorTimeline timeline;volatile boolean enabled;volatile long lastTimestampUs=-1;
 private Bitmap bitmap;private Canvas canvas;private final Paint paint=new Paint(),outline=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path path=new Path();private int width=1,height=1;
 @Override public void configure(Size videoSize){
  super.configure(videoSize);width=videoSize.getWidth();height=videoSize.getHeight();
  float scale=Math.min(1,768f/Math.max(width,height));int w=Math.max(1,Math.round(width*scale)),h=Math.max(1,Math.round(height*scale));
  if(bitmap!=null)bitmap.recycle();bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);canvas=new Canvas(bitmap);paint.setColor(Color.BLACK);
  // The outline is widened (stroke) so hair, hands and motion between analysed frames stay covered.
  outline.setColor(Color.BLACK);outline.setStyle(Paint.Style.FILL_AND_STROKE);outline.setStrokeJoin(Paint.Join.ROUND);outline.setStrokeWidth(Math.max(4f,.035f*Math.min(w,h)));
 }
 @Override public Bitmap getBitmap(long presentationTimeUs){
  lastTimestampUs=presentationTimeUs;
  if(bitmap==null){bitmap=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);canvas=new Canvas(bitmap);}
  canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
  if(!enabled)return bitmap;
  CensorTimeline source=timeline;CensorTimeline.Shape[] shapes=source==null?null:source.shapesAt(presentationTimeUs);
  if(shapes==null){canvas.drawColor(Color.BLACK);return bitmap;}
  int bw=bitmap.getWidth(),bh=bitmap.getHeight();
  for(CensorTimeline.Shape s:shapes){
   double[] b=s.box;
   if(s.outlines==null){canvas.drawRect((float)Math.floor(b[0]*bw),(float)Math.floor(b[1]*bh),(float)Math.ceil(b[2]*bw),(float)Math.ceil(b[3]*bh),paint);continue;}
   for(float[] o:s.outlines){
    path.rewind();path.moveTo(o[0]*bw,o[1]*bh);
    for(int k=2;k<o.length;k+=2)path.lineTo(o[k]*bw,o[k+1]*bh);
    path.close();canvas.drawPath(path,outline);
   }
  }
  return bitmap;
 }
 @Override public OverlaySettings getOverlaySettings(long timeUs){return new OverlaySettings.Builder().setScale(width/(float)Math.max(1,bitmap==null?1:bitmap.getWidth()),height/(float)Math.max(1,bitmap==null?1:bitmap.getHeight())).build();}
 @Override public void release()throws VideoFrameProcessingException{super.release();if(bitmap!=null)bitmap.recycle();bitmap=null;canvas=null;}
}
