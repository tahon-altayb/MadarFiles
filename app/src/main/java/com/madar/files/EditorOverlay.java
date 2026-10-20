package com.madar.files;

import android.content.Context;
import android.graphics.*;
import android.text.*;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BitmapOverlay;
import org.json.*;
import java.io.*;
import java.util.*;

/** Layers use normalized preview coordinates and the final composition clock. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class EditorOverlay extends BitmapOverlay {
 private static final class Layer {double start,end,x,y,w,h;String text;float font=.06f;int color=0xfffff4df;Bitmap image;}
 private final List<Layer> layers=new ArrayList<>();private Bitmap frame;private int width=640,height=360;private float scaleX=1,scaleY=1;private String last="";
 EditorOverlay(Context c,JSONArray data,double duration)throws Exception {
  if(data.length()>20)throw new IOException("الحد 20 عنصرًا");
  try{for(int i=0;i<data.length();i++){JSONObject o=data.getJSONObject(i);Layer l=new Layer();l.start=o.getDouble("start");l.end=o.getDouble("end");l.x=o.getDouble("x");l.y=o.getDouble("y");l.w=o.getDouble("w");l.h=o.getDouble("h");for(double n:new double[]{l.start,l.end,l.x,l.y,l.w,l.h})if(!Double.isFinite(n))throw new IOException("عنصر غير صالح");if(l.start<0||l.end<=l.start||l.end>duration+.01||l.x<0||l.y<0||l.w<=0||l.h<=0||l.x+l.w>1.001||l.y+l.h>1.001)throw new IOException("تحقق من توقيت العنصر وموضعه");
   if(o.getString("type").equals("image")){String name=o.getString("name");if(!name.matches("madar_layer_[0-9]+\\.png"))throw new IOException("صورة عنصر غير صالحة");File f=new File(c.getCacheDir(),name);l.image=FilesUtil.image(f,1600);}else if(o.getString("type").equals("text")){l.text=o.getString("text");l.font=(float)FilesUtil.bounded(o.optDouble("font",.06),.02,.12);String ink=o.optString("color","#fff4df");if(!ink.matches("#[0-9a-fA-F]{6}"))throw new IOException("لون نص غير صالح");l.color=Color.parseColor(ink);if(l.text.length()>500)throw new IOException("النص طويل جدًا");}else throw new IOException("نوع عنصر غير صالح");layers.add(l);
  }}catch(Exception e){for(Layer l:layers)if(l.image!=null)l.image.recycle();throw e;}
 }
 @Override public void configure(Size size){float scale=Math.min(1,1080f/size.getWidth());width=Math.max(1,Math.round(size.getWidth()*scale));height=Math.max(1,Math.round(size.getHeight()*scale));scaleX=size.getWidth()/(float)width;scaleY=size.getHeight()/(float)height;last="!";}
 @Override public androidx.media3.effect.OverlaySettings getOverlaySettings(long time){return new androidx.media3.effect.OverlaySettings.Builder().setScale(scaleX,scaleY).build();}
 @Override public Bitmap getBitmap(long us){double t=us/1000000.0;StringBuilder key=new StringBuilder();for(int i=0;i<layers.size();i++)if(t>=layers.get(i).start&&t<layers.get(i).end)key.append(i).append(',');if(frame!=null&&last.equals(key.toString()))return frame;if(frame!=null)frame.recycle();frame=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);last=key.toString();Canvas canvas=new Canvas(frame);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
  for(Layer l:layers){if(t<l.start||t>=l.end)continue;RectF box=new RectF((float)l.x*width,(float)l.y*height,(float)(l.x+l.w)*width,(float)(l.y+l.h)*height);if(l.image!=null){float scale=Math.min(box.width()/l.image.getWidth(),box.height()/l.image.getHeight());float w=l.image.getWidth()*scale,h=l.image.getHeight()*scale;canvas.drawBitmap(l.image,null,new RectF(box.centerX()-w/2,box.centerY()-h/2,box.centerX()+w/2,box.centerY()+h/2),paint);}else{paint.setColor(0x99171717);canvas.drawRect(box,paint);TextPaint ink=new TextPaint(Paint.ANTI_ALIAS_FLAG);ink.setColor(l.color);ink.setTextSize(Math.max(1,height*l.font));ink.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));StaticLayout text=StaticLayout.Builder.obtain(l.text,0,l.text.length(),ink,Math.max(1,(int)box.width()-8)).setAlignment(Layout.Alignment.ALIGN_CENTER).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).setIncludePad(false).build();canvas.save();canvas.clipRect(box);canvas.translate(box.left+4,box.top+Math.max(0,(box.height()-text.getHeight())/2));text.draw(canvas);canvas.restore();}}
  return frame;
 }
 @Override public void release()throws androidx.media3.common.VideoFrameProcessingException{super.release();if(frame!=null){frame.recycle();frame=null;}for(Layer l:layers)if(l.image!=null)l.image.recycle();}
}
