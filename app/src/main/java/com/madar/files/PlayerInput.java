package com.madar.files;

import android.content.*;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** External media plays directly through its read grant; processing takes a private snapshot. */
final class PlayerInput {
 final Uri uri;final File file;final String identity,title,key;final java.util.Map<String,String> headers;final String mime;final MediaHttp.Session http;
 private PlayerInput(Uri uri,File file,String identity,String title,String key,java.util.Map<String,String> headers,String mime){this(uri,file,identity,title,key,headers,mime,new MediaHttp.Session(uri,headers));}
 private PlayerInput(Uri uri,File file,String identity,String title,String key,java.util.Map<String,String> headers,String mime,MediaHttp.Session http){this.uri=uri;this.file=file;this.identity=identity;this.title=title;this.key=key;this.headers=headers;this.mime=mime;this.http=http;}
 PlayerInput resolved(MediaHttp.Probe media){return new PlayerInput(media.uri,null,identity,title,key,media.headers,media.mime);}
 static PlayerInput read(Context c,Intent intent)throws Exception{
  MediaHttp.browserAgent(c);
  PlaybackSession saved=PlaybackSession.current;if(saved!=null&&!saved.closed&&saved.id.equals(intent.getStringExtra("playbackSession")))return saved.input;
  WatchMusicService.Job job=WatchMusicService.current;
  if(job!=null&&job.id.equals(intent.getStringExtra("watchJob")))return new PlayerInput(job.sourceReady?Uri.fromFile(job.source):job.uri,job.sourceReady?job.source:null,job.identity,job.title,job.ownsSource?key(job.identity):"position_"+job.source.getName(),job.headers,job.mime,job.http);
  Uri uri=ExternalMedia.uri(intent);File file=null;String title=intent.getStringExtra("title"),identity,bookmark;
  if(uri!=null){if(!ExternalMedia.supported(uri))throw new IOException("ملف أو رابط فيديو غير صالح");identity=uri.toString();bookmark=key(identity);if(title==null){if(!ExternalMedia.network(uri))try(android.database.Cursor cursor=c.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())title=cursor.getString(0);}catch(Exception ignored){}if(title==null)title=uri.getLastPathSegment();}}
  else{String path=intent.getStringExtra("path");if(path==null)throw new IOException("اختر مقطعًا للمشاهدة");file=new File(path).getCanonicalFile();if(!file.isFile()||!(file.getPath().startsWith(c.getCacheDir().getCanonicalPath()+"/")||file.getPath().startsWith(FilesUtil.directory(c).getCanonicalPath()+"/")||AssistantFiles.owns(c,file)))throw new IOException("ملف غير صالح");uri=Uri.fromFile(file);identity=uri.toString();bookmark="position_"+file.getName();if(title==null)title=file.getName();}
  return new PlayerInput(MediaResolver.secureProvider(uri),file,identity,title==null?"مقطع":title,bookmark,ExternalMedia.headers(intent),ExternalMedia.mime(intent));
 }
 private static String key(String identity)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder("position_");for(byte b:digest)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
