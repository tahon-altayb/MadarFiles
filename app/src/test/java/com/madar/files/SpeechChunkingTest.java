package com.madar.files;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
public class SpeechChunkingTest {
 @Test public void prefersPauseWithoutDroppingTheNextWindow(){float[] samples=new float[20*16000];Arrays.fill(samples,.2f);Arrays.fill(samples,18*16000,185*1600,0f);int cut=SpeechChunking.boundary(samples,16000);assertTrue(cut>=18*16000&&cut<185*1600);}
 @Test public void leavesContinuousAndShortSpeechIntact(){float[] continuous=new float[20*16000];Arrays.fill(continuous,.2f);assertEquals(continuous.length,SpeechChunking.boundary(continuous,16000));assertEquals(8000,SpeechChunking.boundary(new float[8000],16000));}
}
