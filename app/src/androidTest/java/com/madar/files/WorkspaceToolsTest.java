package com.madar.files;
import android.content.Context;
import android.graphics.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class WorkspaceToolsTest {
 private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
 @Test public void generatedQrRoundTripsArabicAndMediaShareLink()throws Exception{
  Context c=context();for(String[] sample:new String[][]{{"text","مرحبا من مدار ١٢٣"},{"image","https://example.org/photo/123"},{"video","https://example.org/video/123"},{"pdf","https://example.org/book.pdf"}}){File file=QrTools.create(c,new JSONObject().put("qrType",sample[0]).put("qrValue",sample[1]).put("qrSize",512));Bitmap image=BitmapFactory.decodeFile(file.getPath());try{int[] pixels=new int[image.getWidth()*image.getHeight()];image.getPixels(pixels,0,image.getWidth(),0,0,image.getWidth(),image.getHeight());Result result=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(image.getWidth(),image.getHeight(),pixels))));assertEquals(sample[1],result.getText());}finally{image.recycle();file.delete();}}
  for(String url:new String[]{"file:///sdcard/a.mp4","content://a/b","javascript:alert(1)","https://user:pass@example.org/","http://127.0.0.1/video"}){try{QrTools.payload(new JSONObject().put("qrType","video").put("qrValue",url));fail(url);}catch(IOException expected){}}
 }
 @Test public void silenceKeepsQuietBoundaryPaddingAndRejectsAllSilentOrInvalidThreshold()throws Exception{
  JSONArray peaks=new JSONArray();for(int i=0;i<100;i++)peaks.put(i>=20&&i<50?0:.2);JSONObject wave=new JSONObject().put("hasAudio",true).put("duration",10).put("peaks",peaks);JSONArray kept=SilenceDetector.keep(wave,-38,.6,.12);assertEquals(2,kept.length());assertEquals(2.12,kept.getJSONArray(0).getDouble(1),.001);assertEquals(4.88,kept.getJSONArray(1).getDouble(0),.001);assertEquals(10,kept.getJSONArray(1).getDouble(1),.001);
  assertEquals(.2,SilenceDetector.peak(wave,kept),.001);try{SilenceDetector.keep(wave,0,.6,.12);fail();}catch(IOException expected){}
 }
 @Test public void attachedCopiesSurviveSourceDeletionAndCannotAddressOtherFiles()throws Exception{
  Context c=context();File source=File.createTempFile("attachment-test-",".media",c.getCacheDir());try{try(OutputStream out=new FileOutputStream(source)){out.write(new byte[]{1,2,3,4});}JSONObject data=AssistantFiles.store(c,source,new JSONObject().put("kind","image").put("type","source").put("name","picture.png"));source.delete();String id=data.getString("attachmentId");assertEquals(4,AssistantFiles.resolve(c,id).length());assertEquals("picture.png",AssistantFiles.info(c,id).getString("name"));AssistantFiles.delete(c,id);assertFalse(new File(AssistantFiles.directory(c),id+".media").exists());try{AssistantFiles.resolve(c,"../../exports/secret");fail();}catch(IOException expected){}}finally{source.delete();}
 }
 @Test public void musicSamplesZeroPadBeforeAndAfterTrackAndResampleWithoutRepeatingTail()throws Exception{
  File f=File.createTempFile("music-sample-",".pcm",context().getCacheDir());try{ByteBuffer data=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<8;i++)data.putShort((short)16384);try(OutputStream out=new FileOutputStream(f)){out.write(data.array());}try(RandomAccessFile input=new RandomAccessFile(f,"r")){assertArrayEquals(new float[]{0,0,0,0,.5f,.5f,.5f,.5f,.5f,.5f,.5f,.5f,0,0},BackgroundAudio.samples(input,44100,-2,7,4),.001f);assertArrayEquals(new float[8],BackgroundAudio.samples(input,44100,8,4,4),0);}}finally{f.delete();}
 }
 @Test public void documentParagraphsKeepBoundariesAndContextNeverBecomesSubtitleInstructions()throws Exception{
  JSONArray paragraphs=DocumentTranslation.paragraphs("First line\nsecond line\n\nNext paragraph.","en");assertEquals(2,paragraphs.length());assertEquals("First line\nsecond line\n\nNext paragraph.",DocumentTranslation.join(paragraphs));String prompt=DocumentTranslation.request(new JSONArray(),"ar").getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text");assertTrue(prompt.contains("not subtitles"));assertTrue(prompt.contains("Do not complete familiar quotations"));
 }
 @Test public void montageSchemaAcceptsRealOperationsButRejectsPathsConsentAndInvalidTimes()throws Exception{
  JSONObject plan=new JSONObject("{tool:editor,fields:[],message:'',actions:[{action:addImage,start:2,end:5},{action:enhance},{action:export}]}");assertEquals(3,AgentTools.validate(plan,"video").getJSONArray("actions").length());for(String bad:new String[]{"{action:addImage,start:2,end:5,path:'/sdcard/secret'}","{action:addText,start:5,end:2,text:'hi'}","{action:addMusic,start:0,end:5,onlineAudioConsent:true}","{action:shell,text:'rm -rf /'}"}){try{AgentTools.validate(new JSONObject().put("tool","editor").put("fields",new JSONArray()).put("message","").put("actions",new JSONArray().put(new JSONObject(bad))),"video");fail(bad);}catch(IOException expected){}}
 }
}
