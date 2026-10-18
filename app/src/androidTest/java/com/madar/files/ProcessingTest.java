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
    private File latest(){File[] files=FilesUtil.directory(c).listFiles(f->!f.getName().endsWith(".partial"));assertNotNull(files);java.util.Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));assertTrue(files.length>0);return files[0];}
    private void exportVideo(File input,String options)throws Exception{
        VideoExportService.lastEvent=null;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{c.startForegroundService(new Intent(c,VideoExportService.class).putExtra("input",input.getAbsolutePath()).putExtra("options",options).putExtra("duration",3000L).putExtra("height",240));}catch(Exception e){throw new RuntimeException(e);}});
        long end=System.currentTimeMillis()+120000;
        while(System.currentTimeMillis()<end){String event=VideoExportService.lastEvent;if(event!=null){JSONObject o=new JSONObject(event);if(o.getString("type").equals("error"))fail(event);if(o.getString("type").equals("result"))return;}Thread.sleep(100);}
        fail("video export timed out");
    }
}
