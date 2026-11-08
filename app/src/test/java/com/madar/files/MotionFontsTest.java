package com.madar.files;

import static org.junit.Assert.*;
import org.junit.Test;

public class MotionFontsTest {
 @Test public void acceptsOnlyOpenTypeAndTrueType(){
  assertEquals("otf",MotionFonts.kind(new byte[]{'O','T','T','O'}));
  assertEquals("ttf",MotionFonts.kind(new byte[]{0,1,0,0}));
  assertEquals("ttf",MotionFonts.kind(new byte[]{'t','r','u','e'}));
  assertNull(MotionFonts.kind(new byte[]{'w','O','F','2'}));
  assertNull(MotionFonts.kind(new byte[]{'P','K',3,4}));
  assertNull(MotionFonts.kind(new byte[]{1}));
 }
}
