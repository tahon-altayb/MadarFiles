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
  Map<String,String> out=new LinkedHashMap<>();Bundle bundle=intent.getBundleExtra("android.media.intent.extra.HTTP_HEADERS");if(bundle!=null)for(String key:bundle.keySet())put(out,key,bundle.get(key));
  Bundle extras=intent.getExtras();Object raw=extras==null?null:extras.get("headers");
  if(raw instanceof String[]){String[] pairs=(String[])raw;if(pairs.length<=64)for(int i=0;i+1<pairs.length;i+=2)put(out,pairs[i],pairs[i+1]);}
  // Anime Slayer 1.5.10 uses a JSON array of JsHeader(name,value), while
  // MX-compatible callers commonly send an alternating String array.
  if(raw instanceof String&&((String)raw).length()<=65536)try{String json=((String)raw).trim();if(json.startsWith("[")){org.json.JSONArray array=new org.json.JSONArray(json);for(int i=0;i<Math.min(32,array.length());i++){org.json.JSONObject header=array.optJSONObject(i);if(header!=null)put(out,header.optString("name",header.optString("key","")),header.opt("value"));}}else{org.json.JSONObject object=new org.json.JSONObject(json);java.util.Iterator<String> keys=object.keys();while(keys.hasNext()&&out.size()<32){String key=keys.next();put(out,key,object.opt(key));}}}catch(org.json.JSONException ignored){}
  return Collections.unmodifiableMap(out);
 }
 private static void put(Map<String,String> out,String name,Object value){if(out.size()>=32||name==null||!(value instanceof String))return;String v=(String)value;if(name.length()>128||v.length()>8192||!name.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+")||v.indexOf('\r')>=0||v.indexOf('\n')>=0)return;String key=name.toLowerCase(Locale.ROOT);if(Arrays.asList("host","content-length","range","connection","transfer-encoding").contains(key))return;out.put(name,v);}
 static Bundle bundle(Map<String,String> headers){Bundle out=new Bundle();for(Map.Entry<String,String> h:headers.entrySet())out.putString(h.getKey(),h.getValue());return out;}
 static String mime(Intent intent){String type=intent.getType();if(type!=null&&type.indexOf(';')>=0)type=type.substring(0,type.indexOf(';'));if(type!=null)type=type.toLowerCase(Locale.ROOT);if(type!=null&&(type.indexOf('*')>=0||"application/octet-stream".equals(type)))return null;if("application/x-mpegurl".equals(type)||"audio/mpegurl".equals(type)||"audio/x-mpegurl".equals(type)||"application/vnd.apple.mpegurl".equals(type))return "application/x-mpegURL";return type;}
}
