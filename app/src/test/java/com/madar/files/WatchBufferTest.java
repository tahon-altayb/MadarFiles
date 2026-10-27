package com.madar.files;
import org.junit.Test;
import static org.junit.Assert.*;
public class WatchBufferTest {
 @Test public void neverInventsASpeedBeforeEnoughAudioIsMeasured(){WatchBuffer b=new WatchBuffer(3600);assertEquals(-1,b.waitSeconds(0,4,1,0));b.commit(4,100000);b.commit(8,101000);assertEquals(3600,b.required(0,1,101000),0);b.commit(12,102000);assertEquals(3.2,b.speed(102000),.001);assertTrue(b.required(0,1,102000)<=20);}
 @Test public void slowerThanPlaybackBudgetsTheWholeRemainingFilm(){WatchBuffer b=new WatchBuffer(3600);b.commit(4,0);b.commit(8,8000);b.commit(12,16000);assertEquals(.4,b.speed(16000),.001);assertTrue(b.required(0,1,16000)>2100);assertTrue(b.waitSeconds(0,12,1,16000)>5000);assertTrue(b.required(1800,1,16000)<b.required(0,1,16000));}
 @Test public void decodeUploadGapsAndPlaybackSpeedIncreaseTheBudget(){WatchBuffer b=new WatchBuffer(3600);b.commit(4,0);b.commit(8,1000);b.commit(12,2000);double fast=b.required(0,1,2000);assertTrue(b.required(0,1,62000)>fast);b.commit(16,64000);assertTrue(b.required(0,1,64000)>fast);assertTrue(b.required(0,2,64000)>=b.required(0,1,64000));}
 @Test public void watchSessionsMeasureAWholePipelineCycleBeforeStarting(){WatchBuffer b=new WatchBuffer(3600,true);b.commit(4,0);b.commit(8,1000);b.commit(12,2000);assertEquals(0,b.speed(2000),0);b.completeCycle(120,180);assertTrue(b.speed(2000)<1);assertTrue(b.required(0,1,2000)>1600);}
 @Test public void measuredBudgetAvoidsRepeatedStallsInFastAndSlowProduction(){
  for(double rate:new double[]{.25,.5,1,2,4}){WatchBuffer b=new WatchBuffer(600);double ready=0,played=0;boolean started=false;int stalls=0;for(long second=0;played<600&&second<10000;second++){double next=Math.min(600,Math.floor(second*rate/4)*4);if(next>ready){ready=next;b.commit(ready,second*1000);}if(!started&&ready-played>=b.required(played,1,second*1000))started=true;if(started){if(ready-played>=1)played++;else if(ready<600)stalls++;}}assertTrue(started);assertEquals("rate="+rate,0,stalls);assertEquals(600,played,0);}
 }
}
