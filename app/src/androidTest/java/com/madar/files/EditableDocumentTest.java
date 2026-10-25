package com.madar.files;
import android.content.Context;
import android.graphics.*;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;
/** Public synthetic documents: never embeds a user's PDF, screenshot or OCR text. */
public class EditableDocumentTest {
 private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
 private static void text(PDPageContentStream s,String value,float x,float y,float size)throws Exception{s.beginText();s.setFont(PDType1Font.HELVETICA,size);s.newLineAtOffset(x,y);s.showText(value);s.endText();}
 private File fixture()throws Exception{Context c=context();PDFBoxResourceLoader.init(c);File input=File.createTempFile("editable-figure-",".pdf",c.getCacheDir());try(PDDocument doc=new PDDocument()){PDPage page=new PDPage(new PDRectangle(600,800));doc.addPage(page);Bitmap image=Bitmap.createBitmap(720,400,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(Color.rgb(224,236,244));Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);paint.setTextSize(32);canvas.drawText("WORDS INSIDE THE PICTURE",35,70,paint);paint.setStrokeWidth(3);for(int x:new int[]{35,350,680})canvas.drawLine(x,120,x,350,paint);for(int y:new int[]{120,230,350})canvas.drawLine(35,y,680,y,paint);
  try(PDPageContentStream s=new PDPageContentStream(doc,page)){text(s,"An illustrated document",60,740,20);s.drawImage(LosslessFactory.createFromImage(doc,image),60,450,360,200);text(s,"Hidden overlay inside figure",80,600,10);text(s,"Caption below picture",60,430,14);text(s,"Alpha paragraph first line.",60,390,12);text(s,"The paragraph continues on the next line.",60,374,12);text(s,"Last editable line with number 125.00.",60,358,12);s.addRect(40,40,520,720);s.stroke();}finally{image.recycle();}doc.save(input);}return input;}
 private String xml(File file)throws Exception{try(ZipFile z=new ZipFile(file)){return new String(z.getInputStream(z.getEntry("word/document.xml")).readAllBytes(),StandardCharsets.UTF_8);}}
 @Test public void pictureWithItsOwnWordsStaysAPictureAndCaptionStaysEditableInBothWordModes()throws Exception{
  File source=fixture(),layout=null,flow=null;try{try(PDDocument doc=PDDocument.load(source)){LayoutWordExporter.PageData data=LayoutWordExporter.analyze(context(),doc,source,1,"eng",false);try{JSONArray blocks=data.structure.getJSONArray("blocks");for(int i=0;i<blocks.length();i++){JSONObject b=blocks.getJSONObject(i);if(b.optString("text").equals("Caption below picture"))assertTrue("Caption uses the available column, not a tight glyph box",b.getDouble("w")*data.width>=360);}}finally{data.close();}}layout=LayoutWordExporter.export(context(),source,1,1,"eng",false,true);flow=LayoutWordExporter.export(context(),source,1,1,"eng",false,false);
   for(File word:new File[]{layout,flow}){String xml=xml(word);assertFalse("No invented layout table",xml.contains("<w:tbl>"));assertTrue(xml.contains("<pic:pic>"));assertFalse(xml.contains("WORDS INSIDE THE PICTURE"));assertFalse(xml.contains("Hidden overlay inside figure"));assertTrue(xml.contains("Caption below picture"));assertTrue(xml.contains("The paragraph continues on the next line."));assertEquals(1,xml.split("Caption below picture",-1).length-1);assertTrue(xml.indexOf("<pic:pic>")<xml.indexOf("Caption below picture"));assertTrue(xml.indexOf("Caption below picture")<xml.indexOf("Alpha paragraph first line."));javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));try(ZipFile z=new ZipFile(word)){assertEquals(1,z.stream().filter(e->e.getName().startsWith("word/media/")).count());}}
   assertTrue(xml(layout).contains("w:txbxContent"));assertTrue(xml(layout).contains("<wp:anchor"));assertFalse(xml(flow).contains("w:txbxContent"));assertTrue(xml(flow).contains("<wp:inline"));
   ByteArrayOutputStream buffer=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(buffer)){for(File f:new File[]{source,layout,flow}){String name=f==source?"source.pdf":f==layout?"layout.docx":"flow.docx";z.putNextEntry(new ZipEntry(name));z.write(java.nio.file.Files.readAllBytes(f.toPath()));z.closeEntry();}}String encoded=android.util.Base64.encodeToString(buffer.toByteArray(),android.util.Base64.NO_WRAP);for(int i=0;i<encoded.length();i+=2000)android.util.Log.i("MADAR_LAYOUT_FIXTURE","PART "+i+" "+encoded.substring(i,Math.min(encoded.length(),i+2000)));
  }finally{source.delete();if(layout!=null)layout.delete();if(flow!=null)flow.delete();}
 }
 @Test public void adjacentSourceLinesBecomeOneParagraphInsteadOfOneShapePerWord()throws Exception{List<LayoutWordExporter.Line> lines=new ArrayList<>();for(int i=0;i<3;i++){LayoutWordExporter.Line l=new LayoutWordExporter.Line();l.x=40;l.y=50+i*16;l.w=320;l.h=13;l.size=12;l.text="Source line "+i;l.font="Arial";lines.add(l);}JSONArray paragraphs=LayoutWordExporter.paragraphs(lines,600,800,Collections.emptyList());assertEquals(1,paragraphs.length());assertEquals("Source line 0\nSource line 1\nSource line 2",paragraphs.getJSONObject(0).getString("text"));}
 private JSONObject block(String text,double x,double y,double w,double h)throws Exception{return LayoutWordExporter.block("text",text,(float)(x*600),(float)(y*800),(float)(w*600),(float)(h*800),600,800,12);}
 @Test public void arabicColumnsAreReadDownTheRightColumnBeforeTheLeftColumn()throws Exception{JSONArray blocks=new JSONArray().put(block("عنوان الصفحة",.1,.02,.8,.05)).put(block("العمود الأيسر",.1,.2,.35,.4)).put(block("العمود الأيمن",.55,.2,.35,.4)).put(block("آخر الصفحة",.1,.8,.8,.05));List<JSONObject> order=EditableWord.ordered(blocks);assertEquals("عنوان الصفحة",order.get(0).getString("text"));assertEquals("العمود الأيمن",order.get(1).getString("text"));assertEquals("العمود الأيسر",order.get(2).getString("text"));assertEquals("آخر الصفحة",order.get(3).getString("text"));}
 @Test public void sourceCropBoxOffsetsAreAppliedToImageCoordinates(){PDPage page=new PDPage(new PDRectangle(600,800));page.setCropBox(new PDRectangle(20,30,500,700));PointF p=LayoutWordExporter.point(page,80,600);assertEquals(60,p.x,.01);assertEquals(130,p.y,.01);page.setRotation(90);p=LayoutWordExporter.point(page,80,600);assertEquals(570,p.x,.01);assertEquals(60,p.y,.01);}
}
