package com.madar.files;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.zip.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
public class WordTableExportTest {
 @Test public void pdfGridBecomesActualWordCells()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();PDFBoxResourceLoader.init(c);File input=File.createTempFile("word-table-",".pdf",c.getCacheDir()),output=null;
  try{try(PDDocument doc=new PDDocument()){PDPage page=new PDPage(new PDRectangle(400,400));doc.addPage(page);try(PDPageContentStream s=new PDPageContentStream(doc,page)){for(int y:new int[]{180,240,300}){s.moveTo(40,y);s.lineTo(360,y);}for(int x:new int[]{40,200,360}){s.moveTo(x,180);s.lineTo(x,300);}s.stroke();String[] text={"Product","Amount","Madar editable item","125.00"};int i=0;for(int y:new int[]{270,210})for(int x:new int[]{50,210}){s.beginText();s.setFont(PDType1Font.HELVETICA,12);s.newLineAtOffset(x,y);s.showText(text[i++]);s.endText();}}doc.save(input);}output=LayoutWordExporter.export(c,input,1,1,"eng",false);try(ZipFile zip=new ZipFile(output)){String xml=new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(xml.contains("<w:tbl>"));assertTrue(xml.contains("<w:tc>"));assertEquals(1,xml.split("125.00",-1).length-1);assertTrue(xml.contains("Product"));assertTrue(xml.contains("Amount"));}String encoded=android.util.Base64.encodeToString(java.nio.file.Files.readAllBytes(output.toPath()),android.util.Base64.NO_WRAP);for(int at=0;at<encoded.length();at+=2000)System.out.println("MADAR_TABLE_DOCX_PART_"+(at/2000)+":"+encoded.substring(at,Math.min(encoded.length(),at+2000)));File report=new File(c.getExternalFilesDir(null),"table-editable.docx");try(InputStream in=new FileInputStream(output);OutputStream out=new FileOutputStream(report)){FilesUtil.copy(in,out);}
  }finally{input.delete();if(output!=null)output.delete();}
 }
}
