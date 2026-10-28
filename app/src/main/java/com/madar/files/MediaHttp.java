package com.madar.files;

import android.content.Context;
import android.net.Uri;
import androidx.media3.datasource.*;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Browser-compatible media requests. Normal TLS verification and Android HTTP policy apply. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class MediaHttp {
 // This browser UA is also used by ASD Player 4.3.26. It is a request format,
 // never an application/package identity or a substitute for authorization.
 static volatile String USER_AGENT="Mozilla/5.0 (Linux; Android 4.4.2; Nexus 4 Build/KOT49H) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/34.0.1847.114 Mobile Safari/537.36";
 static void browserAgent(Context c){try{USER_AGENT=android.webkit.WebSettings.getDefaultUserAgent(c);}catch(Exception ignored){}}
 static final class Jar implements CookieJar {
  private final List<Cookie> cookies=new ArrayList<>();
  @Override public synchronized void saveFromResponse(HttpUrl url,List<Cookie> incoming){for(Cookie value:incoming){cookies.removeIf(c->c.name().equals(value.name())&&c.domain().equals(value.domain())&&c.path().equals(value.path()));if(value.expiresAt()>System.currentTimeMillis())cookies.add(value);}while(cookies.size()>200)cookies.remove(0);}
  @Override public synchronized List<Cookie> loadForRequest(HttpUrl url){cookies.removeIf(c->c.expiresAt()<=System.currentTimeMillis());List<Cookie> result=new ArrayList<>();for(Cookie cookie:cookies)if(cookie.matches(url))result.add(cookie);return result;}
 }
 static final class Session {
  final Uri uri;final Map<String,String> headers;final Jar jar=new Jar();final OkHttpClient client;private final HttpUrl origin;
  Session(Uri uri,Map<String,String> input){this.uri=uri;origin=HttpUrl.parse(uri.toString());Map<String,String> copy=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);copy.putAll(input);copy.putIfAbsent("User-Agent",USER_AGENT);
   String cookie=copy.remove("Cookie");if(cookie!=null&&origin!=null)for(String part:cookie.split(";")){Cookie value=Cookie.parse(origin,part.trim()+"; Path=/"+(origin.isHttps()?"; Secure":""));if(value!=null)jar.saveFromResponse(origin,Collections.singletonList(value));}
   headers=Collections.unmodifiableMap(copy);
   client=new OkHttpClient.Builder().protocols(java.util.Collections.singletonList(Protocol.HTTP_1_1)).cookieJar(jar).connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).followRedirects(true).followSslRedirects(true).addNetworkInterceptor(chain->{Request request=chain.request();if(origin!=null&&!sameOrigin(origin,request.url()))request=request.newBuilder().removeHeader("Authorization").removeHeader("Proxy-Authorization").build();return chain.proceed(request);}).build();
  }
  DataSource.Factory factory(Context c){return new DefaultDataSource.Factory(c,new OkHttpDataSource.Factory(client).setUserAgent(headers.get("User-Agent")).setDefaultRequestProperties(headers));}
  Map<String,String> headersFor(Uri target){Map<String,String> result=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);result.putAll(headers);HttpUrl url=HttpUrl.parse(target.toString());if(url!=null){if(origin!=null&&!sameOrigin(origin,url)){result.remove("Authorization");result.remove("Proxy-Authorization");}StringBuilder value=new StringBuilder();for(Cookie cookie:jar.loadForRequest(url)){if(value.length()>0)value.append("; ");value.append(cookie.name()).append('=').append(cookie.value());}if(value.length()>0)result.put("Cookie",value.toString());}return Collections.unmodifiableMap(result);}
 }
 private static boolean sameOrigin(HttpUrl a,HttpUrl b){return a.scheme().equals(b.scheme())&&a.host().equals(b.host())&&a.port()==b.port();}
 static final class Probe {final Uri uri;final String mime;final boolean html;final Map<String,String> headers;Probe(Uri u,String m,boolean h,Map<String,String> x){uri=u;mime=m;html=h;headers=x;}}
 static Probe probe(Session session,Uri target,Map<String,String> extra)throws IOException{
  Request.Builder request=new Request.Builder().url(target.toString());for(Map.Entry<String,String> header:extra.entrySet())if(!header.getKey().equalsIgnoreCase("Cookie"))request.header(header.getKey(),header.getValue());request.header("Range","bytes=0-1023");
  try(Response response=session.client.newBuilder().callTimeout(12,TimeUnit.SECONDS).build().newCall(request.build()).execute()){
   if(!response.isSuccessful())throw new IOException("HTTP "+response.code());if(response.body()==null)throw new IOException("استجابة وسائط فارغة");String type=response.header("Content-Type","").split(";",2)[0].trim().toLowerCase(Locale.ROOT);byte[] data=new byte[1024];int size=0;InputStream in=response.body().byteStream();while(size<data.length){int n=in.read(data,size,data.length-size);if(n<0)break;size+=n;}String text=new String(data,0,size,java.nio.charset.StandardCharsets.UTF_8).trim();
   String mime=null;if(type.contains("mpegurl")||text.startsWith("#EXTM3U"))mime="application/x-mpegURL";else if(type.equals("application/dash+xml")||text.contains("<MPD"))mime="application/dash+xml";else if(type.startsWith("video/")||type.startsWith("audio/"))mime=type;else if(size>8&&data[4]=='f'&&data[5]=='t'&&data[6]=='y'&&data[7]=='p')mime="video/mp4";else if(size>4&&(data[0]&255)==0x1a&&(data[1]&255)==0x45&&(data[2]&255)==0xdf&&(data[3]&255)==0xa3)mime="video/x-matroska";
   boolean html=type.equals("text/html")||text.toLowerCase(Locale.ROOT).startsWith("<!doctype html")||text.toLowerCase(Locale.ROOT).startsWith("<html");Uri resolved=Uri.parse(response.request().url().toString());return new Probe(resolved,mime,html,session.headersFor(resolved));
  }
 }
 static boolean looksPlayable(Uri uri){String value=(uri.getPath()==null?"":uri.getPath()).toLowerCase(Locale.ROOT);return value.matches(".*\\.(mp4|m4v|mkv|webm|mov|m3u8|mpd|ts|mp3|m4a|aac|flac|ogg|opus)$")||value.endsWith("/get_video")||value.endsWith("/videoplayback");}
}
