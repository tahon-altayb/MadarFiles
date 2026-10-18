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
    static File export(Context c,File source,JSONObject o)throws Exception {
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
