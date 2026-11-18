package com.madar.files;
import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.*;

/** Only app-owned completed exports. Original imports and device documents are never targets. */
final class RecentFiles {
 static final long RETENTION=7L*24*60*60*1000;
 static File trash(Context c){File f=new File(c.getFilesDir(),"recent-trash");if(!f.isDirectory()&&!f.mkdirs())throw new IllegalStateException("تعذر فتح المحذوفات");return f;}
 static List<File> list(Context c){File[] files=FilesUtil.directory(c).listFiles(f->f.isFile()&&!f.getName().endsWith(".partial"));List<File> out=new ArrayList<>();if(files!=null)for(File f:files)try{if(f.getCanonicalFile().getParentFile().equals(FilesUtil.directory(c).getCanonicalFile()))out.add(f);}catch(IOException ignored){}out.sort((a,b)->Long.compare(b.lastModified(),a.lastModified()));return out;}
 static JSONArray select(Context c,String name,String scope)throws Exception{
  JSONArray names=new JSONArray();if(name!=null&&!name.isEmpty()){names.put(FilesUtil.resolve(c,name).getName());return names;}
  if(!scope.equals("all")&&!scope.equals("latest"))throw new IOException("اختر النتائج المطلوب حذفها");
  for(File f:list(c)){names.put(f.getName());if(scope.equals("latest"))break;}return names;
 }
 static synchronized int remove(Context c,JSONArray names)throws Exception{
  purgeExpired(c);if(names.length()<1||names.length()>1000)throw new IOException("اختر من نتيجة إلى 1000 نتيجة");
  Set<String> unique=new HashSet<>();List<File> files=new ArrayList<>();for(int i=0;i<names.length();i++){String name=names.getString(i);if(!unique.add(name))throw new IOException("نتيجة مكررة");files.add(FilesUtil.resolve(c,name));}
  File batch=new File(trash(c),System.currentTimeMillis()+"_"+UUID.randomUUID());if(!batch.mkdir())throw new IOException("تعذر تجهيز الحذف");
  List<File> moved=new ArrayList<>();try{for(File f:files){File dest=new File(batch,f.getName());if(!f.renameTo(dest))throw new IOException("تعذر نقل النتيجة إلى المحذوفات");moved.add(dest);}return moved.size();}
  catch(Exception e){for(File f:moved)f.renameTo(new File(FilesUtil.directory(c),f.getName()));throw e;}finally{if(moved.isEmpty())batch.delete();}
 }
 static synchronized int restore(Context c)throws Exception{
  purgeExpired(c);File[] batches=trash(c).listFiles(File::isDirectory);if(batches==null||batches.length==0)return 0;Arrays.sort(batches,Comparator.comparing(File::getName).reversed());int restored=0;
  for(File file:Objects.requireNonNull(batches[0].listFiles())){
   File target=new File(FilesUtil.directory(c),file.getName());if(!file.getCanonicalFile().getParentFile().equals(batches[0].getCanonicalFile())||!target.getCanonicalFile().getParentFile().equals(FilesUtil.directory(c).getCanonicalFile())||target.exists())continue;
   if(!file.renameTo(target))throw new IOException("تعذر استرجاع بعض النتائج");restored++;
  }batches[0].delete();return restored;
 }
 static synchronized int trashCount(Context c)throws Exception{purgeExpired(c);int n=0;File[] batches=trash(c).listFiles(File::isDirectory);if(batches!=null)for(File batch:batches){File[] files=batch.listFiles(File::isFile);if(files!=null)n+=files.length;}return n;}
 static void purgeExpired(Context c)throws Exception{
  File[] batches=trash(c).listFiles(File::isDirectory);if(batches==null)return;for(File batch:batches){String name=batch.getName();if(!name.matches("[0-9]+_[a-f0-9-]{36}"))continue;long created=Long.parseLong(name.substring(0,name.indexOf('_')));if(System.currentTimeMillis()-created<RETENTION)continue;File[] files=batch.listFiles();if(files!=null)for(File f:files)if(f.isFile()&&f.getCanonicalFile().getParentFile().equals(batch.getCanonicalFile()))f.delete();batch.delete();}
 }
}
