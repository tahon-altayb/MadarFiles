package com.madar.files;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.image.*;
import java.io.*;
import java.util.*;

/** Optimizes embedded opaque images while retaining text and vector content. */
final class PdfOptimizer {
 static File compress(Context context,File source,String level)throws Exception {
  if(!Arrays.asList("light","balanced","strong").contains(level))throw new IOException("مستوى ضغط غير صالح");
  PDFBoxResourceLoader.init(context);File output=FilesUtil.output(context,"pdf");
  int bound=level.equals("light")?2400:level.equals("strong")?1000:1600;
  float quality=level.equals("light")?.88f:level.equals("strong")?.55f:.75f;
  try(PDDocument doc=PDDocument.load(source)){
   if(doc.isEncrypted())throw new IOException("افتح نسخة غير محمية قبل الضغط");
   Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
   for(PDPage page:doc.getPages())optimize(doc,page.getResources(),seen,bound,quality,0);
   doc.save(output);
   // A PDF already optimized may not shrink: preserve the smaller original bytes.
   if(output.length()>=source.length())try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(output)){FilesUtil.copy(in,out);}
   return output;
  }catch(Exception|OutOfMemoryError e){output.delete();throw e;}
 }
 private static void optimize(PDDocument doc,PDResources resources,Set<Object> seen,int bound,float quality,int depth)throws IOException {
  if(resources==null||depth>30||!seen.add(resources.getCOSObject()))return;
  List<COSName> names=new ArrayList<>();for(COSName name:resources.getXObjectNames())names.add(name);
  for(COSName name:names){
   PDXObject object=resources.getXObject(name);
   if(object instanceof PDFormXObject){optimize(doc,((PDFormXObject)object).getResources(),seen,bound,quality,depth+1);continue;}
   if(!(object instanceof PDImageXObject))continue;
   PDImageXObject image=(PDImageXObject)object;
   if(!seen.add(image.getCOSObject()))continue;
   // Do not flatten transparency, stencils, or huge decoded images into memory.
   if(image.isStencil()||image.getSoftMask()!=null||image.getMask()!=null||image.getColorKeyMask()!=null||image.getWidth()<1||image.getHeight()<1||(long)image.getWidth()*image.getHeight()>16000000L)continue;
   Bitmap bitmap=null,scaled=null;
   try{
    bitmap=image.getImage();if(bitmap==null)continue;
    double ratio=Math.min(1.0,(double)bound/Math.max(bitmap.getWidth(),bitmap.getHeight()));
    int width=Math.max(1,(int)Math.round(bitmap.getWidth()*ratio)),height=Math.max(1,(int)Math.round(bitmap.getHeight()*ratio));
    scaled=Bitmap.createBitmap(width,height,Bitmap.Config.RGB_565);Canvas canvas=new Canvas(scaled);canvas.drawColor(android.graphics.Color.WHITE);canvas.drawBitmap(bitmap,null,new android.graphics.Rect(0,0,width,height),new Paint(Paint.FILTER_BITMAP_FLAG));
    PDImageXObject candidate=JPEGFactory.createFromImage(doc,scaled,quality);
    if(candidate.getCOSObject().getLength()<image.getCOSObject().getLength())resources.put(name,candidate);
   }finally{if(scaled!=null)scaled.recycle();if(bitmap!=null)bitmap.recycle();}
  }
 }
}
