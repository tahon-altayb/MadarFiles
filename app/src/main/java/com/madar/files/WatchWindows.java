package com.madar.files;

/** Bounded overlapping decode windows; only their contiguous cores are played. */
final class WatchWindows {
 static final long SECOND=1000000L;
 final long coreStart,coreEnd,decodeStart,decodeEnd;
 WatchWindows(long next,long end,boolean cloud){
  this(next,end,cloud?120:52);
 }
 static WatchWindows forWatch(long next,long end,boolean cloud,boolean first,double reserve){return new WatchWindows(next,end,cloud?(first?24:reserve<40?32:reserve<120?64:120):52);}
 private WatchWindows(long next,long end,int seconds){
  long core=seconds*SECOND,limit=core+8*SECOND;
  coreStart=next;coreEnd=Math.min(end,next+core);decodeStart=Math.max(0,next-4*SECOND);decodeEnd=Math.min(end,coreEnd+4*SECOND);
  if(next<0||end<=next||decodeEnd-decodeStart>limit)throw new IllegalArgumentException("نافذة صوت غير صالحة");
 }
}
