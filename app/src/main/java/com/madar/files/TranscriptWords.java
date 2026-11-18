package com.madar.files;
import org.json.*;
import java.io.IOException;
/** Reject invented or invalid word clocks at the model boundary. */
final class TranscriptWords {
 static JSONArray words(JSONArray input,double start,double end,double offset)throws Exception{
  JSONArray out=new JSONArray();if(input==null)return out;if(input.length()>500)throw new IOException("كلمات كثيرة في سطر واحد");double previous=start;
  for(int i=0;i<input.length();i++){JSONObject w=input.getJSONObject(i);double a=w.getDouble("start"),b=w.getDouble("end");String text=w.getString("text");if(!Double.isFinite(a)||!Double.isFinite(b)||a<start-.001||a<previous-.001||b<=a||b>end+.001||text.trim().isEmpty()||text.length()>500)throw new IOException("توقيت الكلمات غير صالح");previous=b;out.put(new JSONObject().put("start",offset+a).put("end",offset+b).put("text",text));}return out;
 }
 static JSONArray validateCues(JSONArray input)throws Exception{
  JSONArray out=new JSONArray();if(input==null)return out;if(input.length()>2000||input.toString().length()>700000)throw new IOException("التفريغ كبير جدًا لحفظ المشروع");
  for(int i=0;i<input.length();i++){JSONObject c=input.getJSONObject(i);double a=c.getDouble("start"),b=c.getDouble("end");String text=c.getString("text");if(!Double.isFinite(a)||!Double.isFinite(b)||a<0||b<=a||b>86400||text.length()>2000)throw new IOException("توقيت التفريغ غير صالح");out.put(new JSONObject().put("start",a).put("end",b).put("text",text).put("words",words(c.optJSONArray("words"),a,b,0)));}return out;
 }
}
