package com.madar.files;

import android.content.Context;
import androidx.media3.common.MimeTypes;
import java.io.*;
import java.util.Locale;
import okhttp3.*;

/** Download a direct media file alongside separation, then use the same bytes for both tracks. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class WatchSourceDownload implements AutoCloseable {
 private static final long MAX_BYTES=1024L*1024*1024, FREE_RESERVE=128L*1024*1024;
 private final File file;
 private final Call call;
 private final ColabDiagnostics diagnostic;
 private volatile boolean ready,closed;

 static WatchSourceDownload start(Context context,MediaHttp.Session source,String mime,ColabDiagnostics diagnostic){
  String path=source.uri.getPath();
  path=path==null?"":path.toLowerCase(Locale.ROOT);
  if(MimeTypes.APPLICATION_M3U8.equals(mime)||MimeTypes.APPLICATION_MPD.equals(mime)
     ||path.endsWith(".m3u8")||path.endsWith(".mpd"))return null;
  try{return new WatchSourceDownload(context,source,diagnostic);}
  catch(IOException|IllegalArgumentException unavailable){if(diagnostic!=null)diagnostic.record("WATCH_SOURCE_FALLBACK");return null;}
 }

 private WatchSourceDownload(Context context,MediaHttp.Session source,ColabDiagnostics diagnostic)throws IOException{
  Request.Builder request=new Request.Builder().url(source.uri.toString());
  source.headersFor(source.uri).forEach((name,value)->{
   if(!name.equalsIgnoreCase("Cookie")&&!name.equalsIgnoreCase("Range")&&!name.equalsIgnoreCase("Accept-Encoding"))request.header(name,value);
  });
  call=source.client.newCall(request.header("Accept-Encoding","identity").build());
  this.diagnostic=diagnostic;
  file=File.createTempFile("madar_watch_source_",".media",context.getCacheDir());
  Thread worker=new Thread(this::download,"madar-watch-source");
  worker.setDaemon(true);
  worker.start();
 }

 private void download(){
  long began=android.os.SystemClock.elapsedRealtime(),count=0;
  try(Response response=call.execute()){
   ResponseBody body=response.body();
   if(response.code()!=200||body==null)return;
   String type=response.header("Content-Type","").toLowerCase(Locale.ROOT);
   if(type.startsWith("text/")||type.contains("mpegurl")||type.contains("dash+xml"))return;
   long length=body.contentLength(),limit=Math.min(MAX_BYTES,file.getUsableSpace()-FREE_RESERVE);
   if(limit<=0||length==0||length>limit)return;
   try(InputStream input=body.byteStream();OutputStream out=new FileOutputStream(file)){
    byte[] bytes=new byte[128*1024];
    for(int n;(n=input.read(bytes))!=-1;){
     if(closed||count+n>limit||file.getUsableSpace()<FREE_RESERVE+n)return;
     out.write(bytes,0,n);
     count+=n;
    }
   }
   if(closed||count<=0||(length>=0&&count!=length))return;
   ready=true;
   if(diagnostic!=null)diagnostic.measure("WATCH_PREFETCH",android.os.SystemClock.elapsedRealtime()-began,count,0);
  }catch(IOException unavailable){
   // The window readers keep using the original source if the background copy fails.
  }finally{
   if(!ready){file.delete();if(!closed&&diagnostic!=null)diagnostic.record("WATCH_SOURCE_FALLBACK");}
   if(closed)file.delete();
  }
 }

 File completed(){return ready?file:null;}

 @Override public void close(){
  closed=true;
  call.cancel();
  file.delete();
 }
}
