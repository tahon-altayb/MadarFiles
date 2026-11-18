package com.madar.files;
import android.content.Context;
import org.json.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
final class TranslationEngine {
 static void preflight(Context c,String mode)throws Exception {
  if(!mode.equals("local")&&!mode.equals("online"))throw new IOException("وضع ترجمة غير صالح");
  if(mode.equals("online")&&TranslationSettings.credential(c).isEmpty()&&!GeminiClient.configured(c))throw new IOException("افتح إعداد الترجمة بالإنترنت وأضف مفتاح Gemini أولًا");
 }
 static JSONArray translate(Context c,JSONArray cues,String from,String to,String mode,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception {
  return translate(c,cues,from,to,mode,"",cancel,progress);
 }
 static JSONArray translate(Context c,JSONArray cues,String from,String to,String mode,String names,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception {
  return translate(c,cues,from,to,mode,names,"",cancel,progress);
 }
 static JSONArray translate(Context c,JSONArray cues,String from,String to,String mode,String names,String workContext,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception {
  preflight(c,mode);
  return mode.equals("online")?OnlineTranslation.translate(c,cues,from,to,names,workContext,cancel,progress):LocalTranslation.translate(cues,from,to,names,cancel,progress);
 }
}
