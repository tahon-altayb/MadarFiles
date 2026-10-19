package com.madar.files;
import org.junit.Test;import static org.junit.Assert.*;
public class SeparationTimingTest{
 @Test public void estimatesFromMeasuredChunksAndAdaptsToSlowdown(){SeparationTiming t=new SeparationTiming();assertEquals(-1,t.remainingSeconds(40));t.record(4,2);assertEquals(20,t.remainingSeconds(40));assertEquals(2,t.speed(),.001);t.record(4,4);assertEquals(26,t.remainingSeconds(40));assertEquals(0,t.remainingSeconds(0));}
 @Test public void ignoresInvalidSamples(){SeparationTiming t=new SeparationTiming();t.record(0,4);t.record(4,0);assertEquals(-1,t.remainingSeconds(40));}
}
