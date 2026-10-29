package com.madar.files;
import org.junit.Test;
import static org.junit.Assert.*;
public class WatchBufferTest {
 @Test public void oneSampleContextTailsNeverPoisonThroughputOrInventYearsOfWaiting(){WatchBuffer b=new WatchBuffer(1500,true);b.commit(4,0);b.commit(8,1000);b.commit(12,2000);b.completeCycle(24,12);double before=b.speed(2000);b.commit(12+1.0/44100,4000);b.commit(16,5000);b.commit(20,6000);assertTrue(b.speed(6000)>.5);assertTrue(b.waitSeconds(0,20,1,6000)<3600);assertTrue(before>1);}
 @Test public void automaticReserveNeverBecomesTheRemainingEpisode(){
  for(double duration:new double[]{24,600,1500,7200})for(double speed:new double[]{.5,1,2}){
   WatchBuffer b=new WatchBuffer(duration,true);b.commit(4,0);b.commit(8,10000);b.commit(12,20000);b.completeCycle(40,137);b.planGap(300);
   assertTrue(b.required(0,speed,1000000)<=30*speed);assertTrue(b.required(0,speed,1000000)<=duration);
   assertEquals(Math.min(2,30*speed),b.required(duration-2,speed,1000000),0);
  }
 }
 @Test public void initialMeasurementHasABoundedReserveAndDoesNotInventSpeed(){WatchBuffer b=new WatchBuffer(3600);assertEquals(-1,b.waitSeconds(0,4,1,0));b.commit(4,100000);b.commit(8,101000);assertEquals(30,b.required(0,1,101000),0);b.commit(12,102000);assertEquals(3.2,b.speed(102000),.001);assertTrue(b.required(0,1,102000)<=20);}
 @Test public void slowCellularLogWaitsForAPrefixRatherThanThirteenMinutes(){WatchBuffer b=new WatchBuffer(1500,true);b.commit(4,114000);b.commit(40,137000);b.completeCycle(40,100);b.planGap(118);assertEquals(30,b.required(0,1,245000),0);assertEquals(0,b.waitSeconds(0,40,1,245000));assertEquals(30,b.required(10,1,627000),0);}
 @Test public void requestedSeekNeedsOnlyThreeSecondsAndHonorsSpeedAndEnd(){assertEquals(3,WatchBuffer.seekRequired(1200,1),0);assertEquals(6,WatchBuffer.seekRequired(1200,2),0);assertEquals(1,WatchBuffer.seekRequired(1,1),0);assertEquals(0,WatchBuffer.seekRequired(-1,1),0);assertEquals(3,WatchBuffer.seekRequired(Double.POSITIVE_INFINITY,1),0);}
 @Test public void decodeUploadGapsAndPlaybackSpeedIncreaseOnlyTheBoundedBudget(){WatchBuffer b=new WatchBuffer(3600);b.commit(4,0);b.commit(8,1000);b.commit(12,2000);double fast=b.required(0,1,2000);assertTrue(b.required(0,1,62000)>fast);b.commit(16,64000);assertTrue(b.required(0,1,64000)>fast);assertTrue(b.required(0,2,64000)>=b.required(0,1,64000));assertTrue(b.required(0,2,64000)<=60);}
 @Test public void watchSessionsStillMeasureTheWholePipelineSpeed(){WatchBuffer b=new WatchBuffer(3600,true);b.commit(4,0);b.commit(8,1000);b.commit(12,2000);assertEquals(0,b.speed(2000),0);b.completeCycle(120,180);assertTrue(b.speed(2000)<1);assertEquals(30,b.required(0,1,2000),0);}
 @Test public void slowAndFastProductionBothMakeProgressWithBoundedAutomaticResumes(){
  for(double rate:new double[]{.25,.5,1,2,4}){WatchBuffer b=new WatchBuffer(600);double ready=0,played=0;boolean playing=false;int resumes=0;for(long second=0;played<600&&second<10000;second++){double next=Math.min(600,Math.floor(second*rate/4)*4);if(next>ready){ready=next;b.commit(ready,second*1000);}if(!playing&&(ready>=600||ready-played>=b.required(played,1,second*1000))){playing=true;resumes++;}if(playing){if(ready-played>=1)played++;else playing=false;}}assertTrue(resumes>0);assertEquals("producer rate="+rate,600,played,0);}
 }
}
