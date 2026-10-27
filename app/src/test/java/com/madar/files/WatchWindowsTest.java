package com.madar.files;
import org.junit.Test;
import static org.junit.Assert.*;
public class WatchWindowsTest {
 @Test public void longPlaybackUsesContiguousBoundedCoresWithContext(){
  for(boolean cloud:new boolean[]{false,true})for(long origin:new long[]{0,1250000,632500000}){
   long end=origin+7200123456L,next=origin;int windows=0;
   while(next<end){WatchWindows w=new WatchWindows(next,end,cloud);assertEquals(next,w.coreStart);assertTrue(w.coreEnd>w.coreStart);assertTrue(w.decodeStart<=w.coreStart);assertTrue(w.decodeEnd>=w.coreEnd);assertTrue(w.decodeEnd-w.decodeStart<=(cloud?600:60)*1000000L);if(next>=4000000)assertTrue(w.coreStart-w.decodeStart>=4000000);if(w.coreEnd<end)assertTrue(w.decodeEnd-w.coreEnd>=4000000);next=w.coreEnd;windows++;}
   assertEquals(end,next);assertTrue(windows>1);
  }
 }
}
