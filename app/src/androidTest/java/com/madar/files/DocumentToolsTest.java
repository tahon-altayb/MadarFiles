package com.madar.files;
import android.content.Context;
import android.graphics.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class DocumentToolsTest {
 @Test public void compressedDocumentRetainsTextAndPageSize()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();PDFBoxResourceLoader.init(c);
  File source=File.createTempFile("pdf-tool-",".pdf",c.getCacheDir()),compressed=null,protectedFile=null,extracted=null,merged=null;
  try{
   try(PDDocument doc=new PDDocument()){
    PDPage page=new PDPage();doc.addPage(page);Bitmap bitmap=Bitmap.createBitmap(2400,1600,Bitmap.Config.ARGB_8888);
    int[] pixels=new int[2400];java.util.Random random=new java.util.Random(7);for(int y=0;y<1600;y++){for(int x=0;x<2400;x++)pixels[x]=0xff000000|random.nextInt(0x1000000);bitmap.setPixels(pixels,0,2400,0,y,2400,1);}
    try(PDPageContentStream stream=new PDPageContentStream(doc,page)){stream.drawImage(JPEGFactory.createFromImage(doc,bitmap,.99f),0,0,500,400);stream.beginText();stream.setFont(PDType1Font.HELVETICA,18);stream.newLineAtOffset(30,700);stream.showText("Madar searchable text");stream.endText();}finally{bitmap.recycle();}doc.save(source);
   }
   compressed=PdfOptimizer.compress(c,source,"strong");assertTrue("opaque image PDF did not shrink: source="+source.length()+", result="+compressed.length(),compressed.length()<source.length());
   try(PDDocument doc=PDDocument.load(compressed)){assertEquals(1,doc.getNumberOfPages());assertEquals(612f,doc.getPage(0).getMediaBox().getWidth(),.1f);assertTrue(new PDFTextStripper().getText(doc).contains("Madar searchable text"));}
   extracted=PdfProcessor.export(c,source,new JSONObject().put("tool","extract").put("first",1).put("last",1));try(PDDocument doc=PDDocument.load(extracted)){assertTrue(new PDFTextStripper().getText(doc).contains("Madar searchable text"));}
   merged=DocumentCollection.export(c,java.util.Arrays.asList(source,extracted),false);try(PDDocument doc=PDDocument.load(merged)){assertEquals(2,doc.getNumberOfPages());assertTrue(new PDFTextStripper().getText(doc).contains("Madar searchable text"));}
   protectedFile=PdfSecurity.apply(c,source,new JSONObject().put("pdfPassword","test-password"));boolean refused=false;try(PDDocument ignored=PDDocument.load(protectedFile,"wrong")){}catch(com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException expected){refused=true;}assertTrue(refused);try(PDDocument doc=PDDocument.load(protectedFile,"test-password")){assertTrue(doc.isEncrypted());assertEquals(1,doc.getNumberOfPages());}
  }finally{source.delete();if(compressed!=null)compressed.delete();if(protectedFile!=null)protectedFile.delete();if(extracted!=null)extracted.delete();if(merged!=null)merged.delete();}
 }
 @Test public void targetImageIsDecodableAndWithinLimit()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File source=File.createTempFile("image-tool-",".png",c.getCacheDir()),result=null;Bitmap bitmap=Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);Paint paint=new Paint();for(int x=0;x<1200;x+=10){paint.setColor(Color.rgb(x%256,(x*3)%256,(x*7)%256));canvas.drawRect(x,0,x+10,800,paint);}try{ImageProcessor.writeImage(bitmap,source,"png",100);result=ImageSizeTarget.export(c,source,20);assertTrue(result.length()<=20*1024);Bitmap decoded=BitmapFactory.decodeFile(result.getPath());assertNotNull(decoded);assertTrue(decoded.getWidth()>0);decoded.recycle();}finally{bitmap.recycle();source.delete();if(result!=null)result.delete();}
 }
 @Test public void arabicCaptionsAppearOnlyInTheirTimeRange()throws Exception{
  CaptionOverlay overlay=new CaptionOverlay(new org.json.JSONArray().put(new JSONObject().put("start",1).put("end",2).put("text","مدار الملفات")));
  try{overlay.configure(new androidx.media3.common.util.Size(1080,1920));Bitmap before=overlay.getBitmap(0);assertEquals(0,Color.alpha(before.getPixel(0,0)));Bitmap during=overlay.getBitmap(1500000);assertTrue(during.getWidth()>500);assertTrue(during.getHeight()>20);assertTrue(Color.alpha(during.getPixel(during.getWidth()/2,during.getHeight()/2))>0);Bitmap after=overlay.getBitmap(2000000);assertEquals(0,Color.alpha(after.getPixel(0,0)));}finally{overlay.release();}
 }
 @Test public void compressionPreservesRealTransparency()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();PDFBoxResourceLoader.init(c);File input=File.createTempFile("alpha-pdf-",".pdf",c.getCacheDir()),output=null;
  try{try(PDDocument doc=new PDDocument()){PDPage page=new PDPage();doc.addPage(page);Bitmap bitmap=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);java.util.Random random=new java.util.Random(9);int[] row=new int[512];for(int y=0;y<512;y++){for(int x=0;x<512;x++)row[x]=((x<256?64:192)<<24)|random.nextInt(0x1000000);bitmap.setPixels(row,0,512,0,y,512,1);}try(PDPageContentStream stream=new PDPageContentStream(doc,page)){com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject image=JPEGFactory.createFromImage(doc,bitmap,.99f);
    // Build an explicit grayscale soft mask: Android JPEG alpha extraction is not a reliable PDF fixture.
    byte[] alpha=new byte[512*512];for(int y=0;y<512;y++)for(int x=0;x<512;x++)alpha[y*512+x]=(byte)(x<256?64:192);
    java.io.ByteArrayOutputStream encoded=new java.io.ByteArrayOutputStream();try(java.util.zip.DeflaterOutputStream zip=new java.util.zip.DeflaterOutputStream(encoded)){zip.write(alpha);}
    com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject mask=new com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject(doc,new ByteArrayInputStream(encoded.toByteArray()),com.tom_roush.pdfbox.cos.COSName.FLATE_DECODE,512,512,8,com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray.INSTANCE);
    image.getCOSObject().setItem(com.tom_roush.pdfbox.cos.COSName.SMASK,mask);stream.drawImage(image,0,0,500,500);}finally{bitmap.recycle();}doc.save(input);}
   output=PdfOptimizer.compress(c,input,"strong");try(PDDocument doc=PDDocument.load(output)){boolean mask=false;for(com.tom_roush.pdfbox.cos.COSName name:doc.getPage(0).getResources().getXObjectNames()){com.tom_roush.pdfbox.pdmodel.graphics.PDXObject object=doc.getPage(0).getResources().getXObject(name);if(object instanceof com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject)mask|=((com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject)object).getSoftMask()!=null;}assertTrue("Transparent image mask must remain",mask);}
  }finally{input.delete();if(output!=null)output.delete();}
 }
}
