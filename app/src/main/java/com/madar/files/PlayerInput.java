package com.madar.files;

import android.content.*;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** External media plays directly through its read grant; processing takes a private snapshot. */
final class PlayerInput {
 final Uri uri;final File file;final String identity,title,key;
 private PlayerInput(Uri uri,File file,String identity,String title,String key){this.uri=uri;this.file=file;this.identity=identity;this.title=title;this.key=key;}
 static PlayerInput read(Context c,Intent intent)throws Exception{
  WatchMusicService.Job job=WatchMusicService.current;
  if(job!=null&&job.id.equals(intent.getStringExtra("watchJob")))return new PlayerInput(job.sourceReady?Uri.fromFile(job.source):job.uri,job.sourceReady?job.source:null,job.identity,job.title,job.ownsSource?key(job.identity):"position_"+job.source.getName());
  Uri uri=intent.getData();File file=null;String title=intent.getStringExtra("title"),identity,bookmark;
  if(uri!=null){if(!("content".equals(uri.getScheme())||"file".equals(uri.getScheme())))throw new IOException("المشغّل يقبل الملفات المحلية فقط");identity=uri.toString();bookmark=key(identity);if(title==null){try(android.database.Cursor cursor=c.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())title=cursor.getString(0);}catch(Exception ignored){}if(title==null)title=uri.getLastPathSegment();}}
  else{String path=intent.getStringExtra("path");if(path==null)throw new IOException("اختر مقطعًا للمشاهدة");file=new File(path).getCanonicalFile();if(!file.isFile()||!(file.getPath().startsWith(c.getCacheDir().getCanonicalPath()+"/")||file.getPath().startsWith(FilesUtil.directory(c).getCanonicalPath()+"/")||AssistantFiles.owns(c,file)))throw new IOException("ملف غير صالح");uri=Uri.fromFile(file);identity=uri.toString();bookmark="position_"+file.getName();if(title==null)title=file.getName();}
  return new PlayerInput(uri,file,identity,title==null?"مقطع":title,bookmark);
 }
 private static String key(String identity)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder("position_");for(byte b:digest)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
