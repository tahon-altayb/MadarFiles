package com.madar.files;

import static org.junit.Assert.*;
import org.junit.Test;

/** Google refusing the account says what to do: a family tablet showed only «HTTP_403» after 82 s of decoding. */
public class ColabRefusalTest {
 @Test public void aRefusedAccountIsExplainedWithTheCodeFirst(){
  String known=new ColabRuntime.HttpFailure(403,null,"USER_PROJECT_DENIED").getMessage();
  assertTrue(known,known.startsWith("Colab · HTTP_403 · GOOGLE_USER_PROJECT_DENIED\n"));assertTrue(known,known.contains("حساب المشروع"));
  String plain=new ColabRuntime.HttpFailure(403,null,"").getMessage();
  assertTrue(plain,plain.startsWith("Colab · HTTP_403\n"));assertTrue(plain,plain.contains("إعدادات Colab")&&plain.contains("معرّف المشروع"));
 }
 @Test public void otherFailuresKeepTheirBareCode(){
  assertEquals("Colab · HTTP_403",new ColabRuntime.HttpFailure(403).getMessage());assertEquals("Colab · HTTP_500",new ColabRuntime.HttpFailure(500,null,"").getMessage());
  assertEquals("Colab · HTTP_401",new ColabRuntime.HttpFailure(401,null).getMessage());assertEquals(403,new ColabRuntime.HttpFailure(403,null,"").status);
 }
 @Test public void theReportKeepsOnlyKnownReasonWords(){
  for(String code:new String[]{"HTTP_403","GOOGLE_USER_PROJECT_DENIED","GOOGLE_SERVICE_DISABLED","DECODE_STOPPED_EARLY"})assertTrue(code,ColabDiagnostics.safe(code));
  for(String code:new String[]{"GOOGLE_SECRET","API","GOOGLE_","TOKEN"})assertFalse(code,ColabDiagnostics.safe(code));
  assertEquals("",ColabProbe.reasonAdvice("SOMETHING_NEW"));assertEquals("",ColabProbe.reasonAdvice(null));assertFalse(ColabProbe.reasonAdvice("SERVICE_DISABLED").isEmpty());
 }
}
