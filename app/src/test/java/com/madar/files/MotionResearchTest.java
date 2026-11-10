package com.madar.files;

import static org.junit.Assert.*;
import org.junit.Test;

/** What a web search found reaches the owner only when it could stand in an advert. */
public class MotionResearchTest {
 private static final String OWNER="شاهي جمر الطيب في الطائف شارع الجال، شغالين على مدار الساعة";
 @Test public void advertFactsStay(){
  for(String fact:new String[]{"تقييم 4.8 من 46 مراجعة على الخرائط","يقدّم شاهي على الجمر وكرك وقهوة عربية","٤٫٨ من ٥ نجوم","توصيل أكثر من 100 مطعم في الرياض","متوفر على آيفون وأندرويد","تقييم 4.5 من 50 مراجعة","أكثر من 1,200 تقييم بخمس نجوم","5 نجوم على خرائط قوقل"})assertTrue(fact,MotionResearch.keepFact(fact,OWNER));
 }
 @Test public void contactsOfficesAndWeakRatingsGo(){
  for(String fact:new String[]{"رقم الجوال 0551234567","للتواصل +966 55 123 4567","البريد info@jahez.net","الموقع jahez.net","https://jahez.net","المقر الرئيسي في الرياض حي الملقا","شركة جاهز الدولية المحدودة","ص.ب 1234 الرياض","المطوّر: Jahez International","تقييم 3.6 من 5","٣٫٩ نجوم من 200 تقييم","تقييم 3 من 5 نجوم","Open 24 hours",""})assertFalse(fact,MotionResearch.keepFact(fact,OWNER));
 }
 @Test public void whatTheOwnerAlreadyWroteIsNotOfferedAgain(){
  assertFalse(MotionResearch.keepFact("شغالين على مدار الساعة",OWNER));
  assertFalse(MotionResearch.keepFact("الطائف، شارع الجال",OWNER));
 }
}
