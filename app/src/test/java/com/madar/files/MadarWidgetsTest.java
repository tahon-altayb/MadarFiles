package com.madar.files;

import static org.junit.Assert.*;
import org.junit.Test;

/** The download widget takes the copied link from the clipboard: the first web link in whatever was copied. */
public class MadarWidgetsTest {
 @Test public void theFirstLinkInTheCopiedText(){
  assertEquals("https://youtu.be/abc123",MadarWidgets.link("https://youtu.be/abc123"));
  assertEquals("https://www.tiktok.com/@tayebtea/video/7300",MadarWidgets.link("شوف المقطع https://www.tiktok.com/@tayebtea/video/7300 حلو"));
  assertEquals("https://x.com/a/status/1",MadarWidgets.link("(https://x.com/a/status/1)."));
  assertEquals("http://example.com/v?id=5&t=2",MadarWidgets.link("http://example.com/v?id=5&t=2\nسطر ثاني https://other.example/x"));
 }
 @Test public void whatIsNotALinkGivesNothing(){
  for(String copied:new String[]{"","كلام بدون رابط","www.youtube.com/watch","ftp://files.example/a.mp4","javascript:alert(1)","file:///sdcard/a.mp4","https://"})assertEquals(copied,"",MadarWidgets.link(copied));
  assertEquals("",MadarWidgets.link(null));
 }
 @Test public void everySectionHasItsNameAndShortcuts(){
  for(String kind:MadarWidgets.KINDS){assertTrue(MadarWidgets.known(kind));assertFalse(MadarWidgets.name(kind).isEmpty());assertFalse(MadarWidgets.glyph(kind).isEmpty());assertTrue(MadarWidgets.tools(kind).length<=4);for(String[] tool:MadarWidgets.tools(kind))assertTrue(tool[0],tool[0].matches("[A-Za-z]{1,24}")&&!tool[1].isEmpty());}
  assertEquals(0,MadarWidgets.tools("links").length);assertFalse(MadarWidgets.known("hub"));assertFalse(MadarWidgets.known("settings"));
 }
}
