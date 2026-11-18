package com.madar.files;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.util.*;

/** Only explicit media URLs and bounded, syntactically valid request headers. */
final class ExternalMedia {
 static boolean network(Uri uri){return uri!=null&&("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()));}
 static boolean supported(Uri uri){return uri!=null&&("content".equals(uri.getScheme())||"file".equals(uri.getScheme())||(network(uri)&&uri.getHost()!=null&&!uri.getHost().isEmpty()));}
 static Map<String,String> headers(Intent intent){
  Map<String,String> out=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);Uri uri=intent.getData();if(uri!=null){String rawUri=uri.toString();int pipe=rawUri.indexOf('|');if(pipe>=0)parsePairs(out,rawUri.substring(pipe+1));String fragment=uri.getEncodedFragment();if(fragment!=null)parsePairs(out,fragment);}
  for(String[] alias:new String[][]{{"User-Agent","User-Agent","user_agent","user-agent"},{"Referer","Referer","referer","referrer"},{"Cookie","Cookie","cookie"},{"Origin","Origin","origin"}})for(int i=1;i<alias.length;i++)put(out,alias[0],intent.getStringExtra(alias[i]));
  Bundle bundle=intent.getBundleExtra("android.media.intent.extra.HTTP_HEADERS");if(bundle!=null)for(String key:bundle.keySet())put(out,key,bundle.get(key));
  Bundle extras=intent.getExtras();Object raw=extras==null?null:extras.get("headers");
  if(raw instanceof String[]){String[] pairs=(String[])raw;if(pairs.length<=64)for(int i=0;i+1<pairs.length;i+=2)put(out,pairs[i],pairs[i+1]);}
  if(raw instanceof Bundle)for(String key:((Bundle)raw).keySet())put(out,key,((Bundle)raw).get(key));
  if(raw instanceof Map&&((Map<?,?>)raw).size()<=32)for(Object key:((Map<?,?>)raw).keySet())if(key instanceof String)put(out,(String)key,((Map<?,?>)raw).get(key));
  // Anime Slayer 1.5.10 uses a JSON array of JsHeader(name,value), while
  // MX-compatible callers commonly send an alternating String array.
  if(raw instanceof String&&((String)raw).length()<=65536)try{String json=((String)raw).trim();if(json.startsWith("[")){org.json.JSONArray array=new org.json.JSONArray(json);for(int i=0;i<Math.min(32,array.length());i++){org.json.JSONObject header=array.optJSONObject(i);if(header!=null)put(out,header.optString("name",header.optString("key","")),header.opt("value"));}}else{org.json.JSONObject object=new org.json.JSONObject(json);java.util.Iterator<String> keys=object.keys();while(keys.hasNext()&&out.size()<32){String key=keys.next();put(out,key,object.opt(key));}}}catch(org.json.JSONException ignored){}
  return Collections.unmodifiableMap(out);
 }
 static Uri uri(Intent intent){Uri uri=intent.getData();if(uri==null||!network(uri))return uri;String raw=uri.toString();int pipe=raw.indexOf('|');if(pipe>=0)raw=raw.substring(0,pipe);Uri result=Uri.parse(raw);String fragment=result.getEncodedFragment();if(fragment!=null&&(fragment.toLowerCase(Locale.ROOT).contains("user_agent=")||fragment.toLowerCase(Locale.ROOT).contains("referer=")||fragment.toLowerCase(Locale.ROOT).contains("user-agent=")))result=result.buildUpon().fragment(null).build();return result;}
 private static void parsePairs(Map<String,String> out,String value){if(value.length()>65536)return;for(String part:value.split("&",33)){int at=part.indexOf('=');if(at<=0)continue;String key=Uri.decode(part.substring(0,at)),decoded=Uri.decode(part.substring(at+1));if(key.equalsIgnoreCase("user_agent"))key="User-Agent";if(key.equalsIgnoreCase("referrer"))key="Referer";if(Arrays.asList("user-agent","referer","cookie","origin").contains(key.toLowerCase(Locale.ROOT)))put(out,key,decoded);}}
 private static void put(Map<String,String> out,String name,Object value){if(out.size()>=32||name==null||!(value instanceof String))return;String v=(String)value;if(name.length()>128||v.length()>8192||!name.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+")||v.indexOf('\r')>=0||v.indexOf('\n')>=0)return;String key=name.toLowerCase(Locale.ROOT);if(Arrays.asList("host","content-length","range","connection","transfer-encoding").contains(key))return;out.put(name,v);}
 static Bundle bundle(Map<String,String> headers){Bundle out=new Bundle();for(Map.Entry<String,String> h:headers.entrySet())out.putString(h.getKey(),h.getValue());return out;}
 static String mime(Intent intent){String type=intent.getType();if(type!=null&&type.indexOf(';')>=0)type=type.substring(0,type.indexOf(';'));if(type!=null)type=type.toLowerCase(Locale.ROOT);if(type!=null&&(type.indexOf('*')>=0||"application/octet-stream".equals(type)))return null;if("application/x-mpegurl".equals(type)||"audio/mpegurl".equals(type)||"audio/x-mpegurl".equals(type)||"application/vnd.apple.mpegurl".equals(type))return "application/x-mpegURL";return type;}
}
