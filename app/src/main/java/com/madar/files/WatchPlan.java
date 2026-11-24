package com.madar.files;

/** Prepare a continuous prefix from zero, then extend it without leaving history holes. */
final class WatchPlan {
 private final PcmRanges ready;private long end;private final boolean cloud,audiovisual;private int forwardIndex;
 WatchPlan(long startUs,long endUs,boolean cloud,PcmRanges ready){this(startUs,endUs,cloud,ready,false);}
 WatchPlan(long startUs,long endUs,boolean cloud,PcmRanges ready,boolean audiovisual){end=frame(endUs);this.cloud=cloud;this.ready=ready;this.audiovisual=audiovisual;if(startUs<0||endUs<=startUs)throw new IllegalArgumentException("Watch duration");}
 static boolean needsStartChoice(long positionMs){return positionMs>150000;}
 static long frame(long us){return Math.round(us*44100.0/1000000);}
 static long micros(long frame){return Math.round(frame*1000000.0/44100);}
 void trimEnd(long lastFrame){end=Math.min(end,Math.max(0,lastFrame));}
 WatchWindows next(long requestedFrame){
  // A seek must not divert production away from the prefix needed by playback
  // or create another hole behind the requested position.
  long[] gap=ready.firstGap(0,end);
  return gap==null?null:window(gap,forwardIndex++);
 }
 /**
  * The window next() will return once `current` is committed in order (same sizes, same index), so it can be
  * prepared while `current` separates. Null at the end. The caller must still compare it with next().
  */
 WatchWindows following(WatchWindows current){
  long start=frame(current.coreEnd);
  if(start>=end)return null;
  return window(new long[]{start,end},forwardIndex);
 }
 static boolean same(WatchWindows a,WatchWindows b){return a!=null&&b!=null&&a.coreStart==b.coreStart&&a.coreEnd==b.coreEnd&&a.decodeStart==b.decodeStart&&a.decodeEnd==b.decodeEnd;}
 private WatchWindows window(long[] gap,int index){return WatchWindows.contextFor(audiovisual?WatchWindows.audiovisual(micros(gap[0]),micros(gap[1]),index):WatchWindows.forWatch(micros(gap[0]),micros(gap[1]),cloud,index),micros(end));}
}
