package com.madar.files;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** These tests exercise the actual WebView local transport and visible controls. */
@RunWith(AndroidJUnit4.class)
public class InteractivePreviewTest {
 private MainActivity activity;private WebView web;private Context context;
 @Before public void launch()throws Exception{context=InstrumentationRegistry.getInstrumentation().getTargetContext();activity=(MainActivity)InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));java.lang.reflect.Field f=MainActivity.class.getDeclaredField("web");f.setAccessible(true);web=(WebView)f.get(activity);await("typeof compactEditor==='function'",20000);assertTrue("Phone viewport must be at least 320 CSS pixels",Double.parseDouble(js("innerWidth"))>=320);}
 @After public void close(){InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);}
 private String js(String script)throws Exception{CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->web.evaluateJavascript(script,v->{result.set(v);done.countDown();}));assertTrue("WebView callback",done.await(10,TimeUnit.SECONDS));return result.get();}
 private void screenshot(String name)throws Exception{android.os.ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("screencap -p /data/local/tmp/madar-"+name+".png");try(InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] b=new byte[1024];while(in.read(b)!=-1){}}}
 private void await(String expression,long timeout)throws Exception{long end=System.currentTimeMillis()+timeout;String last="";while(System.currentTimeMillis()<end){last=js(expression);if("true".equals(last))return;Thread.sleep(100);}fail("Preview condition: "+expression+" was "+last);}
 private void source(File file,JSONObject info)throws Exception{java.lang.reflect.Field f=MainActivity.class.getDeclaredField("source"),s=MainActivity.class.getDeclaredField("sourceInfo");f.setAccessible(true);s.setAccessible(true);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{f.set(activity,file);s.set(activity,info);}catch(Exception e){throw new RuntimeException(e);}});js("window.receive("+info+")");}
 @Test public void imageChangesVisiblePixelsWhileEditing()throws Exception{File file=File.createTempFile("preview-",".png",context.getCacheDir());Bitmap b=Bitmap.createBitmap(80,60,Bitmap.Config.ARGB_8888);new Canvas(b).drawColor(Color.rgb(180,100,60));try(OutputStream out=new FileOutputStream(file)){b.compress(Bitmap.CompressFormat.PNG,100,out);}try{source(file,new JSONObject().put("type","source").put("kind","image").put("name","image.png").put("mime","image/png").put("size",file.length()).put("width",80).put("height",60).put("thumbnail",FilesUtil.thumbnail(b)));js("openTool('adjust')");await("!!$('liveCanvas')&&$('liveCanvas').width>0&&$('liveCanvas').getContext('2d').getImageData(20,20,1,1).data[0]>100",10000);js("$('brightness').value=-80;$('brightness').dispatchEvent(new Event('input',{bubbles:true}))");await("$('liveCanvas').getContext('2d').getImageData(20,20,1,1).data[0]<70",10000);}finally{b.recycle();file.delete();}}
 @Test public void pdfPagesTurnHorizontallyUsingRealBridge()throws Exception{File file=File.createTempFile("preview-",".pdf",context.getCacheDir());PdfDocument pdf=new PdfDocument();try{for(int i=0;i<2;i++){PdfDocument.Page p=pdf.startPage(new PdfDocument.PageInfo.Builder(200,300,i+1).create());p.getCanvas().drawColor(i==0?Color.RED:Color.BLUE);pdf.finishPage(p);}try(OutputStream out=new FileOutputStream(file)){pdf.writeTo(out);}}finally{pdf.close();}try{source(file,new JSONObject().put("type","source").put("kind","pdf").put("name","book.pdf").put("mime","application/pdf").put("size",file.length()).put("pages",2));js("openTool('preview')");await("!!$('pagePreview')&&$('pagePreview').naturalWidth>0",10000);String old=js("$('pagePreview').src");js("$('pdfNext').click()");await("$('pdfCount').textContent==='2 / 2'&&$('pagePreview').src!=="+old,10000);js("$('pdfPrevious').click()");await("$('pdfCount').textContent==='1 / 2'",10000);js("openTool('extract');$('first').value=1;$('last').value=2;$('last').dispatchEvent(new Event('input',{bubbles:true}));$('pdfNext').click()");await("$('pdfCount').textContent==='2 / 2'",10000);assertEquals("\"1\"",js("$('first').value"));js("openTool('pages');$('pageOrder').value='2,1';$('pageOrder').dispatchEvent(new Event('input',{bubbles:true}))");await("state.pdfPage===2&&$('pdfCount').textContent==='1 / 2'",10000);screenshot("pdf");}finally{file.delete();}}
 @Test public void videoLocalRoutePlaysAndSeeksInEditor()throws Exception{File file=File.createTempFile("preview-",".media",context.getCacheDir());try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("sample.mp4");OutputStream out=new FileOutputStream(file)){FilesUtil.copy(in,out);}try{source(file,new JSONObject().put("type","source").put("kind","video").put("name","video.mp4").put("mime","application/octet-stream").put("size",file.length()).put("width",320).put("height",240).put("duration",3000));js("openTool('editor')");await("!!$('liveVideo')&&$('liveVideo').readyState>=2",20000);assertEquals("true",js("$('liveVideo').currentSrc.startsWith('http://127.0.0.1:')"));js("$('liveVideo').muted=true;$('liveVideo').play()");await("$('liveVideo').currentTime>.2",10000);js("$('liveVideo').pause();$('liveVideo').currentTime=.75");await("Math.abs($('liveVideo').currentTime-.75)<.1",10000);js("addLayer({type:'text',text:'مدار'})");await("state.layers.length===1&&!!$('editLayers').firstElementChild",5000);assertEquals("false",js("document.documentElement.scrollWidth>innerWidth"));screenshot("editor");}finally{file.delete();}}
 @Test public void audioLocalRouteDecodesAndStarts()throws Exception{File file=File.createTempFile("preview-audio-",".media",context.getCacheDir());try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("sample.mp4");OutputStream out=new FileOutputStream(file)){FilesUtil.copy(in,out);}try{source(file,new JSONObject().put("type","source").put("kind","audio").put("name","audio.mp4").put("mime","audio/mp4").put("size",file.length()).put("duration",3000));js("openTool('sound')");await("!!$('liveAudio')&&$('liveAudio').readyState>=2",20000);js("$('liveAudio').muted=true;$('liveAudio').play()");await("$('liveAudio').currentTime>.2",10000);js("$('gain').value=150;$('gain').dispatchEvent(new Event('input',{bubbles:true}))");await("audioGraph.media===$('liveAudio')&&audioGraph.gain.gain.value>1.4",5000);}finally{file.delete();}}

 @Test public void subtitleSessionExportsBothTracksWithoutRepeatingRecognition()throws Exception{
  js("state.kind='video';receive({type:'subtitles',language:'ja',target:'ar',originalCues:[{start:.123,end:2.456,text:'すまねえな',language:'ja'}],cues:[{start:.123,end:2.456,text:'آسف',language:'ja'}]})");
  await("!!$('subtitleTrack')",5000);
  for(String track:new String[]{"original","translated-ar"}){
   js("$('subtitleTrack').value='"+track+"';$('subtitleTrack').onchange();[...$('subtitleEditor').querySelectorAll('button')].find(b=>b.textContent==='حفظ ملف الترجمة SRT').click()");
   await("!$('result').hidden&&!!$('returnSubtitles')",10000);
   String name=new JSONArray("["+js("state.output.name")+"]").getString(0);
   File saved=FilesUtil.resolve(context,name);String text=new String(java.nio.file.Files.readAllBytes(saved.toPath()),java.nio.charset.StandardCharsets.UTF_8);
   assertTrue(text.contains(track.equals("original")?"すまねえな":"آسف"));assertTrue(text.contains("00:00:00,123 --> 00:00:02,456"));
   js("$('returnSubtitles').click()");await("!$('subtitleEditor').hidden&&$('result').hidden",5000);
  }
  js("$('subtitleTrack').value='original';$('subtitleTrack').onchange()");assertEquals("\"すまねえな\"",js("$('subtitleEditor').querySelector('.subtitleRow textarea').value"));
 }
}
