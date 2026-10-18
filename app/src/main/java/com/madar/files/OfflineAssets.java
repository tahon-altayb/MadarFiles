package com.madar.files;
import android.content.Context;
import java.io.*;
final class OfflineAssets {
 static synchronized File file(Context c,String name)throws IOException{
  File dir=new File(c.getFilesDir(),"offline-models");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("تعذر تجهيز ملفات المعالجة");File target=new File(dir,name);
  if(target.isFile()&&target.length()>1000)return target;
  File part=new File(dir,name+".partial");try(InputStream in=c.getAssets().open("models/"+name);OutputStream out=new FileOutputStream(part)){FilesUtil.copy(in,out);}catch(Exception e){part.delete();throw e;}
  if(!part.renameTo(target))throw new IOException("تعذر حفظ نموذج المعالجة");return target;
 }
}
