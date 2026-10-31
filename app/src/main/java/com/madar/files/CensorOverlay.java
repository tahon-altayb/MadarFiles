package com.madar.files;

import android.graphics.*;
import androidx.media3.common.util.Size;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.effect.*;

/** Mask is composited into the video frame, before FIT/FILL/ZOOM and system PiP. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class CensorOverlay extends BitmapOverlay {
 volatile CensorTimeline timeline;volatile boolean enabled;volatile long lastTimestampUs=-1;
 private Bitmap bitmap;private Canvas canvas;private final Paint paint=new Paint();private int width=1,height=1;
 @Override public void configure(Size videoSize)throws VideoFrameProcessingException{
  super.configure(videoSize);width=videoSize.getWidth();height=videoSize.getHeight();
  float scale=Math.min(1,768f/Math.max(width,height));int w=Math.max(1,Math.round(width*scale)),h=Math.max(1,Math.round(height*scale));
  if(bitmap!=null)bitmap.recycle();bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);canvas=new Canvas(bitmap);paint.setColor(Color.BLACK);
 }
 @Override public Bitmap getBitmap(long presentationTimeUs){
  lastTimestampUs=presentationTimeUs;
  if(bitmap==null){bitmap=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);canvas=new Canvas(bitmap);}
  canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
  if(!enabled)return bitmap;
  CensorTimeline source=timeline;double[][] boxes=source==null?null:source.boxesAt(presentationTimeUs);
  if(boxes==null){canvas.drawColor(Color.BLACK);return bitmap;}
  for(double[] b:boxes)canvas.drawRect((float)Math.floor(b[0]*bitmap.getWidth()),(float)Math.floor(b[1]*bitmap.getHeight()),(float)Math.ceil(b[2]*bitmap.getWidth()),(float)Math.ceil(b[3]*bitmap.getHeight()),paint);
  return bitmap;
 }
 @Override public OverlaySettings getOverlaySettings(long timeUs){return new OverlaySettings.Builder().setScale(width/(float)Math.max(1,bitmap==null?1:bitmap.getWidth()),height/(float)Math.max(1,bitmap==null?1:bitmap.getHeight())).build();}
 @Override public void release()throws VideoFrameProcessingException{super.release();if(bitmap!=null)bitmap.recycle();bitmap=null;canvas=null;}
}
