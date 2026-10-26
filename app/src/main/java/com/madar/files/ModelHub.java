package com.madar.files;
import android.content.Context;
import org.json.*;
import java.io.*;
/** Reports only engines actually implemented in this application. */
final class ModelHub {
 static final String[] IDS={"small","maximum","agent"};
 static JSONArray list(Context c)throws Exception{
  JSONArray rows=new JSONArray();for(String id:IDS){File[] files=files(c,id);long bytes=0;for(File f:files)if(f.isFile())bytes+=f.length();long expected=id.equals("agent")?LocalAgent.MODEL_BYTES:id.equals("small")?375485327L:1036613791L;rows.put(new JSONObject().put("id",id).put("name",id.equals("agent")?"Qwen3 · المساعد المحلي":id.equals("small")?"Whisper Small · التفريغ":"Whisper Turbo · التفريغ").put("bytes",bytes).put("expectedBytes",expected).put("downloaded",bytes==expected).put("optional",true));}
  String[] assets=c.getAssets().list("models");if(assets!=null)for(String asset:assets){if(!asset.endsWith("onnx"))continue;long bytes;try(android.content.res.AssetFileDescriptor fd=c.getAssets().openFd("models/"+asset)){bytes=fd.getLength();}catch(IOException compressed){bytes=-1;}rows.put(new JSONObject().put("id",asset).put("name",asset).put("bytes",bytes).put("downloaded",true).put("optional",false));}return rows;
 }
 static File[] files(Context c,String id)throws IOException{if(id.equals("agent"))return new File[]{LocalAgent.model(c)};if(!id.equals("small")&&!id.equals("maximum"))throw new IOException("نموذج غير مدعوم");String prefix=id.equals("small")?"small":"turbo";File dir=new File(c.getFilesDir(),"speech-"+prefix);return new File[]{new File(dir,prefix+"-encoder.int8.onnx"),new File(dir,prefix+"-decoder.int8.onnx"),new File(dir,prefix+"-tokens.txt")};}
 static void remove(Context c,String id)throws Exception{for(File f:files(c,id))if(f.exists()&&!f.delete())throw new IOException("تعذر حذف النموذج");}
}
