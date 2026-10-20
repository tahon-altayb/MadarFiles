package com.madar.files;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.*;
import android.os.ParcelFileDescriptor;
import org.json.JSONObject;
import java.io.*;

final class PdfProcessor {
    static Bitmap render(PdfRenderer renderer,int index,int width) {
        try(PdfRenderer.Page page=renderer.openPage(index)) {
            int height=Math.max(1,Math.round((float)width*page.getHeight()/page.getWidth()));if(height>4096){width=Math.max(1,width*4096/height);height=4096;}
            Bitmap b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);new Canvas(b).drawColor(Color.WHITE);page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);return b;
        }
    }
    private static File organize(Context c,File source,JSONObject o)throws Exception{
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(c);File output=FilesUtil.output(c,"pdf");
        try(com.tom_roush.pdfbox.pdmodel.PDDocument doc=com.tom_roush.pdfbox.pdmodel.PDDocument.load(source);com.tom_roush.pdfbox.pdmodel.PDDocument target=new com.tom_roush.pdfbox.pdmodel.PDDocument()){
            String[] order=o.getString("pageOrder").trim().split("[,،\\s]+");if(order.length<1||order.length>100)throw new IOException("اختر من صفحة إلى 100 صفحة");int rotation=o.optInt("pageRotation",0);if(rotation!=0&&rotation!=90&&rotation!=180&&rotation!=270)throw new IOException("تدوير غير صالح");
            for(String item:order){int n=Integer.parseInt(item);if(n<1||n>doc.getNumberOfPages())throw new IOException("رقم صفحة غير موجود");com.tom_roush.pdfbox.pdmodel.PDPage page=target.importPage(doc.getPage(n-1));page.setRotation((doc.getPage(n-1).getRotation()+rotation)%360);}
            target.save(output);return output;
        }catch(Exception|OutOfMemoryError e){output.delete();throw e;}
    }
    static File export(Context c,File source,JSONObject o)throws Exception {
        if(o.optString("tool").equals("protectPdf"))return PdfSecurity.apply(c,source,o);
        if(o.optString("tool").equals("compressPdf"))return PdfOptimizer.compress(c,source,o.optString("compression","balanced"));
        if(o.optString("tool").equals("pages"))return organize(c,source,o);
        if(o.optString("tool").equals("extract")){
            int first=o.getInt("first"),last=o.optInt("last",first);if(first<1||last<first||last-first>=100)throw new IOException("اختر نطاقًا حتى 100 صفحة");
            StringBuilder order=new StringBuilder();for(int i=first;i<=last;i++){if(i>first)order.append(',');order.append(i);}return organize(c,source,new JSONObject().put("pageOrder",order.toString()));
        }
        File output=null;
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd)){
            int first=o.getInt("first")-1,last=o.optInt("last",first+1)-1,width=(int)FilesUtil.bounded(o.optInt("pdfWidth",1600),500,2400);
            boolean extract=o.getString("tool").equals("extract");if(first<0||first>=renderer.getPageCount()||last<first||last>=renderer.getPageCount()||last-first>=50)throw new IllegalArgumentException("نطاق الصفحات غير صحيح");
            String format=extract?"pdf":o.optString("format","png");if(!format.matches("png|jpg|pdf"))throw new IllegalArgumentException("صيغة غير صالحة");output=FilesUtil.output(c,format);
            if(extract){
                PdfDocument doc=new PdfDocument();try{
                    for(int i=first;i<=last;i++){Bitmap b=render(renderer,i,width);try{PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(b.getWidth(),b.getHeight(),i-first+1).create());page.getCanvas().drawBitmap(b,0,0,new Paint(Paint.FILTER_BITMAP_FLAG));doc.finishPage(page);}finally{b.recycle();}}
                    try(OutputStream out=new FileOutputStream(output)){doc.writeTo(out);}
                }finally{doc.close();}
            } else {Bitmap b=render(renderer,first,width);try{ImageProcessor.writeImage(b,output,format,92);}finally{b.recycle();}}
            return output;
        }catch(Exception|OutOfMemoryError e){if(output!=null)output.delete();throw e;}
    }
}
