package com.madar.files;

import static org.junit.Assert.*;
import org.json.*;
import org.junit.Test;

public class ChosenLogoTest {
 private static final String LOGO="asset_"+"b".repeat(32)+".jpg";
 private static JSONObject brief(boolean withChoice)throws Exception{
  JSONObject b=new JSONObject().put("images",new JSONArray().put("asset_"+"a".repeat(32)+".jpg").put(LOGO));
  if(withChoice)b.put("contract",new JSONObject().put("logoAsset",LOGO).put("logoFirst",true));
  return b;
 }
 private static JSONArray catalog()throws Exception{
  return new JSONArray().put(new JSONObject().put("index",0).put("kind","brand_logo").put("description","واجهة")).put(new JSONObject().put("index",1).put("kind","other").put("description","شعار"));
 }
 @Test public void chatChoiceBecomesTheVerifiedLogo()throws Exception{
  JSONObject contract=new JSONObject().put("opening_logo",true).put("logo_index",-1);JSONArray catalog=catalog();
  AgentExecutive.trustChosenLogo(brief(true),contract,catalog);
  assertEquals(1,contract.getInt("logo_index"));assertEquals("brand_logo",catalog.getJSONObject(1).getString("kind"));assertEquals("other",catalog.getJSONObject(0).getString("kind"));
 }
 @Test public void withoutChoiceNothingChanges()throws Exception{
  JSONObject contract=new JSONObject().put("opening_logo",true).put("logo_index",-1);JSONArray catalog=catalog();
  AgentExecutive.trustChosenLogo(brief(false),contract,catalog);
  assertEquals(-1,contract.getInt("logo_index"));assertEquals("brand_logo",catalog.getJSONObject(0).getString("kind"));
 }
}
