package com.madar.files;

import org.json.*;
import java.io.IOException;
import java.util.*;

/**
 * Validates the chat's motion brief contract before it is stored or sent to Colab.
 * Only known fields survive; every string and list is bounded. The chosen logo
 * attachment is resolved to the project's own copied asset name, never a path.
 */
final class MotionContract {
 private MotionContract(){}
 static String text(JSONObject in,String name,int limit){String v=in.optString(name,"").replaceAll("[\\p{Cntrl}&&[^\\n]]","").trim();return v.length()>limit?v.substring(0,limit):v;}
 static JSONArray texts(JSONObject in,String name,int items,int limit){JSONArray out=new JSONArray(),src=in.optJSONArray(name);if(src==null)return out;Set<String> seen=new LinkedHashSet<>();for(int i=0;i<src.length()&&seen.size()<items;i++){String v=src.optString(i,"").replaceAll("\\p{Cntrl}"," ").trim();if(v.isEmpty())continue;if(v.length()>limit)v=v.substring(0,limit);seen.add(v);}for(String v:seen)out.put(v);return out;}
 static JSONObject sanitize(JSONObject in,JSONObject referenceMap)throws Exception{
  if(in==null)return null;if(in.toString().length()>12000)throw new IOException("تفاصيل طلب الموشن أطول من المسموح");
  JSONObject out=new JSONObject().put("version",1);
  if(in.has("durationSec")&&!in.isNull("durationSec")){double d=in.getDouble("durationSec");if(!(d>=6&&d<=180))throw new IOException("مدة الموشن غير صالحة");out.put("durationSec",d);}
  out.put("durationSource","user".equals(in.optString("durationSource"))?"user":"default");
  String aspect=in.optString("aspect","");if(Arrays.asList("9:16","16:9","1:1").contains(aspect))out.put("aspect",aspect);
  for(String flag:new String[]{"logoFirst","logoLast","visitBranch","noWebsite","askWhenMissing","musicRequested"})out.put(flag,in.optBoolean(flag,false));
  out.put("address",text(in,"address",160)).put("phone",text(in,"phone",20)).put("businessName",text(in,"businessName",60));
  out.put("required",texts(in,"required",8,90)).put("forbidden",texts(in,"forbidden",60,80)).put("notes",texts(in,"notes",12,300));
  JSONArray corrections=new JSONArray(),src=in.optJSONArray("corrections");if(src!=null)for(int i=0;i<src.length()&&corrections.length()<10;i++){JSONObject c=src.optJSONObject(i);if(c==null)continue;JSONObject z=new JSONObject().put("text",text(c,"text",300)).put("subject",text(c,"subject",40)).put("wrong",text(c,"wrong",40));if(!z.getString("text").isEmpty())corrections.put(z);}
  out.put("corrections",corrections);
  int scenes=in.optInt("sceneCount",0);if(scenes<3||scenes>12)throw new IOException("عدد مشاهد الموشن غير صالح");out.put("sceneCount",scenes);
  double seconds=in.optDouble("sceneSeconds",0);if(!(seconds>=1&&seconds<=60))throw new IOException("مدة المشهد غير صالحة");out.put("sceneSeconds",seconds);
  String logo=in.optString("logoAttachment","");if(!logo.isEmpty()&&referenceMap!=null&&referenceMap.has(logo)){String asset=referenceMap.getString(logo);if(!asset.matches("asset_[a-f0-9]{32}\\.jpg"))throw new IOException("مرجع الشعار غير صالح");out.put("logoAsset",asset);}
  return out;
 }
}
