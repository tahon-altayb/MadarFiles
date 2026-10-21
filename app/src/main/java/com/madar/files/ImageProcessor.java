package com.madar.files;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import org.json.JSONObject;
import java.io.*;

final class ImageProcessor {
    static File export(Context c, File source, JSONObject o) throws Exception {
        if(o.optString("tool").equals("targetImage"))return ImageSizeTarget.export(c,source,o.getInt("targetKb"));
        File destination=null;
        Bitmap b=FilesUtil.image(source,o.optString("tool").equals("removebg")?2048:4096);File result=null;
        try {
            String tool=o.getString("tool"),format=o.optString("format","jpg");
            if(tool.equals("removebg")){Bitmap next=PersonCutout.cut(c,b,o.optString("cutBackground","transparent").equals("transparent"),Color.parseColor(o.optString("color","#eadcc7")),o.optString("cutModel","fine"),o.optDouble("cutThreshold",50),o.optDouble("cutSoftness",50));b.recycle();b=next;
            }else if(tool.equals("adjust")){
                float brightness=1+(float)FilesUtil.bounded(o.optDouble("brightness",0),-100,100)/100f,contrast=(float)FilesUtil.bounded(o.optDouble("contrast",100),50,150)/100f,saturation=(float)FilesUtil.bounded(o.optDouble("saturation",100),0,200)/100f;
                ColorMatrix matrix=new ColorMatrix();matrix.setSaturation(saturation);ColorMatrix scale=new ColorMatrix(new float[]{brightness,0,0,0,0,0,brightness,0,0,0,0,0,brightness,0,0,0,0,0,1,0});matrix.postConcat(scale);float shift=127.5f*(1-contrast);matrix.postConcat(new ColorMatrix(new float[]{contrast,0,0,0,shift,0,contrast,0,0,shift,0,0,contrast,0,shift,0,0,0,1,0}));
                Bitmap next=Bitmap.createBitmap(b.getWidth(),b.getHeight(),Bitmap.Config.ARGB_8888);Paint p=new Paint(Paint.FILTER_BITMAP_FLAG);p.setColorFilter(new ColorMatrixColorFilter(matrix));new Canvas(next).drawBitmap(b,0,0,p);b.recycle();b=next;
            }else if(tool.equals("flip")){Matrix m=new Matrix();boolean horizontal=o.optString("flipAxis","horizontal").equals("horizontal");m.setScale(horizontal?-1:1,horizontal?1:-1);Bitmap next=Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);FilesUtil.replace(b,next);b=next;
            }else if(tool.equals("crop")) {
                JSONObject r=o.getJSONObject("crop");double x=FilesUtil.bounded(r.getDouble("x"),0,.999),y=FilesUtil.bounded(r.getDouble("y"),0,.999);
                double w=FilesUtil.bounded(r.getDouble("w"),.001,1-x),h=FilesUtil.bounded(r.getDouble("h"),.001,1-y);
                int left=(int)(x*b.getWidth()),top=(int)(y*b.getHeight());Bitmap next=Bitmap.createBitmap(b,left,top,Math.max(1,Math.min(b.getWidth()-left,(int)(w*b.getWidth()))),Math.max(1,Math.min(b.getHeight()-top,(int)(h*b.getHeight()))));FilesUtil.replace(b,next);b=next;
            } else if(tool.equals("resize")) {
                int width=(int)FilesUtil.bounded(o.getDouble("width"),32,4096);int height=Math.max(1,(int)((double)width*b.getHeight()/b.getWidth()));
                if(height>4096){width=Math.max(1,width*4096/height);height=4096;}
                Bitmap next=Bitmap.createScaledBitmap(b,width,height,true);FilesUtil.replace(b,next);b=next;
            } else if(tool.equals("rotate")) {
                int angle=o.getInt("angle");if(angle!=90&&angle!=180&&angle!=270)throw new IllegalArgumentException("زاوية غير صالحة");
                Matrix m=new Matrix();m.postRotate(angle);Bitmap next=Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);FilesUtil.replace(b,next);b=next;
            } else if(tool.equals("background")) {
                float padding=(float)FilesUtil.bounded(o.optDouble("padding",10),0,30)/100f;int pad=Math.round(Math.max(b.getWidth(),b.getHeight())*padding);
                int width=b.getWidth()+2*pad,height=b.getHeight()+2*pad;float factor=Math.min(1f,4096f/Math.max(width,height));width=Math.max(1,Math.round(width*factor));height=Math.max(1,Math.round(height*factor));
                Bitmap next=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas cv=new Canvas(next);cv.drawColor(Color.parseColor(o.optString("color","#eadcc7")));
                Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);RectF dst=new RectF(pad*factor,pad*factor,(b.getWidth()+pad)*factor,(b.getHeight()+pad)*factor);
                float radius=(float)FilesUtil.bounded(o.optDouble("radius",4),0,25)/100f*Math.min(dst.width(),dst.height());Path path=new Path();path.addRoundRect(dst,radius,radius,Path.Direction.CW);cv.save();cv.clipPath(path);cv.drawBitmap(b,null,dst,p);cv.restore();b.recycle();b=next;
            }
            if(!format.matches("jpg|png|webp|pdf"))throw new IllegalArgumentException("صيغة غير مدعومة");
            destination=FilesUtil.output(c,format);result=new File(destination.getPath()+".partial");
            if(format.equals("pdf")){
                PdfDocument doc=new PdfDocument();try {PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(b.getWidth(),b.getHeight(),1).create());page.getCanvas().drawColor(Color.WHITE);page.getCanvas().drawBitmap(b,0,0,new Paint(Paint.FILTER_BITMAP_FLAG));doc.finishPage(page);try(OutputStream out=new FileOutputStream(result)){doc.writeTo(out);}}finally{doc.close();}
            }else{writeImage(b,result,format,(int)FilesUtil.bounded(o.optInt("quality",92),10,100));}
            if(result.length()==0||!result.renameTo(destination))throw new IOException("تعذر حفظ الصورة");
            return destination;
        }catch(Exception|OutOfMemoryError e){if(result!=null)result.delete();throw e;}finally{b.recycle();}
    }
    @SuppressWarnings("deprecation")
    static void writeImage(Bitmap b,File output,String format,int quality)throws IOException {
        Bitmap use=b;
        if(format.equals("jpg")&&b.hasAlpha()){use=Bitmap.createBitmap(b.getWidth(),b.getHeight(),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(use);c.drawColor(Color.WHITE);c.drawBitmap(b,0,0,null);}
        Bitmap.CompressFormat cf=format.equals("png")?Bitmap.CompressFormat.PNG:format.equals("webp")?(android.os.Build.VERSION.SDK_INT>=30?Bitmap.CompressFormat.WEBP_LOSSY:Bitmap.CompressFormat.WEBP):Bitmap.CompressFormat.JPEG;
        try(OutputStream out=new FileOutputStream(output)){if(!use.compress(cf,quality,out))throw new IOException("تعذر ترميز الصورة");}finally{if(use!=b)use.recycle();}
    }
}
