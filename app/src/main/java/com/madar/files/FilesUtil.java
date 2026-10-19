package com.madar.files;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.util.Locale;
import java.util.UUID;

final class FilesUtil {
    private FilesUtil() {}
    static File directory(Context c) { File d=new File(c.getFilesDir(),"exports"); if(!d.isDirectory()&&!d.mkdirs())throw new IllegalStateException("تعذر فتح مجلد النتائج");return d; }
    static File output(Context c,String ext) { return new File(directory(c),"Madar_"+new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new java.util.Date())+"_"+UUID.randomUUID().toString().substring(0,6)+"."+ext); }
    static File resolve(Context c,String name) throws IOException { File d=directory(c);File f=new File(d,name);if(!f.getCanonicalFile().getParentFile().equals(d.getCanonicalFile())||!f.isFile())throw new IOException("الملف غير موجود");return f; }
    static String mime(File f) { String n=f.getName().toLowerCase(Locale.ROOT);if(n.endsWith(".mkv"))return "video/x-matroska";if(n.endsWith(".mov"))return "video/quicktime";if(n.endsWith(".webm"))return "video/webm";if(n.endsWith(".flac"))return "audio/flac";if(n.endsWith(".ogg"))return "audio/ogg";if(n.endsWith(".mp3"))return "audio/mpeg";if(n.endsWith(".wav"))return "audio/wav";if(n.endsWith(".aac"))return "audio/aac";if(n.endsWith(".srt"))return "application/x-subrip";if(n.endsWith(".txt"))return "text/plain";if(n.endsWith(".docx"))return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";if(n.endsWith(".pdf"))return "application/pdf";if(n.endsWith(".mp4"))return "video/mp4";if(n.endsWith(".m4a"))return "audio/mp4";if(n.endsWith(".png"))return "image/png";if(n.endsWith(".webp"))return "image/webp";return "image/jpeg"; }
    static JSONObject info(File f) throws Exception { return new JSONObject().put("name",f.getName()).put("size",f.length()).put("mime",mime(f)).put("modified",f.lastModified()); }
    static Bitmap image(File file,int maxEdge) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file),(decoder,info,source)->{
            int w=info.getSize().getWidth(),h=info.getSize().getHeight();
            float scale=Math.min(1f,(float)maxEdge/Math.max(w,h));
            decoder.setTargetSize(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }
    static String thumbnail(Bitmap b) { ByteArrayOutputStream out=new ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG,80,out);return "data:image/jpeg;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP); }
    static void copy(InputStream in,OutputStream out) throws IOException { byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n); }
    static double bounded(double v,double min,double max){if(!Double.isFinite(v))throw new IllegalArgumentException("قيمة غير صالحة");return Math.max(min,Math.min(max,v));}
    static void replace(Bitmap old,Bitmap next){if(old!=next)old.recycle();}
}
