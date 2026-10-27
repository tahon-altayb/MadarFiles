package com.madar.files;
import org.junit.Test;
import static org.junit.Assert.*;
public class WatchWindowsTest {
 @Test public void longPlaybackUsesContiguousBoundedCoresWithContext(){
  for(boolean cloud:new boolean[]{false,true})for(long origin:new long[]{0,1250000,632500000})for(long duration:new long[]{1,3999999,(cloud?120:52)*1000000L-1,(cloud?120:52)*1000000L+1,(cloud?120:52)*1000000L+3000000,7200123456L}){
   long end=origin+duration,next=origin,core=(cloud?120:52)*1000000L;int windows=0;
   while(next<end){WatchWindows w=new WatchWindows(next,end,cloud);assertEquals(next,w.coreStart);assertEquals(Math.min(end,next+core),w.coreEnd);assertTrue(w.coreEnd>w.coreStart);assertEquals(Math.max(0,next-4000000),w.decodeStart);assertEquals(Math.min(end,w.coreEnd+4000000),w.decodeEnd);assertTrue(w.decodeEnd-w.decodeStart<=(cloud?128:60)*1000000L);next=w.coreEnd;windows++;}
   assertEquals(end,next);if(duration>core)assertTrue(windows>1);
  }
 }
}
