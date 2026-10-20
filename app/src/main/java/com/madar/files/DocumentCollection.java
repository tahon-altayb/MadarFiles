package com.madar.files;
import android.content.Context;
import android.graphics.Bitmap;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import java.io.*;
import java.util.List;
final class DocumentCollection {
 static File export(Context c,List<File> inputs,boolean images)throws Exception{
  if(inputs.isEmpty()||inputs.size()>20)throw new IOException("اختر من ملف واحد إلى 20 ملفًا");
  PDFBoxResourceLoader.init(c);File output=FilesUtil.output(c,"pdf");
  try(PDDocument target=new PDDocument()){
   for(File input:inputs){
    if(images){Bitmap bitmap=FilesUtil.image(input,2400);try{
     boolean wide=bitmap.getWidth()>bitmap.getHeight();PDRectangle size=wide?new PDRectangle(842,595):PDRectangle.A4;PDPage page=new PDPage(size);target.addPage(page);
     float scale=Math.min((size.getWidth()-32)/bitmap.getWidth(),(size.getHeight()-32)/bitmap.getHeight()),w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;
     try(PDPageContentStream stream=new PDPageContentStream(target,page)){stream.drawImage(JPEGFactory.createFromImage(target,bitmap,.9f),(size.getWidth()-w)/2,(size.getHeight()-h)/2,w,h);}
    }finally{bitmap.recycle();}}
    else try(PDDocument document=PDDocument.load(input)){if(document.isEncrypted())throw new IOException("اختر ملفات PDF غير محمية");if(target.getNumberOfPages()+document.getNumberOfPages()>200)throw new IOException("الحد الأقصى للدمج 200 صفحة");new PDFMergerUtility().appendDocument(target,document);}
   }
   target.save(output);return output;
  }catch(Exception|OutOfMemoryError e){output.delete();throw e;}
 }
}
