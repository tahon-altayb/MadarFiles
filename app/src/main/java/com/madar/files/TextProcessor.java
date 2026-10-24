package com.madar.files;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import com.googlecode.tesseract.android.TessBaseAPI;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

final class TextProcessor {
 interface Progress{void update(String text);}
 private static TessBaseAPI engine(Context c,String language)throws Exception{
  File dir=new File(c.getFilesDir(),"tesseract"),data=new File(dir,"tessdata");if(!data.isDirectory()&&!data.mkdirs())throw new IOException("تعذر تجهيز OCR");
  for(String lang:new String[]{"ara","eng"}){File source=OfflineAssets.file(c,lang+".traineddata"),dest=new File(data,source.getName());if(!dest.isFile())try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(dest)){FilesUtil.copy(in,out);}}
  TessBaseAPI tess=new TessBaseAPI();if(!tess.init(dir.getAbsolutePath(),language,TessBaseAPI.OEM_LSTM_ONLY)){tess.recycle();throw new IOException("تعذر تهيئة قراءة النصوص");}tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);return tess;
 }
 static String image(Context c,File file)throws Exception{return image(c,file,"ara+eng");}
 static String image(Context c,File file,String language)throws Exception{
  if(!language.matches("ara|eng|ara\\+eng"))throw new IOException("لغة قراءة غير مدعومة");
  Bitmap b=FilesUtil.image(file,3000);try{if(!language.equals("eng")){try(ArabicOcr ocr=new ArabicOcr(c)){return ArabicOcr.text(ocr.read(b));}}TessBaseAPI tess=engine(c,"eng");try{tess.setImage(b);return TextQuality.clean(tess.getUTF8Text());}finally{tess.recycle();}}finally{b.recycle();}
 }
 static String pdf(Context c,File file,int first,int last,Progress progress)throws Exception{return pdf(c,file,first,last,"ara+eng",false,progress);}
 static String pdf(Context c,File file,int first,int last,String language,boolean forceOcr,Progress progress)throws Exception{
  if(!language.matches("ara|eng|ara\\+eng"))throw new IOException("لغة قراءة غير مدعومة");PDFBoxResourceLoader.init(c);StringBuilder text=new StringBuilder();TessBaseAPI tess=null;ArabicOcr ocr=null;
  try(PDDocument doc=PDDocument.load(file);ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd)){
   if(first<1||last<first||last>doc.getNumberOfPages()||last-first>=50)throw new IllegalArgumentException("اختر نطاقًا صحيحًا لا يزيد عن 50 صفحة");PDFTextStripper stripper=new PDFTextStripper();stripper.setSortByPosition(true);
   for(int page=first;page<=last;page++){
    progress.update("قراءة الصفحة "+page+" من "+last);stripper.setStartPage(page);stripper.setEndPage(page);String raw=stripper.getText(doc);String content=TextQuality.clean(raw);
    if(forceOcr||!TextQuality.usable(raw,language)){
     progress.update("إعادة قراءة الصفحة "+page+" بصريًا؛ طبقة النص مفقودة أو مشوّهة");Bitmap bitmap=PdfProcessor.render(renderer,page-1,2400);try{if(language.equals("eng")){if(tess==null)tess=engine(c,"eng");tess.setImage(bitmap);content=TextQuality.clean(tess.getUTF8Text());tess.clear();}else{if(ocr==null)ocr=new ArabicOcr(c);content=ArabicOcr.text(ocr.read(bitmap));}}finally{bitmap.recycle();}
    }
    if(page>first)text.append('\f');text.append(content);if(text.length()>1000000)throw new IOException("النص كبير جدًا؛ اختر نطاق صفحات أصغر");
   }
  }finally{if(tess!=null)tess.recycle();if(ocr!=null)ocr.close();}return text.toString();
 }
 static File save(Context c,String text,String format)throws Exception{
  if(text==null||text.trim().isEmpty())throw new IllegalArgumentException("لا يوجد نص للحفظ");if(text.length()>1000000)throw new IllegalArgumentException("النص طويل جدًا");
  if(!format.equals("docx")&&!format.equals("txt"))throw new IllegalArgumentException("صيغة النص غير مدعومة");
  File output=FilesUtil.output(c,format);try{if(format.equals("txt")){try(OutputStream out=new FileOutputStream(output)){out.write(text.getBytes(StandardCharsets.UTF_8));}}else docx(output,text);return output;}catch(Exception e){output.delete();throw e;}
 }
 private static String xml(String s){return s.replaceAll("[\\x00-\\x08\\x0B\\x0E-\\x1F]","").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
 private static void entry(ZipOutputStream zip,String name,String content)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(content.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
 private static void wordRun(StringBuilder body,String text,boolean rtl){body.append("<w:r><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:cs=\"Arial\"/><w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/>").append(rtl?"<w:rtl/>":"<w:rtl w:val=\"0\"/>").append("</w:rPr>");String[] pieces=text.split("\\t",-1);for(int i=0;i<pieces.length;i++){if(i>0)body.append("<w:tab/>");body.append("<w:t xml:space=\"preserve\">").append(xml(pieces[i])).append("</w:t>");}body.append("</w:r>");}
 static void docx(File output,String text)throws IOException{
  StringBuilder body=new StringBuilder();String[] pages=text.split("\\f",-1);
  for(int page=0;page<pages.length;page++){
   if(page>0)body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
   for(String line:pages[page].split("\\r?\\n",-1)){boolean rtl=line.matches("(?s).*[\\u0600-\\u06ff].*");body.append("<w:p><w:pPr>").append(rtl?"<w:bidi/><w:jc w:val=\"left\"/>":"<w:jc w:val=\"left\"/>").append("</w:pPr>");java.util.regex.Matcher latin=java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9 \\t.,:/%+\\-]*").matcher(line);int at=0;while(latin.find()){if(latin.start()>at)wordRun(body,line.substring(at,latin.start()),rtl);wordRun(body,latin.group(),false);at=latin.end();}if(at<line.length())wordRun(body,line.substring(at),rtl);if(line.isEmpty())wordRun(body,"",rtl);body.append("</w:p>");}

  }
  try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(output))){
   entry(zip,"[Content_Types].xml","<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
   entry(zip,"_rels/.rels","<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
   entry(zip,"word/document.xml","<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"+body+"<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr></w:body></w:document>");
  }
 }
}
