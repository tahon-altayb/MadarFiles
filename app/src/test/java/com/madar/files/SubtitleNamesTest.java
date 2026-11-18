package com.madar.files;
import org.junit.Test;
import static org.junit.Assert.*;
public class SubtitleNamesTest {
 @Test public void namesAreProtectedButDeathAndLongerWordsAreUnchanged()throws Exception {
  SubtitleNames names=new SubtitleNames("シン = شين\nShin = شين");SubtitleNames.Protected p=names.protect("シン殿、死ぬな。シンガー Shin Shining");assertTrue(p.text.contains("死ぬな"));assertTrue(p.text.contains("シンガー"));assertTrue(p.text.contains("Shining"));assertEquals("شين殿、死ぬな。シンガー شين Shining",p.restore(p.text));
 }
 @Test public void lostOrDuplicatedNameTokensAreRejected()throws Exception {
  SubtitleNames.Protected p=new SubtitleNames("信=شين").protect("信!");for(String translated:new String[]{"الموت!",p.text+p.text}){try{p.restore(translated);fail();}catch(java.io.IOException expected){}}
 }
 @Test public void duplicateAndMalformedGlossariesFailClearly()throws Exception {
  for(String glossary:new String[]{"信=شين\n信=سين","信","=شين","信="}){try{new SubtitleNames(glossary);fail();}catch(java.io.IOException expected){}}
 }
}
