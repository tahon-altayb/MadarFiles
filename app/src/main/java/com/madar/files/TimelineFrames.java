package com.madar.files;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.util.Base64;
import java.io.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Small bounded contact sheet; never seeks the visible video player. */
final class TimelineFrames {
 static JSONArray read(File input,BooleanSupplier cancelled)throws Exception{
  JSONArray frames=new JSONArray();
  try(MediaMetadataRetriever r=new MediaMetadataRetriever()){
   r.setDataSource(input.getPath());long duration=Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
   int w=Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)),h=Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
   if(duration<=0||w<=0||h<=0)throw new IOException("تعذر قراءة أبعاد الفيديو");
   String rotation=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);if("90".equals(rotation)||"270".equals(rotation)){int swap=w;w=h;h=swap;}
   double scale=Math.min(160.0/w,90.0/h);int width=Math.max(1,(int)(w*scale)),height=Math.max(1,(int)(h*scale));
   int count=(int)Math.min(12,Math.max(1,(duration+999)/1000));
   for(int i=0;i<count;i++){
    if(cancelled.getAsBoolean())return new JSONArray();long at=i*Math.max(0,duration-1)/count;
    Bitmap b=r.getScaledFrameAtTime(at*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,width,height);if(b==null)continue;
    try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.JPEG,70,bytes);frames.put(new JSONObject().put("time",at/1000.0).put("image","data:image/jpeg;base64,"+Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP)));}finally{b.recycle();}
   }
  }return frames;
 }
}
