package com.madar.files;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;
public class CensorPlanTest {
 @Test public void requestedRegionAndForwardReserveComeBeforeHistory(){Set<Long> done=new HashSet<>();for(long at=48000;at<112000;at+=8000){assertEquals(at,CensorPlan.next(54000,160000,done::contains));done.add(at);}assertEquals(0,CensorPlan.next(54000,160000,done::contains));}
 @Test public void seekIntoMissingRegionIsPrioritisedAndCompletedRegionsAreReused(){Set<Long> done=new HashSet<>(Arrays.asList(0L,8000L,16000L));assertEquals(80000,CensorPlan.next(83000,96000,done::contains));done.add(80000L);assertEquals(88000,CensorPlan.next(83000,96000,done::contains));assertEquals(24000,CensorPlan.next(0,96000,done::contains));}
 @Test public void lastPartialBlockAndLongMediaHaveNoArtificialDurationLimit(){assertEquals(8000,CensorPlan.next(9000,10001,start->start==0));assertEquals(7*3600000L/8000*8000,CensorPlan.next(7*3600000L,8*3600000L,start->false));assertEquals(-1,CensorPlan.next(0,16000,start->true));}
}
