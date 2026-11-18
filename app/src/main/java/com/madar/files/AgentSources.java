package com.madar.files;
import android.text.Html;
import okhttp3.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

/** Read public HTML only. A transport success never establishes business facts. */
final class AgentSources {
 static List<InetAddress> addresses(String host)throws UnknownHostException{
  List<InetAddress> values=Arrays.asList(InetAddress.getAllByName(host));
  for(InetAddress a:values){byte[] b=a.getAddress();if(a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||a.isMulticastAddress()||b.length==16&&(b[0]&0xfe)==0xfc)throw new UnknownHostException("Public source required");}return values;
 }
 static HttpUrl url(String value)throws IOException{HttpUrl u=HttpUrl.parse(value);if(u==null||!(u.scheme().equals("https")||u.scheme().equals("http"))||!u.username().isEmpty()||!u.password().isEmpty()||u.port()!=(u.isHttps()?443:80))throw new IOException("SOURCE_URL");addresses(u.host());return u;}
 static String text(String html){String clean=html.replaceAll("(?is)<(script|style|noscript|svg)\\b[^>]*>.*?</\\1\\s*>"," ");return Html.fromHtml(clean,Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+"," ").trim();}
 static JSONArray read(String request,AtomicBoolean cancel)throws Exception{
  JSONArray out=new JSONArray();Matcher links=Pattern.compile("https?://[^\\s<>\\\"،]+",Pattern.CASE_INSENSITIVE).matcher(request);Set<String> seen=new LinkedHashSet<>();
  OkHttpClient client=new OkHttpClient.Builder().dns(AgentSources::addresses).connectTimeout(5,TimeUnit.SECONDS).readTimeout(6,TimeUnit.SECONDS).callTimeout(8,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
  try{while(links.find()&&seen.size()<2){String raw=links.group().replaceAll("[).,!؟؛]+$","");if(!seen.add(raw))continue;JSONObject item=new JSONObject().put("url",raw).put("status","unavailable").put("text","");
   try{HttpUrl target=url(raw);for(int hop=0;hop<4;hop++){ColabProbe.check(cancel);try(Response response=client.newCall(new Request.Builder().url(target).header("User-Agent","MadarFiles public page reader").build()).execute()){
    ColabProbe.check(cancel);if(response.isRedirect()){if(hop==3)throw new IOException("SOURCE_REDIRECT");String location=response.header("Location");HttpUrl next=location==null?null:target.resolve(location);if(next==null)throw new IOException("SOURCE_REDIRECT");target=url(next.toString());continue;}
    if(!response.isSuccessful()||response.body()==null)break;String type=response.header("Content-Type","").toLowerCase(Locale.ROOT);if(!(type.startsWith("text/html")||type.startsWith("text/plain")))break;
    String value;try(InputStream in=response.body().byteStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){ColabProbe.check(cancel);if(bytes.size()+n>512000)throw new IOException("SOURCE_SIZE");bytes.write(buffer,0,n);}value=text(bytes.toString("UTF-8"));}
    String host=target.host();boolean blocked=host.equals("accounts.google.com")||host.equals("consent.google.com")||Pattern.compile("enable javascript|please enable js|verify you are human|unusual traffic|access denied",Pattern.CASE_INSENSITIVE).matcher(value).find();
    if(!blocked&&value.length()>=60)item.put("url",target.toString()).put("status","partial_text").put("text",value.substring(0,Math.min(6500,value.length())));break;
   }}}
   catch(java.util.concurrent.CancellationException stopped){throw stopped;}catch(Exception unavailable){}out.put(item);
  }return out;}finally{client.dispatcher().cancelAll();client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
 }
}
