package com.madar.files;

import java.util.ArrayList;

/** Maps AudioTrack output frames to real PCM; drain padding never advances media time. */
final class AudioContentClock {
 private final ArrayList<long[]> padding=new ArrayList<>();
 private long written;
 void reset(){padding.clear();written=0;}
 void contentWritten(int frames){if(frames<0)throw new IllegalArgumentException("frames");written+=frames;}
 void paddingWritten(int frames){
  if(frames<0)throw new IllegalArgumentException("frames");if(frames==0)return;
  if(!padding.isEmpty()&&padding.get(padding.size()-1)[1]==written)padding.get(padding.size()-1)[1]+=frames;
  else padding.add(new long[]{written,written+frames});
  written+=frames;
 }
 long contentAt(long outputFrame){
  long content=outputFrame;
  for(long[] gap:padding){if(outputFrame<=gap[0])break;content-=Math.min(outputFrame,gap[1])-gap[0];}
  return Math.max(0,content);
 }
}
