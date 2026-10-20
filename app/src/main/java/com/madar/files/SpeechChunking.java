package com.madar.files;
/** Prefer a quiet boundary near the end of a recognition window. */
final class SpeechChunking {
 static int boundary(float[] samples,int rate){
  if(samples.length<rate*17)return samples.length;
  int width=Math.max(1,rate/10),begin=Math.max(width,samples.length-rate*3),cut=samples.length;double energySum=0;for(float sample:samples)energySum+=sample*sample;double quiet=Math.min(.0003,energySum/Math.max(1,samples.length)*.03);
  for(int at=begin;at+width<=samples.length;at+=width){double energy=0;for(int i=at;i<at+width;i++)energy+=samples[i]*samples[i];energy/=width;if(energy<quiet){quiet=energy;cut=at+width/2;}}
  return Math.max(1,cut);
 }
}
