package com.madar.files;
import org.junit.Test;
import static org.junit.Assert.*;
public class WatchBufferTest {
 @org.junit.Test public void gpuRequiredPlaybackGateCannotBeOverriddenByUserPreference(){
  LiveSeparation.Session session=new LiveSeparation.Session(null,true);
  session.forceWaitForComplete=true;
  session.waitForComplete=false;
  org.junit.Assert.assertTrue(session.waitsForComplete());
  session.forceWaitForComplete=false;
  org.junit.Assert.assertFalse(session.waitsForComplete());
 }
 @Test public void oneSampleContextTailsNeverPoisonThroughputOrInventYearsOfWaiting(){WatchBuffer b=new WatchBuffer(1500,true);b.commit(4,0);b.commit(8,1000);b.commit(12,2000);b.completeCycle(24,12);double before=b.speed(2000);b.commit(12+1.0/44100,4000);b.commit(16,5000);b.commit(20,6000);assertTrue(b.speed(6000)>.5);assertTrue(b.waitSeconds(0,20,1,6000)<3600);assertTrue(before>1);}
 @Test public void measuredSlowProductionReservesTheDeficitWithinTheRemainingEpisode(){
  for(double duration:new double[]{24,600,1500,7200})for(double speed:new double[]{.5,1,2}){
   WatchBuffer b=new WatchBuffer(duration,true);b.commit(4,0);b.commit(8,10000);b.commit(12,20000);b.completeCycle(40,137);b.planGap(300);
   assertTrue(b.required(0,speed,1000000)>0);assertTrue(b.required(0,speed,1000000)<=duration);
   assertEquals(Math.min(2,30*speed),b.required(duration-2,speed,1000000),0);
  }
 }
 @Test public void initialMeasurementHasABoundedReserveAndDoesNotInventSpeed(){WatchBuffer b=new WatchBuffer(3600);assertEquals(-1,b.waitSeconds(0,4,1,0));b.commit(4,100000);b.commit(8,101000);assertEquals(30,b.required(0,1,101000),0);b.commit(12,102000);assertEquals(3.2,b.speed(102000),.001);assertTrue(b.required(0,1,102000)<=20);}
 @Test public void slowCellularLogDoesNotPretendThirtySecondsWillAvoidRepeatedStalls(){WatchBuffer b=new WatchBuffer(1500,true);b.commit(4,114000);b.commit(40,137000);b.completeCycle(40,100);b.planGap(118);assertTrue(b.required(0,1,245000)>30);assertTrue(b.waitSeconds(0,40,1,245000)>0);assertTrue(b.required(10,1,627000)>30);}
 @Test public void audiovisualStartupWaitsForNinetySecondsWithoutInflatingRefill(){
  WatchBuffer buffer=new WatchBuffer(300,true,true,90);
  assertEquals(90,buffer.startupRequired(0,1,0),0);
  assertEquals(20,buffer.startupRequired(280,1,0),0);
  assertEquals(6,buffer.required(100,1,0),0);
  assertEquals(6,new WatchBuffer(300,true,true).startupRequired(0,1,0),0);
 }
 @Test public void requestedSeekNeedsOnlyThreeSecondsAndHonorsSpeedAndEnd(){assertEquals(3,WatchBuffer.seekRequired(1200,1),0);assertEquals(6,WatchBuffer.seekRequired(1200,2),0);assertEquals(1,WatchBuffer.seekRequired(1,1),0);assertEquals(0,WatchBuffer.seekRequired(-1,1),0);assertEquals(3,WatchBuffer.seekRequired(Double.POSITIVE_INFINITY,1),0);}
 @Test public void decodeUploadGapsAndPlaybackSpeedIncreaseOnlyTheBoundedBudget(){WatchBuffer b=new WatchBuffer(3600);b.commit(4,0);b.commit(8,1000);b.commit(12,2000);double fast=b.required(0,1,2000);assertTrue(b.required(0,1,62000)>fast);b.commit(16,64000);assertTrue(b.required(0,1,64000)>fast);assertTrue(b.required(0,2,64000)>=b.required(0,1,64000));assertTrue(b.required(0,2,64000)<=3600);}
 @Test public void watchSessionsStillMeasureTheWholePipelineSpeed(){WatchBuffer b=new WatchBuffer(3600,true);b.commit(4,0);b.commit(8,1000);b.commit(12,2000);assertEquals(0,b.speed(2000),0);b.completeCycle(120,180);assertTrue(b.speed(2000)<1);assertTrue(b.required(0,1,2000)>1000);}
 @Test public void slowAndFastProductionBothMakeProgressWithBoundedAutomaticResumes(){
  for(double rate:new double[]{.25,.5,1,2,4}){WatchBuffer b=new WatchBuffer(600);double ready=0,played=0;boolean playing=false;int resumes=0,stalls=0;for(long second=0;played<600&&second<10000;second++){double next=Math.min(600,Math.floor(second*rate/4)*4);if(next>ready){ready=next;b.commit(ready,second*1000);}if(!playing&&(ready>=600||ready-played>=b.required(played,1,second*1000))){playing=true;resumes++;}if(playing){if(ready-played>=1)played++;else {playing=false;stalls++;}}}assertTrue(resumes>0);assertTrue("Should avoid repeated exhaustion at producer rate="+rate+", stalls="+stalls,stalls<=1);assertEquals("producer rate="+rate,600,played,0);}
 }
 @Test public void slowerThanPlaybackWaitsOnceForTheWholeDeficitButFastKeepsAShortRefill(){
  WatchBuffer fast=new WatchBuffer(1380,true,true,120);fast.commit(16,0);fast.commit(40,20000);fast.commit(80,40000);fast.completeCycle(40,20);
  assertEquals(120,fast.startupRequired(0,1,40000),0);assertTrue(fast.required(80,1,40000)<=18);
  WatchBuffer slow=new WatchBuffer(1380,true,true,120);slow.commit(16,0);slow.commit(40,40000);slow.commit(80,106000);slow.commit(120,172000);slow.completeCycle(40,66);
  assertTrue(slow.required(120,1,172000)>500);assertTrue(slow.required(120,1,172000)<=1260);
 }
 @Test public void slowFirstWindowsDoNotHoldBackPlaybackOnceBatchesAreAhead(){
  // Report 1.9.90: ready 3:04 while the screen asked for 8:52 (first windows and the cold start measured slow).
  WatchBuffer buffer=new WatchBuffer(1430,true,true,120);
  double[][] commits={{8,109},{32,135},{72,186},{128,216},{184,256}};
  for(double[] c:commits){buffer.commit(c[0],(long)(c[1]*1000));assertEquals(120,buffer.startupRequired(0,1,(long)(c[1]*1000)),0);}
 }
}
