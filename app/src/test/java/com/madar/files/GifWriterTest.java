package com.madar.files;
import static org.junit.Assert.*;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
public class GifWriterTest {
 @Test public void writesALoopingGifWithEveryFrame()throws Exception{
  int w=64,h=36;ByteArrayOutputStream bytes=new ByteArrayOutputStream();java.util.Random r=new java.util.Random(3);
  try(GifWriter gif=new GifWriter(bytes,w,h,10)){
   for(int f=0;f<4;f++){int[] p=new int[w*h];for(int i=0;i<p.length;i++)p[i]=f==0?r.nextInt():f==1?0xFF13233A:(i%w)<<16|(i/w)<<10|f;gif.frame(p);}
  }
  byte[] out=bytes.toByteArray();assertEquals("GIF89a",new String(out,0,6,"US-ASCII"));assertEquals(w,(out[6]&255)|(out[7]&255)<<8);assertEquals(h,(out[8]&255)|(out[9]&255)<<8);
  assertEquals(0x3B,out[out.length-1]&255);
  // Walk the blocks: every frame has a graphic control extension and an image descriptor, and the stream ends cleanly.
  int at=13,frames=0,controls=0;boolean loop=false;
  while(at<out.length-1){int kind=out[at]&255;
   if(kind==0x21){int label=out[at+1]&255;if(label==0xFF)loop=true;if(label==0xF9)controls++;at+=2;while((out[at]&255)!=0)at+=(out[at]&255)+1;at++;}
   else if(kind==0x2C){frames++;assertEquals(w,(out[at+5]&255)|(out[at+6]&255)<<8);int flags=out[at+9]&255;at+=10;if((flags&0x80)!=0)at+=3*(1<<((flags&7)+1));at++;while((out[at]&255)!=0)at+=(out[at]&255)+1;at++;}
   else fail("unknown GIF block "+kind+" at "+at);}
  assertEquals(out.length-1,at);assertTrue(loop);assertEquals(4,frames);assertEquals(4,controls);
 }
 @Test(expected=IllegalArgumentException.class) public void rejectsAFrameOfTheWrongSize()throws Exception{try(GifWriter gif=new GifWriter(new ByteArrayOutputStream(),8,8,10)){gif.frame(new int[10]);}}
 /** WhatsApp turns a GIF into a video, and a video cannot have an odd side: a portrait clip came out 480x853 and played as an empty box. */
 @Test public void bothSidesAreEvenAndNeverLargerThanTheVideo(){
  assertArrayEquals(new int[]{480,852},GifWriter.fit(1080,1920,480));assertArrayEquals(new int[]{480,270},GifWriter.fit(1920,1080,480));
  assertArrayEquals(new int[]{320,320},GifWriter.fit(321,321,640));assertArrayEquals(new int[]{160,284},GifWriter.fit(720,1280,160));
  for(int w=2;w<1300;w+=37)for(int h=2;h<2400;h+=53)for(int wanted:new int[]{160,321,480,640}){int[] size=GifWriter.fit(w,h,wanted);assertTrue(size[0]%2==0&&size[1]%2==0&&size[0]>=2&&size[1]>=2&&size[0]<=Math.max(2,w)&&size[0]<=wanted);}
 }
}
