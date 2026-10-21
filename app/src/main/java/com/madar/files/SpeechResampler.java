package com.madar.files;
import java.io.*;
/** Bounded windowed-sinc resampling; avoids high-frequency aliasing in 44.1/48 kHz speech. */
final class SpeechResampler {
 private static final int TAPS=32,PHASES=1024;private final int rate;private final float[][] weights=new float[PHASES][TAPS];
 SpeechResampler(int rate){this.rate=rate;double cutoff=.45*Math.min(1,16000.0/rate);for(int phase=0;phase<PHASES;phase++){double sum=0,fraction=phase/(double)PHASES;for(int i=0;i<TAPS;i++){double x=i-(TAPS/2-1)-fraction,v=Math.abs(x)<1e-8?2*cutoff:Math.sin(2*Math.PI*cutoff*x)/(Math.PI*x);v*=.5+.5*Math.cos(Math.PI*x/(TAPS/2));weights[phase][i]=(float)v;sum+=v;}for(int i=0;i<TAPS;i++)weights[phase][i]/=(float)sum;}}
 float[] read(RandomAccessFile pcm,long frames,long start,int count)throws IOException{long first=Math.max(0,(long)Math.floor(start*rate/16000.0)-TAPS),last=Math.min(frames-1,(long)Math.ceil((start+count)*rate/16000.0)+TAPS);if(last<first)throw new IOException("صوت غير صالح");byte[] bytes=new byte[Math.toIntExact((last-first+1)*4)];pcm.seek(first*4);pcm.readFully(bytes);float[] samples=new float[count];for(int i=0;i<count;i++){double point=(start+i)*rate/16000.0;long base=(long)Math.floor(point);if(rate==16000){samples[i]=sample(bytes,(int)(Math.min(frames-1,base)-first));continue;}int phase=Math.min(PHASES-1,(int)((point-base)*PHASES));float sum=0;for(int tap=0;tap<TAPS;tap++){long index=Math.max(0,Math.min(frames-1,base+tap-(TAPS/2-1)));sum+=sample(bytes,(int)(index-first))*weights[phase][tap];}samples[i]=Math.max(-1,Math.min(1,sum));}return samples;}
 private static float sample(byte[] pcm,int at){int p=at*4;short l=(short)((pcm[p]&255)|(pcm[p+1]<<8)),r=(short)((pcm[p+2]&255)|(pcm[p+3]<<8));return (l+r)/65536f;}
}
