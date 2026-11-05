package com.madar.files;
import android.content.Context;
import android.util.Base64;
import com.google.firebase.ai.FirebaseAI;
import com.google.firebase.ai.java.GenerativeModelFutures;
import com.google.firebase.ai.type.*;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** Google speech uses the existing private transport choice, never browser cookies. */
@androidx.annotation.OptIn(markerClass=com.google.firebase.ai.type.PublicPreviewAPI.class)
final class MotionSpeech {
 static final String MODEL="gemini-3.1-flash-tts-preview";
 static File synthesize(Context c,JSONObject p,JSONObject scene,AtomicBoolean cancel)throws Exception{
  String text=scene.getString("narration"),voice=p.getString("voice"),style=p.optString("style","documentary");
  String fingerprint=MotionProject.hash(MODEL+"\n"+voice+"\n"+style+"\n"+text);File output=MotionProject.asset(c,p.getString("id"),"speech_"+fingerprint+".wav");if(output.isFile()&&output.length()>44)return output;
  String direction=style.equals("warm")?"Warm, approachable, clear Arabic narration at a natural pace.":"Calm, confident documentary narration in clear Modern Standard Arabic, natural pauses.";
  String prompt="Read ONLY the following transcript in Arabic. "+direction+" No music, no sound effects, no added words. Preserve proper names.\n\n"+text;
  String key=TranslationSettings.credential(c);ByteArrayOutputStream bytes=new ByteArrayOutputStream();String mime="";
  if(!key.isEmpty()){
   JSONObject config=new JSONObject().put("responseModalities",new JSONArray().put("AUDIO")).put("speechConfig",new JSONObject().put("voiceConfig",new JSONObject().put("prebuiltVoiceConfig",new JSONObject().put("voiceName",voice))));
   JSONObject response=OnlineTranslation.post(MODEL,key,new JSONObject().put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text",prompt))))).put("generationConfig",config),cancel,16*1024*1024);
   JSONObject candidate=response.getJSONArray("candidates").getJSONObject(0);if(!candidate.optString("finishReason").equals("STOP"))throw new IOException("لم يكتمل تعليق قوقل؛ لم تُحفظ نتيجة ناقصة");JSONArray parts=candidate.getJSONObject("content").getJSONArray("parts");for(int i=0;i<parts.length();i++){JSONObject data=parts.getJSONObject(i).optJSONObject("inlineData");if(data!=null&&data.optString("mimeType").startsWith("audio/")){String type=data.getString("mimeType");if(!mime.isEmpty()&&!mime.equals(type))throw new IOException("صيغة صوت غير متسقة");mime=type;bytes.write(Base64.decode(data.getString("data"),Base64.DEFAULT));}}
  }else{
   GenerationConfig.Builder config=new GenerationConfig.Builder();config.responseModalities=Collections.singletonList(ResponseModality.AUDIO);config.speechConfig=new SpeechConfig(new Voice(voice),"ar-EG");
   GenerativeModelFutures model=GenerativeModelFutures.from(FirebaseAI.getInstance(GeminiClient.managed(c),GenerativeBackend.googleAI()).generativeModel(MODEL,config.build(),null,null,null,null,new RequestOptions(180000L)));
   ListenableFuture<GenerateContentResponse> future=model.generateContent(new Content.Builder().addText(prompt).build());long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(185);
   try{while(true){ColabProbe.check(cancel);if(System.nanoTime()>deadline)throw new IOException("انتهت مهلة التعليق؛ لم يُعَد الطلب تلقائيًا");try{GenerateContentResponse response=future.get(150,TimeUnit.MILLISECONDS);if(response.getCandidates().isEmpty()||!FinishReason.STOP.equals(response.getCandidates().get(0).getFinishReason()))throw new IOException("لم يكتمل تعليق قوقل");for(Part part:response.getCandidates().get(0).getContent().getParts())if(part instanceof InlineDataPart){InlineDataPart data=(InlineDataPart)part;if(data.getMimeType().startsWith("audio/")){mime=data.getMimeType();bytes.write(data.getInlineData());}}break;}catch(TimeoutException waiting){}}}catch(ExecutionException e){throw GeminiClient.failure(e.getCause());}finally{future.cancel(true);}
  }
  ColabProbe.check(cancel);byte[] data=bytes.toByteArray();if(data.length<1000||data.length>5*1024*1024||!mime.startsWith("audio/"))throw new IOException("تعليق غير صالح أو طويل جدًا؛ قسّم المشهد");File temp=new File(output.getPath()+".partial");try{try(OutputStream out=new FileOutputStream(temp)){boolean wav=data.length>12&&data[0]=='R'&&data[1]=='I'&&data[2]=='F'&&data[3]=='F'&&data[8]=='W'&&data[9]=='A'&&data[10]=='V'&&data[11]=='E';if(!wav){if(!(mime.toLowerCase(Locale.ROOT).contains("pcm")||mime.toLowerCase(Locale.ROOT).contains("l16"))||data.length%2!=0)throw new IOException("صيغة تعليق قوقل غير مدعومة");out.write(wavHeader(data.length));}out.write(data);}ColabProbe.check(cancel);if(!temp.renameTo(output))throw new IOException("تعذر حفظ التعليق");}finally{temp.delete();}return output;
 }
 static byte[] wavHeader(int length){ByteBuffer b=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);b.put(new byte[]{'R','I','F','F'}).putInt(length+36).put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16).putShort((short)1).putShort((short)1).putInt(24000).putInt(48000).putShort((short)2).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(length);return b.array();}
}
