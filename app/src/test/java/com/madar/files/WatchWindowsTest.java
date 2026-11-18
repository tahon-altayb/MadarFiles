package com.madar.files;
import org.junit.Test;
import static org.junit.Assert.*;
public class WatchWindowsTest {
 @Test public void cloudScheduleFollowsRequestedDurationsIndependentOfReserve(){
  long next=632500000L,end=next+7200000000L;int[] seconds={40,60,90,150,240,360,540,600,600,600};
  for(int i=0;i<seconds.length;i++){WatchWindows w=WatchWindows.forWatch(next,end,true,i);assertEquals(seconds[i]*1000000L,w.coreEnd-w.coreStart);assertEquals(next,w.coreStart);assertEquals(next-4000000L,w.decodeStart);assertEquals(w.coreEnd+4000000L,w.decodeEnd);next=w.coreEnd;}
 }
 @Test public void longPlaybackUsesContiguousBoundedCoresWithContext(){
  for(boolean cloud:new boolean[]{false,true})for(long origin:new long[]{0,1250000,632500000})for(long duration:new long[]{1,3999999,39999999,40000001,7200123456L}){
   long end=origin+duration,next=origin;int index=0;
   while(next<end){WatchWindows w=WatchWindows.forWatch(next,end,cloud,index++);assertEquals(next,w.coreStart);assertEquals(Math.max(0,next-4000000L),w.decodeStart);assertTrue(w.coreEnd>w.coreStart);assertEquals(Math.min(end,w.coreEnd+4000000L),w.decodeEnd);assertTrue(w.decodeEnd-w.decodeStart<=(cloud?608:60)*1000000L);next=w.coreEnd;}
   assertEquals(end,next);
  }
 }
}
