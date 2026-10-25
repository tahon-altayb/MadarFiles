package com.madar.files;
import org.junit.Test;
import static org.junit.Assert.*;
public class AspectSizingTest {
 @Test public void landscapeAndPortraitAreActualOppositePixelDimensions(){assertArrayEquals(new int[]{1920,1080},AspectSizing.dimensions("16:9",1080));assertArrayEquals(new int[]{1080,1920},AspectSizing.dimensions("9:16",1080));assertArrayEquals(new int[]{3840,2160},AspectSizing.dimensions("16:9",2160));assertArrayEquals(new int[]{1080,1080},AspectSizing.dimensions("1:1",1080));}
 @Test public void dimensionsStayEvenAndBounded(){for(String ratio:new String[]{"16:9","9:16","1:1","4:3","3:4"})for(int edge:new int[]{720,1080,2160}){int[] size=AspectSizing.dimensions(ratio,edge);assertEquals(0,size[0]%2);assertEquals(0,size[1]%2);assertTrue(size[0]<=4096&&size[1]<=4096);}}
 @Test public void rejectsUnboundedRatiosAndUnsupportedResolution(){for(String ratio:new String[]{"0:1","NaN","original","100000:1"})try{AspectSizing.dimensions(ratio,1080);fail(ratio);}catch(IllegalArgumentException expected){}try{AspectSizing.dimensions("16:9",999999);fail();}catch(IllegalArgumentException expected){}}
}
