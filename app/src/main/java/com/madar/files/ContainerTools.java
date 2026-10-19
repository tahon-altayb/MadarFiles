package com.madar.files;
import android.content.Context;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
final class ContainerTools {
 static{System.loadLibrary("madar_audio");}
 private static native void remux(String input,String output,String format,AtomicBoolean cancel);
 static File convert(Context c,File input,String format,AtomicBoolean cancel)throws Exception{if(!format.matches("mkv|mov|webm|mp4"))throw new IOException("حاوية غير مدعومة");File out=FilesUtil.output(c,format);try{remux(input.getPath(),out.getPath(),format.equals("mkv")?"matroska":format,cancel);MusicSeparator.check(cancel);return out;}catch(Exception e){out.delete();MusicSeparator.check(cancel);throw new IOException("ترميزات المصدر غير متوافقة مع الحاوية المختارة. جرّب MKV أو MP4. "+e.getMessage(),e);}}
}
