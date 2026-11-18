package com.madar.files;

import java.util.ArrayList;

/** Committed PCM intervals. A sparse file's unwritten holes are never playable. */
final class PcmRanges {
 private final ArrayList<long[]> ranges=new ArrayList<>();
 synchronized void add(long start,long end){
  if(start<0||end<start)throw new IllegalArgumentException("PCM range");if(end==start)return;
  int i=0;while(i<ranges.size()&&ranges.get(i)[1]<start)i++;
  while(i<ranges.size()&&ranges.get(i)[0]<=end){long[] r=ranges.remove(i);start=Math.min(start,r[0]);end=Math.max(end,r[1]);}
  ranges.add(i,new long[]{start,end});
 }
 synchronized long endFrom(long frame){for(long[] r:ranges){if(frame<r[0])break;if(frame<r[1])return r[1];}return frame;}
 synchronized long total(){long n=0;for(long[] r:ranges)n+=r[1]-r[0];return n;}
 synchronized long[] firstGap(long start,long end){
  long at=Math.max(0,start);for(long[] r:ranges){if(r[1]<=at)continue;if(r[0]>=end)break;if(r[0]>at)return new long[]{at,Math.min(end,r[0])};at=Math.max(at,r[1]);if(at>=end)return null;}
  return at<end?new long[]{at,end}:null;
 }
}
