package com.madar.files;

/** Bounded overlapping decode windows; only their contiguous cores are played. */
final class WatchWindows {
 static final long SECOND=1000000L;
 final long coreStart,coreEnd,decodeStart,decodeEnd;
 WatchWindows(long next,long end,boolean cloud){
  long limit=(cloud?600:60)*SECOND,core=limit-8*SECOND;
  coreStart=next;coreEnd=Math.min(end,next+core);decodeStart=Math.max(0,next-4*SECOND);decodeEnd=Math.min(end,coreEnd+4*SECOND);
  if(next<0||end<=next||decodeEnd-decodeStart>limit)throw new IllegalArgumentException("نافذة صوت غير صالحة");
 }
}
