package com.madar.files;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class SpeechLanguageTest {
 @Test public void clearDialogueOutvotesOneShortMisidentifiedShout(){assertEquals("ja",SpeechLanguage.decide(Arrays.asList("en","ja","ja"),Arrays.asList(20000,160000,140000)));}
 @Test public void ambiguousLanguagesRequireManualChoice(){assertEquals("",SpeechLanguage.decide(Arrays.asList("ja","en"),Arrays.asList(160000,160000)));}
 @Test public void retainsOnlyThreeSpeechRichContexts(){List<SpeechLanguage.Sample> samples=new ArrayList<>();for(int n:new int[]{40000,100000,60000,200000})SpeechLanguage.consider(samples,new float[320000],new int[]{0,n});assertEquals(3,samples.size());assertEquals(200000,samples.get(0).voiced);assertEquals(60000,samples.get(2).voiced);}
}
