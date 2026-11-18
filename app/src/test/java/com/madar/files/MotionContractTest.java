package com.madar.files;

import static org.junit.Assert.*;
import org.json.*;
import org.junit.Test;

public class MotionContractTest {
 private static JSONObject contract()throws Exception{
  return new JSONObject().put("version",1).put("durationSec",50).put("durationSource","user").put("aspect","9:16")
   .put("logoFirst",true).put("noWebsite",true).put("address","الرياض – حي النرجس")
   .put("required",new JSONArray().put("زورونا في فرعنا")).put("forbidden",new JSONArray().put("نكهه الورد").put("موقع الكتروني"))
   .put("corrections",new JSONArray().put(new JSONObject().put("text","الورد ليس نكهه").put("subject","الورد").put("wrong","نكهه")))
   .put("sceneCount",10).put("sceneSeconds",5).put("logoAttachment","22222222-2222-2222-2222-222222222222").put("unknown","dropped");
 }
 @Test public void keepsKnownFieldsAndResolvesLogo()throws Exception{
  String asset="asset_"+"b".repeat(32)+".jpg";
  JSONObject out=MotionContract.sanitize(contract(),new JSONObject().put("22222222-2222-2222-2222-222222222222",asset));
  assertEquals(50,out.getDouble("durationSec"),0);assertEquals("9:16",out.getString("aspect"));assertTrue(out.getBoolean("logoFirst"));
  assertEquals(asset,out.getString("logoAsset"));assertFalse(out.has("unknown"));assertFalse(out.has("logoAttachment"));
  assertEquals("زورونا في فرعنا",out.getJSONArray("required").getString(0));assertEquals(10,out.getInt("sceneCount"));
 }
 @Test public void logoMustBeAnOwnedReference()throws Exception{
  assertFalse(MotionContract.sanitize(contract(),new JSONObject()).has("logoAsset"));
  try{MotionContract.sanitize(contract(),new JSONObject().put("22222222-2222-2222-2222-222222222222","../secret.jpg"));fail("path accepted");}catch(java.io.IOException expected){}
 }
 @Test public void rejectsOutOfRangePlan()throws Exception{
  try{MotionContract.sanitize(contract().put("sceneCount",40),null);fail("scene count accepted");}catch(java.io.IOException expected){}
  try{MotionContract.sanitize(contract().put("durationSec",2000),null);fail("duration accepted");}catch(java.io.IOException expected){}
  assertNull(MotionContract.sanitize(null,null));
 }
 @Test public void boundsListsAndText()throws Exception{
  JSONArray many=new JSONArray();for(int i=0;i<100;i++)many.put("عبارة "+i);
  JSONObject out=MotionContract.sanitize(contract().put("forbidden",many).put("address","أ".repeat(500)),null);
  assertEquals(60,out.getJSONArray("forbidden").length());assertEquals(160,out.getString("address").length());
 }
}
