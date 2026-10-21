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
}
