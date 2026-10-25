package com.madar.files;
import org.junit.Test;
import static org.junit.Assert.*;
public class GeminiPlanTest {
 @Test public void userClipNeedsOneRequestAndReviewIsExplicit(){assertEquals(1,GeminiPlan.requests(157L*16000,false));assertEquals(2,GeminiPlan.requests(157L*16000,true));}
 @Test public void boundaryAndLongClipStayWithinInlineBudget(){long step=GeminiPlan.step();assertEquals(1,GeminiPlan.requests(step,false));assertEquals(2,GeminiPlan.requests(step+1,false));assertTrue(GeminiPlan.encodedWavBytes(step+2L*GeminiPlan.CONTEXT)+GeminiPlan.RESERVE_BYTES<=GeminiPlan.REQUEST_BYTES);assertEquals(20,GeminiPlan.requests(3600L*16000,false));}
}
