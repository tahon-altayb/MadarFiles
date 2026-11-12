package com.madar.files;
import java.io.IOException;
import java.io.OutputStream;
/** Animated GIF89a writer in plain Java: a median-cut palette per frame and LZW. No Android classes, so it is unit-tested on the JVM. */
final class GifWriter implements AutoCloseable {
 private final OutputStream out;private final int width,height,delay;private boolean closed;
 /** @param delayCs frame delay in hundredths of a second */
 GifWriter(OutputStream out,int width,int height,int delayCs)throws IOException{
  if(width<1||height<1||width>4096||height>4096||delayCs<2||delayCs>6000)throw new IllegalArgumentException("GIF size");
  this.out=out;this.width=width;this.height=height;this.delay=delayCs;
  out.write(new byte[]{'G','I','F','8','9','a'});word(width);word(height);out.write(0x70);out.write(0);out.write(0);
  // Loop forever.
  out.write(new byte[]{0x21,(byte)0xFF,11,'N','E','T','S','C','A','P','E','2','.','0',3,1,0,0,0});
 }
 private void word(int v)throws IOException{out.write(v&255);out.write(v>>8&255);}
 /** Adds one frame of width*height ARGB pixels (alpha is ignored). */
 void frame(int[] argb)throws IOException{
  if(closed||argb.length!=width*height)throw new IllegalArgumentException("GIF frame");
  int[] palette=new int[256];byte[] lookup=new byte[32768];int colours=quantize(argb,palette,lookup);
  int bits=1;while((1<<bits)<colours)bits++;if(bits<2)bits=2;
  out.write(new byte[]{0x21,(byte)0xF9,4,0});word(delay);out.write(0);out.write(0);
  out.write(0x2C);word(0);word(0);word(width);word(height);out.write(0x80|bits-1);
  for(int i=0;i<(1<<bits);i++){int c=i<colours?palette[i]:0;out.write(c>>16&255);out.write(c>>8&255);out.write(c&255);}
  byte[] indexed=new byte[argb.length];for(int i=0;i<argb.length;i++){int p=argb[i];indexed[i]=lookup[(p>>9&0x7C00)|(p>>6&0x3E0)|(p>>3&0x1F)];}
  lzw(indexed,bits);
 }
 @Override public void close()throws IOException{if(closed)return;closed=true;out.write(0x3B);out.flush();}
 /** Median cut over a 15-bit histogram; fills the palette (RGB) and the 15-bit to index table, returns the colour count. */
 private static int quantize(int[] argb,int[] palette,byte[] lookup){
  int[] hist=new int[32768];for(int p:argb)hist[(p>>9&0x7C00)|(p>>6&0x3E0)|(p>>3&0x1F)]++;
  int used=0;for(int h:hist)if(h>0)used++;int[] cols=new int[used];for(int i=0,n=0;i<32768;i++)if(hist[i]>0)cols[n++]=i;
  // Boxes are ranges of cols[]; split the box with the widest weighted channel at its median until 256 remain.
  int[] lo=new int[256],hi=new int[256];int boxes=1;lo[0]=0;hi[0]=used;
  while(boxes<256){
   int best=-1,bestChannel=0;long bestScore=0;
   for(int b=0;b<boxes;b++){if(hi[b]-lo[b]<2)continue;long count=0;int[] min={31,31,31},max={0,0,0};
    for(int i=lo[b];i<hi[b];i++){int c=cols[i];count+=hist[c];for(int k=0;k<3;k++){int v=c>>(10-5*k)&31;if(v<min[k])min[k]=v;if(v>max[k])max[k]=v;}}
    for(int k=0;k<3;k++){long score=(long)(max[k]-min[k])*(k==1?5:k==0?4:3)*(long)Math.sqrt(count);if(score>bestScore){bestScore=score;best=b;bestChannel=k;}}}
   if(best<0)break;
   final int shift=10-5*bestChannel;int a=lo[best],z=hi[best];
   // Counting sort of this range by the chosen channel.
   int[] bucket=new int[33];for(int i=a;i<z;i++)bucket[(cols[i]>>shift&31)+1]++;for(int i=1;i<33;i++)bucket[i]+=bucket[i-1];
   int[] sorted=new int[z-a];for(int i=a;i<z;i++)sorted[bucket[cols[i]>>shift&31]++]=cols[i];System.arraycopy(sorted,0,cols,a,z-a);
   long total=0;for(int i=a;i<z;i++)total+=hist[cols[i]];long half=0;int cut=a+1;
   for(int i=a;i<z-1;i++){half+=hist[cols[i]];cut=i+1;if(half*2>=total)break;}
   lo[boxes]=cut;hi[boxes]=z;hi[best]=cut;boxes++;
  }
  for(int b=0;b<boxes;b++){long r=0,g=0,bl=0,n=0;
   for(int i=lo[b];i<hi[b];i++){int c=cols[i],w=hist[c];r+=(long)(c>>10&31)*w;g+=(long)(c>>5&31)*w;bl+=(long)(c&31)*w;n+=w;lookup[c]=(byte)b;}
   if(n==0)n=1;int R=(int)(r*255/(31*n)),G=(int)(g*255/(31*n)),B=(int)(bl*255/(31*n));palette[b]=R<<16|G<<8|B;}
  return boxes;
 }
 private void lzw(byte[] pixels,int bits)throws IOException{
  out.write(bits);final int clear=1<<bits,end=clear+1;int size=bits+1,next=end+1;
  final int HASH=5003;int[] keys=new int[HASH];short[] codes=new short[HASH];java.util.Arrays.fill(keys,-1);
  byte[] block=new byte[255];int filled=0,acc=0,accBits=0;
  // Emit helper inlined for speed.
  acc|=clear<<accBits;accBits+=size;while(accBits>=8){block[filled++]=(byte)acc;acc>>>=8;accBits-=8;if(filled==255){out.write(255);out.write(block,0,255);filled=0;}}
  int prefix=pixels[0]&255;
  for(int i=1;i<pixels.length;i++){
   int c=pixels[i]&255,key=prefix<<8|c,h=(c<<4^prefix)%HASH;if(h<0)h+=HASH;boolean found=false;
   while(keys[h]!=-1){if(keys[h]==key){prefix=codes[h]&0xFFFF;found=true;break;}h=h==0?HASH-1:h-1;}
   if(found)continue;
   acc|=prefix<<accBits;accBits+=size;while(accBits>=8){block[filled++]=(byte)acc;acc>>>=8;accBits-=8;if(filled==255){out.write(255);out.write(block,0,255);filled=0;}}
   if(next<4096){keys[h]=key;codes[h]=(short)next;if(next==(1<<size)&&size<12)size++;next++;}
   else{acc|=clear<<accBits;accBits+=size;while(accBits>=8){block[filled++]=(byte)acc;acc>>>=8;accBits-=8;if(filled==255){out.write(255);out.write(block,0,255);filled=0;}}
    java.util.Arrays.fill(keys,-1);size=bits+1;next=end+1;}
   prefix=c;
  }
  acc|=prefix<<accBits;accBits+=size;while(accBits>=8){block[filled++]=(byte)acc;acc>>>=8;accBits-=8;if(filled==255){out.write(255);out.write(block,0,255);filled=0;}}
  if(next==(1<<size)&&size<12)size++;
  acc|=end<<accBits;accBits+=size;while(accBits>0){block[filled++]=(byte)acc;acc>>>=8;accBits-=8;if(filled==255){out.write(255);out.write(block,0,255);filled=0;}}
  if(filled>0){out.write(filled);out.write(block,0,filled);}out.write(0);
 }
}
