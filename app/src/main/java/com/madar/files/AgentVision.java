package com.madar.files;
import android.content.Context;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import org.json.*;
import java.io.*;
/** Small real visual samples, explicitly labelled; never pretends to inspect unseen frames. */
final class AgentVision {
 static JSONArray sample(Context c,File file,String kind)throws Exception{
  JSONArray parts=new JSONArray();if(file==null||!file.isFile())return parts;
  if(kind.equals("image")){Bitmap bitmap=FilesUtil.image(file,768);try{parts.put(part(bitmap));}finally{bitmap.recycle();}}
  else if(kind.equals("video")){try(MediaMetadataRetriever media=new MediaMetadataRetriever()){media.setDataSource(file.getPath());String value=media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);long duration=value==null?0:Long.parseLong(value);for(double fraction:new double[]{.05,.5,.9}){Bitmap b=media.getScaledFrameAtTime((long)(duration*1000*fraction),MediaMetadataRetriever.OPTION_CLOSEST_SYNC,480,480);if(b!=null)try{parts.put(new JSONObject().put("text","Visual sample at "+Math.round(duration*fraction)/1000.0+" seconds; not a full video transcript."));parts.put(part(b));}finally{b.recycle();}}}}
  return parts;
 }
 private static JSONObject part(Bitmap b)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.JPEG,78,out);return new JSONObject().put("inlineData",new JSONObject().put("mimeType","image/jpeg").put("data",android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP)));}
}
