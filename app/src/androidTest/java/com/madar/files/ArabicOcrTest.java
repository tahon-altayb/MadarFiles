package com.madar.files;
import android.content.Context;
import android.graphics.*;
import android.text.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.zip.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import static org.junit.Assert.*;
/** Synthetic receipt; never publishes user screenshots or documents. Full native build only. */
@RunWith(AndroidJUnit4.class)
public class ArabicOcrTest {
 private Bitmap receipt(){Bitmap bitmap=Bitmap.createBitmap(1200,720,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);TextPaint text=new TextPaint(Paint.ANTI_ALIAS_FLAG);text.setColor(Color.BLACK);text.setTextSize(46);String[] labels={"الفاتورة","قيمة المنتجات","التوصيل","المجموع"};String[] values={"","125.00 SAR","15.00 SAR","140.00 SAR"};for(int i=0;i<labels.length;i++){canvas.save();canvas.translate(510,40+i*145);StaticLayout layout=StaticLayout.Builder.obtain(labels[i],0,labels[i].length(),text,620).setTextDirection(TextDirectionHeuristics.RTL).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();layout.draw(canvas);canvas.restore();if(!values[i].isEmpty())canvas.drawText(values[i],70,90+i*145,text);}return bitmap;}
 private void meaning(String text){System.out.println("MADAR_SYNTHETIC_ARABIC_OCR "+text);assertTrue("Invoice title: "+text,text.contains("الفاتورة"));assertTrue("Product label: "+text,text.contains("المنتجات"));assertTrue("Delivery label: "+text,text.contains("التوصيل"));assertTrue("Total label: "+text,text.contains("المجموع"));assertTrue("Latin currency preserved: "+text,text.contains("SAR"));assertTrue("Amount preserved: "+text,text.contains("125.00"));}
 @Test public void arabicReceiptKeepsLabelsAmountsAndLogicalOrder()throws Exception{Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();Bitmap bitmap=receipt();try(ArabicOcr ocr=new ArabicOcr(c)){meaning(ArabicOcr.text(ocr.read(bitmap)));assertEquals("الفاتورة",ArabicOcr.logicalArabic("ةروتافلا"));assertEquals("125.00 SAR",ArabicOcr.logicalArabic("125.00 SAR"));}finally{bitmap.recycle();}}
 @Test public void corruptedPdfTextLayerIsReReadAndWordContainsEditableArabic()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();PDFBoxResourceLoader.init(c);File input=File.createTempFile("ocr-broken-layer-",".pdf",c.getCacheDir());Bitmap bitmap=receipt();File word=null;
  try{try(PDDocument doc=new PDDocument()){PDPage page=new PDPage(new PDRectangle(600,360));doc.addPage(page);try(PDPageContentStream content=new PDPageContentStream(doc,page)){content.beginText();content.setFont(PDType1Font.HELVETICA,12);content.newLineAtOffset(20,300);content.showText("CVADUAL MANS WEE QZX bad mapping");content.endText();content.drawImage(LosslessFactory.createFromImage(doc,bitmap),0,0,600,360);}doc.save(input);}meaning(TextProcessor.pdf(c,input,1,1,"ara",false,s->{}));word=LayoutWordExporter.export(c,input,1,1,"ara",false);String xml="";try(ZipInputStream zip=new ZipInputStream(new FileInputStream(word))){ZipEntry entry;while((entry=zip.getNextEntry())!=null)if(entry.getName().equals("word/document.xml")){ByteArrayOutputStream out=new ByteArrayOutputStream();FilesUtil.copy(zip,out);xml=out.toString("UTF-8");break;}}assertTrue(xml.contains("الفاتورة"));assertTrue(xml.contains("التوصيل"));assertFalse(xml.contains("CVADUAL"));assertTrue(xml.contains("w:txbxContent"));}finally{bitmap.recycle();input.delete();if(word!=null)word.delete();}
 }
}
