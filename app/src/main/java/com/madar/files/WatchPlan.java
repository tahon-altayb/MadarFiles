package com.madar.files;

/** Prepare a continuous prefix from zero, then extend it without leaving history holes. */
final class WatchPlan {
 private final PcmRanges ready;private long end;private final boolean cloud;private int forwardIndex;
 WatchPlan(long startUs,long endUs,boolean cloud,PcmRanges ready){end=frame(endUs);this.cloud=cloud;this.ready=ready;if(startUs<0||endUs<=startUs)throw new IllegalArgumentException("Watch duration");}
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
 private WatchWindows window(long[] gap,int index){return WatchWindows.contextFor(WatchWindows.forWatch(micros(gap[0]),micros(gap[1]),cloud,index),micros(end));}
}
