package com.madar.files;
import org.junit.Test;
import static org.junit.Assert.*;
public class BoundsTest {
    @Test public void clampsDimensionsAndQuality(){assertEquals(4096,FilesUtil.bounded(8000,32,4096),0);assertEquals(32,FilesUtil.bounded(-1,32,4096),0);assertEquals(80,FilesUtil.bounded(80,10,100),0);}
    @Test(expected=IllegalArgumentException.class) public void rejectsNaN(){FilesUtil.bounded(Double.NaN,0,1);}
    @Test(expected=IllegalArgumentException.class) public void rejectsInfinity(){FilesUtil.bounded(Double.POSITIVE_INFINITY,0,1);}
}
