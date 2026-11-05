package com.madar.files;
import org.junit.Test;
import static org.junit.Assert.*;
public class AgentMotionContextTest {
 @Test public void softwareBriefRecognizesItsActualName(){
  assertTrue(AgentMotion.isMadarBrief("أريد موشن لتطبيق «مدار» للملفات"));
  assertTrue(AgentMotion.isMadarBrief("إعلان عن مدار الملفات"));
  assertTrue(AgentMotion.isMadarBrief("MadarFiles application"));
 }
 @Test public void orbitAndOtherProductsDoNotBecomeFileSoftware(){
  assertFalse(AgentMotion.isMadarBrief("موشن تعليمي عن مدار الأرض حول الشمس"));
  assertFalse(AgentMotion.isMadarBrief("تطبيق يشرح مدار الكواكب"));
  assertFalse(AgentMotion.isMadarBrief("إعلان عن دقيق القمح البلدي"));
 }
}
