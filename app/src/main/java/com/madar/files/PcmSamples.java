package com.madar.files;
import java.io.*;
/** PCM resampling does not initialize any native AI engine. */
final class PcmSamples {
 static float[] read(RandomAccessFile input,int rate,long from,int count,long totalFrames)throws IOException{
  double ratio=rate/44100.0;long first=(long)Math.floor(from*ratio),last=Math.min(totalFrames-1,(long)Math.ceil((from+count)*ratio));
  int sourceCount=(int)(last-first+1);byte[] bytes=new byte[sourceCount*4];input.seek(first*4);input.readFully(bytes);float[] result=new float[count*2];
  for(int i=0;i<count;i++){double position=(from+i)*ratio-first;int a=Math.max(0,Math.min(sourceCount-1,(int)position)),b=Math.min(sourceCount-1,a+1);float weight=(float)(position-a);
   for(int ch=0;ch<2;ch++){int ai=a*4+ch*2,bi=b*4+ch*2;short x=(short)((bytes[ai]&255)|(bytes[ai+1]<<8)),y=(short)((bytes[bi]&255)|(bytes[bi+1]<<8));result[2*i+ch]=(x+(y-x)*weight)/32768f;}}
  return result;
 }
}
