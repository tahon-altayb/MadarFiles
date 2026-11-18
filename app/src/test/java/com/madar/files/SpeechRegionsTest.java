package com.madar.files;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
public class SpeechRegionsTest {
 @Test public void preservesBoundarySpeechAndSkipsLongNonspeechGaps(){
  List<int[]> ranges=SpeechRegions.padded(new int[]{0,8000,8800,16000,80000,90000},100000);
  assertEquals(2,ranges.size());assertArrayEquals(new int[]{0,20800},ranges.get(0));assertArrayEquals(new int[]{75200,94800},ranges.get(1));
 }
 @Test public void keepsRelatedClausesAcrossANaturalPause(){List<int[]> ranges=SpeechRegions.padded(new int[]{10000,20000,44000,60000},100000);assertEquals(1,ranges.size());assertArrayEquals(new int[]{5200,64800},ranges.get(0));}
 @Test public void silenceHasNoRecognitionWindow(){assertTrue(SpeechRegions.padded(new int[0],320000).isEmpty());}
 @Test public void clipsPaddingAtTheEnd(){assertArrayEquals(new int[]{24200,32000},SpeechRegions.padded(new int[]{29000,32000},32000).get(0));}
 @Test public void continuousContextJoinsAcrossAnOldTwentySecondBufferBoundary(){
  java.util.List<int[]> ranges=SpeechRegions.continuous(new int[]{18*16000,20*16000+1000,21*16000,23*16000},30*16000);
  assertEquals(1,ranges.size());assertArrayEquals(new int[]{18*16000-4800,23*16000+4800},ranges.get(0));
 }
 @Test public void continuousWindowsStayBoundedAndDoNotRepeatSharedPadding(){
  java.util.List<int[]> ranges=SpeechRegions.continuous(new int[]{0,20*16000,20*16000,40*16000},40*16000);
  assertEquals(2,ranges.size());assertEquals(ranges.get(0)[1],ranges.get(1)[0]);
  for(int[] r:ranges){assertTrue(r[1]>r[0]);assertTrue(r[1]-r[0]<=24*16000);}
 }
 @Test public void continuousRegionsPreserveSilenceAndFinalShortSpeech(){
  java.util.List<int[]> ranges=SpeechRegions.continuous(new int[]{0,8000,29*16000,30*16000},30*16000);
  assertEquals(2,ranges.size());assertEquals(30*16000,ranges.get(1)[1]);assertTrue(ranges.get(1)[0]>ranges.get(0)[1]);
 }
}
