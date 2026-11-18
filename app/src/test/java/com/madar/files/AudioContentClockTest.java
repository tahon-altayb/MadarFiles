package com.madar.files;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioContentClockTest {
 @Test public void tailGuardDrainsEveryRealFrameWithoutBecomingHeardWords(){
  AudioContentClock clock=new AudioContentClock();clock.contentWritten(176400);clock.paddingWritten(2205);
  assertEquals(174930,clock.contentAt(174930));assertEquals(176400,clock.contentAt(176400));
  for(long head=176400;head<=178605;head++)assertEquals(176400,clock.contentAt(head));
  clock.contentWritten(44100);assertEquals(176401,clock.contentAt(178606));assertEquals(220500,clock.contentAt(222705));
 }
 @Test public void partialGuardsAndLaterRefillsPreserveEveryContentBoundary(){
  AudioContentClock clock=new AudioContentClock();clock.contentWritten(100);clock.paddingWritten(7);clock.paddingWritten(13);clock.contentWritten(50);clock.paddingWritten(10);clock.contentWritten(30);
  assertEquals(99,clock.contentAt(99));assertEquals(100,clock.contentAt(110));assertEquals(101,clock.contentAt(121));assertEquals(150,clock.contentAt(175));assertEquals(151,clock.contentAt(181));assertEquals(180,clock.contentAt(210));
  // Pausing and resuming queries cannot consume a queued guard or real words.
  assertEquals(150,clock.contentAt(175));assertEquals(150,clock.contentAt(170));
 }
 @Test public void aUserSeekStartsAnIndependentOutputEpoch(){
  AudioContentClock clock=new AudioContentClock();clock.contentWritten(1000);clock.paddingWritten(2205);clock.reset();clock.contentWritten(400);
  assertEquals(0,clock.contentAt(0));assertEquals(200,clock.contentAt(200));assertEquals(400,clock.contentAt(400));
 }
}
