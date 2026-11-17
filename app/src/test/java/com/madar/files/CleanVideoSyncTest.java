package com.madar.files;
import org.junit.Test;
import static org.junit.Assert.*;
public class CleanVideoSyncTest {
 @Test public void correctionChasesAudioAndKeepsRequestedSpeedWithinFivePercent(){
  for(float requested:new float[]{.5f,1,1.25f,2}){
   assertEquals(requested,CleanVideoSync.speed(requested,.02),0);
   assertTrue(CleanVideoSync.speed(requested,.4)<requested);
   assertTrue(CleanVideoSync.speed(requested,-.4)>requested);
   assertEquals(requested*.95,CleanVideoSync.speed(requested,20),.00001);
   assertEquals(requested*1.05,CleanVideoSync.speed(requested,-20),.00001);
  }
 }
 @Test public void aHalfSecondDriftConvergesWithoutSeekOrAudioPause(){
  for(double initial:new double[]{-.65,.65}){
   double drift=initial;
   for(int tick=0;tick<30000;tick++)drift+=(CleanVideoSync.speed(1,drift)-1)*.03;
   assertTrue(Math.abs(drift)<.041);
  }
 }
}
