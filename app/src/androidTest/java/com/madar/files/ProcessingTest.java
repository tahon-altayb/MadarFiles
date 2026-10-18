package com.madar.files;

import android.content.*;
import android.graphics.*;
import android.graphics.pdf.*;
import android.media.MediaMetadataRetriever;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ProcessingTest {
    Context c;
    File input;
    android.app.Activity activity;
    @Before public void prepare()throws Exception {
        c=InstrumentationRegistry.getInstrumentation().getTargetContext();activity=InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));input=new File(c.getCacheDir(),"test.png");
        Bitmap b=Bitmap.createBitmap(400,200,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);canvas.drawColor(Color.RED);Paint p=new Paint();p.setColor(Color.BLUE);canvas.drawRect(200,0,400,200,p);ImageProcessor.writeImage(b,input,"png",100);b.recycle();
    }
    @After public void clean(){if(activity!=null)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->activity.finish());input.delete();File[] files=FilesUtil.directory(c).listFiles();if(files!=null)for(File f:files)f.delete();}
    @Test public void cropAndRotateRetainPixels()throws Exception {
        JSONObject o=new JSONObject("{\"tool\":\"crop\",\"format\":\"png\",\"crop\":{\"x\":0.5,\"y\":0,\"w\":0.5,\"h\":1}}");
        File cropped=ImageProcessor.export(c,input,o);Bitmap b=FilesUtil.image(cropped,4096);assertEquals(200,b.getWidth());assertEquals(200,b.getHeight());assertEquals(Color.BLUE,b.getPixel(100,100));b.recycle();
        File rotated=ImageProcessor.export(c,input,new JSONObject("{\"tool\":\"rotate\",\"angle\":90,\"format\":\"png\"}"));b=FilesUtil.image(rotated,4096);assertEquals(200,b.getWidth());assertEquals(400,b.getHeight());b.recycle();
    }
    @Test public void resizeBackgroundAndPdfRoundTrip()throws Exception {
        File resized=ImageProcessor.export(c,input,new JSONObject("{\"tool\":\"resize\",\"width\":100,\"format\":\"webp\"}"));Bitmap b=FilesUtil.image(resized,4096);assertEquals(100,b.getWidth());assertEquals(50,b.getHeight());b.recycle();
        File bg=ImageProcessor.export(c,input,new JSONObject("{\"tool\":\"background\",\"padding\":10,\"color\":\"#00ff00\",\"format\":\"png\"}"));b=FilesUtil.image(bg,4096);assertEquals(480,b.getWidth());assertEquals(280,b.getHeight());assertEquals(Color.GREEN,b.getPixel(1,1));b.recycle();
        File pdf=ImageProcessor.export(c,input,new JSONObject("{\"tool\":\"convert\",\"format\":\"pdf\"}"));
        File png=PdfProcessor.export(c,pdf,new JSONObject("{\"tool\":\"convert\",\"first\":1,\"format\":\"png\",\"pdfWidth\":1000}"));b=FilesUtil.image(png,4096);assertEquals(1000,b.getWidth());assertEquals(500,b.getHeight());b.recycle();
        File extracted=PdfProcessor.export(c,pdf,new JSONObject("{\"tool\":\"extract\",\"first\":1,\"last\":1}"));try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(extracted,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer r=new PdfRenderer(fd)){assertEquals(1,r.getPageCount());}
    }
    @Test public void rejectsTraversalAndBadPdfRange()throws Exception {
        try{FilesUtil.resolve(c,"../test.png");fail("path traversal");}catch(IOException expected){}
        File pdf=ImageProcessor.export(c,input,new JSONObject("{\"tool\":\"convert\",\"format\":\"pdf\"}"));try{PdfProcessor.export(c,pdf,new JSONObject("{\"tool\":\"extract\",\"first\":2,\"last\":3}"));fail("invalid PDF range");}catch(IllegalArgumentException expected){}
    }
    @Test public void videoTrimMuteResizeAndAudio()throws Exception {
        File video=new File(c.getCacheDir(),"sample.mp4");try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("sample.mp4");OutputStream out=new FileOutputStream(video)){FilesUtil.copy(in,out);}
        exportVideo(video,"{\"tool\":\"trim\",\"start\":0.5,\"end\":1.5,\"height\":120,\"format\":\"mp4\",\"bitrate\":1000000}");
        File trimmed=latest();try(MediaMetadataRetriever r=new MediaMetadataRetriever()){r.setDataSource(trimmed.getAbsolutePath());long ms=Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));assertTrue("trim duration "+ms,ms>=850&&ms<=1300);assertEquals("120",r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));}
        exportVideo(video,"{\"tool\":\"mute\",\"format\":\"mp4\"}");try(MediaMetadataRetriever r=new MediaMetadataRetriever()){r.setDataSource(latest().getAbsolutePath());assertNotEquals("yes",r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));}
        exportVideo(video,"{\"tool\":\"audio\"}");try(MediaMetadataRetriever r=new MediaMetadataRetriever()){r.setDataSource(latest().getAbsolutePath());assertEquals("yes",r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));assertNotEquals("yes",r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));}video.delete();
    }

    @Test public void sharedInterfaceLoadsAndFitsPhone()throws Exception {
        android.webkit.WebView[] found=new android.webkit.WebView[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->found[0]=findWeb(activity.getWindow().getDecorView()));
        assertNotNull(found[0]);long deadline=System.currentTimeMillis()+15000;
        while(System.currentTimeMillis()<deadline){if("true".equals(evaluate(found[0],"typeof window.receive==='function' && document.getElementById('tools').children.length===7")))break;Thread.sleep(100);}
        assertEquals("true",evaluate(found[0],"typeof window.receive==='function'"));
        assertEquals("true",evaluate(found[0],"getComputedStyle(document.body).backgroundColor==='rgb(248, 244, 237)' && document.body.scrollWidth<=innerWidth"));
        String thumb;Bitmap b=FilesUtil.image(input,480);try{thumb=FilesUtil.thumbnail(b);}finally{b.recycle();}
        JSONObject source=new JSONObject().put("type","source").put("kind","image").put("name","صورة اختبار.png").put("width",400).put("height",200).put("size",input.length()).put("mime","image/png").put("thumbnail",thumb);
        evaluate(found[0],"window.receive("+source+");true");Thread.sleep(300);
        File shots=new File(c.getExternalFilesDir(null),"qa");assertTrue(shots.isDirectory()||shots.mkdirs());
        Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(screenshot);try(OutputStream out=new FileOutputStream(new File(shots,"image-tools.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}finally{screenshot.recycle();}
        evaluate(found[0],"openTool('resize');true");assertEquals("true",evaluate(found[0],"!document.getElementById('sheet').hidden && !!document.getElementById('width')"));
        evaluate(found[0],"document.getElementById('sheet').hidden=true;selectKind('video');true");assertEquals("true",evaluate(found[0],"document.getElementById('tools').children.length===7"));
    }
    private android.webkit.WebView findWeb(android.view.View v){if(v instanceof android.webkit.WebView)return (android.webkit.WebView)v;if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int i=0;i<g.getChildCount();i++){android.webkit.WebView w=findWeb(g.getChildAt(i));if(w!=null)return w;}}return null;}
    private String evaluate(android.webkit.WebView web,String script)throws Exception{java.util.concurrent.CountDownLatch latch=new java.util.concurrent.CountDownLatch(1);String[] value=new String[1];InstrumentationRegistry.getInstrumentation().runOnMainSync(()->web.evaluateJavascript(script,result->{value[0]=result;latch.countDown();}));assertTrue(latch.await(5,java.util.concurrent.TimeUnit.SECONDS));return value[0];}

    @Test public void offlineOcrAndEditableWord()throws Exception {
        File textImage=new File(c.getCacheDir(),"ocr-test.png");Bitmap b=Bitmap.createBitmap(1600,500,Bitmap.Config.ARGB_8888);Canvas cv=new Canvas(b);cv.drawColor(Color.WHITE);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.BLACK);p.setTextSize(100);cv.drawText("MADAR TEST 12345",80,160,p);p.setTextSize(95);cv.drawText("مدار الملفات",80,350,p);ImageProcessor.writeImage(b,textImage,"png",100);b.recycle();
        String read=TextProcessor.image(c,textImage);assertTrue("OCR result: "+read,read.contains("12345"));assertTrue("Arabic OCR: "+read,read.matches("(?s).*[\\u0600-\\u06ff].*"));
        File word=TextProcessor.save(c,"مدار الملفات\nEditable text 12345","docx");try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(word)){assertNotNull(zip.getEntry("word/document.xml"));String xml=new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(xml.contains("مدار الملفات"));assertTrue(xml.contains("<w:bidi/>")&&xml.contains("Editable text 12345"));}textImage.delete();
    }
    @Test public void pdfTextAndScannedOcr()throws Exception {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(c);File pdf=new File(c.getCacheDir(),"text.pdf");
        try(com.tom_roush.pdfbox.pdmodel.PDDocument doc=new com.tom_roush.pdfbox.pdmodel.PDDocument()){com.tom_roush.pdfbox.pdmodel.PDPage page=new com.tom_roush.pdfbox.pdmodel.PDPage();doc.addPage(page);try(com.tom_roush.pdfbox.pdmodel.PDPageContentStream stream=new com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc,page)){stream.beginText();stream.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA,20);stream.newLineAtOffset(50,700);stream.showText("Madar editable PDF 12345");stream.endText();}doc.save(pdf);}
        assertTrue(TextProcessor.pdf(c,pdf,1,1,t->{}).contains("Madar editable PDF 12345"));pdf.delete();
    }
    @Test public void offlineMusicProcessingPreservesVideo()throws Exception {
        File mixed=new File(c.getCacheDir(),"mixed.mp4");try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("music-test.mp4");OutputStream out=new FileOutputStream(mixed)){FilesUtil.copy(in,out);}
        VideoExportService.lastEvent=null;InstrumentationRegistry.getInstrumentation().runOnMainSync(()->c.startForegroundService(new Intent(c,VideoExportService.class).putExtra("input",mixed.getAbsolutePath()).putExtra("options","{\"tool\":\"music\",\"sample\":true}").putExtra("duration",4000L).putExtra("height",240)));
        long deadline=System.currentTimeMillis()+300000;File result=null;while(System.currentTimeMillis()<deadline){String data=VideoExportService.lastEvent;if(data!=null){JSONObject event=new JSONObject(data);if(event.optString("type").equals("error"))fail(data);if(event.optString("type").equals("result")){result=FilesUtil.resolve(c,event.getString("name"));break;}}Thread.sleep(100);}
        assertNotNull("offline separation timed out",result);assertEquals(videoDigest(mixed),videoDigest(result));
        java.util.concurrent.atomic.AtomicBoolean cancel=new java.util.concurrent.atomic.AtomicBoolean();AudioPipeline.Decoded before=AudioPipeline.decode(c,mixed,0,cancel,(n,t)->{}),after=AudioPipeline.decode(c,result,0,cancel,(n,t)->{});assertTrue(after.pcm.length()>10000);assertTrue(Math.abs(before.pcm.length()-after.pcm.length())<20000);before.pcm.delete();after.pcm.delete();mixed.delete();
    }
    private String videoDigest(File file)throws Exception{android.media.MediaExtractor ex=new android.media.MediaExtractor();try{ex.setDataSource(file.getAbsolutePath());ex.selectTrack(AudioPipeline.track(ex,"video/"));java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");java.nio.ByteBuffer buffer=java.nio.ByteBuffer.allocate(1024*1024);while(true){buffer.clear();int n=ex.readSampleData(buffer,0);if(n<0)break;byte[] data=new byte[n];buffer.position(0);buffer.get(data);digest.update(data);ex.advance();}return java.util.Arrays.toString(digest.digest());}finally{ex.release();}}
    private File latest(){File[] files=FilesUtil.directory(c).listFiles(f->!f.getName().endsWith(".partial"));assertNotNull(files);java.util.Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));assertTrue(files.length>0);return files[0];}
    private void exportVideo(File input,String options)throws Exception{
        VideoExportService.lastEvent=null;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{c.startForegroundService(new Intent(c,VideoExportService.class).putExtra("input",input.getAbsolutePath()).putExtra("options",options).putExtra("duration",3000L).putExtra("height",240));}catch(Exception e){throw new RuntimeException(e);}});
        long end=System.currentTimeMillis()+120000;
        while(System.currentTimeMillis()<end){String event=VideoExportService.lastEvent;if(event!=null){JSONObject o=new JSONObject(event);if(o.getString("type").equals("error"))fail(event);if(o.getString("type").equals("result"))return;}Thread.sleep(100);}
        fail("video export timed out");
    }
}
