package com.madar.files;

/** Current scene first, then missing history and forward windows; user seeks take priority. */
final class WatchPlan {
 private final PcmRanges ready;private final long start;private long end;private final boolean cloud;private boolean first=true,historyTurn=true;private int forwardIndex;
 WatchPlan(long startUs,long endUs,boolean cloud,PcmRanges ready){this.start=frame(startUs);end=frame(endUs);this.cloud=cloud;this.ready=ready;if(start<0||end<=start)throw new IllegalArgumentException("Watch duration");}
 static long frame(long us){return Math.round(us*44100.0/1000000);}
 static long micros(long frame){return Math.round(frame*1000000.0/44100);}
 void trimEnd(long lastFrame){end=Math.min(end,Math.max(0,lastFrame));}
 WatchWindows next(long requestedFrame){
  if(first){first=false;long[] gap=ready.firstGap(start,end);if(gap!=null)return window(gap,forwardIndex++);}
  if(requestedFrame>=0&&requestedFrame<end){long[] gap=ready.firstGap(requestedFrame,end);if(gap!=null&&gap[0]==requestedFrame)return window(gap,0);}
  long[] history=ready.firstGap(0,Math.min(start,end)),forward=ready.firstGap(Math.min(start,end),end);
  if(history!=null&&(historyTurn||forward==null)){historyTurn=false;return window(history,0);}
  if(forward!=null){historyTurn=true;return window(forward,forwardIndex++);}
  return null;
 }
 private WatchWindows window(long[] gap,int index){return WatchWindows.forWatch(micros(gap[0]),micros(gap[1]),cloud,index);}
}
