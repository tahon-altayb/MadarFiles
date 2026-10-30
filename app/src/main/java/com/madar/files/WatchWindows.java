package com.madar.files;

/** Bounded overlapping decode windows; only contiguous cores reach the player. */
final class WatchWindows {
 static final long SECOND=1000000L;
 static final int[] CLOUD_SECONDS={40,60,90,150,240,360,540,600};
 final long coreStart,coreEnd,decodeStart,decodeEnd;
 WatchWindows(long next,long end,boolean cloud){this(next,end,cloud?120:52);}
 static WatchWindows forWatch(long next,long end,boolean cloud,int index){return new WatchWindows(next,end,cloud?CLOUD_SECONDS[Math.min(Math.max(index,0),CLOUD_SECONDS.length-1)]:52);}
 // Compatibility for old callers/tests; production tracks the ordinal, independent of reserve.
 static WatchWindows forWatch(long next,long end,boolean cloud,boolean first,double reserve){return forWatch(next,end,cloud,first?0:1);}
 private WatchWindows(long next,long end,int seconds){
  long core=seconds*SECOND,limit=core+8*SECOND;
  coreStart=next;coreEnd=Math.min(end,next+core);decodeStart=Math.max(0,next-4*SECOND);decodeEnd=Math.min(end,coreEnd+4*SECOND);
  if(next<0||end<=next||decodeEnd-decodeStart>limit)throw new IllegalArgumentException("نافذة صوت غير صالحة");
 }
 static WatchWindows contextFor(WatchWindows core,long mediaEnd){return new WatchWindows(core,mediaEnd);}
 private WatchWindows(WatchWindows core,long mediaEnd){
  coreStart=core.coreStart;coreEnd=core.coreEnd;decodeStart=core.decodeStart;decodeEnd=Math.min(mediaEnd,coreEnd+4*SECOND);
  if(mediaEnd<coreEnd)throw new IllegalArgumentException("نافذة خارج المقطع");
 }

}
