package com.madar.files;

/** A measured production budget, in media seconds. No device/server speed is assumed. */
final class WatchBuffer {
 private final double duration;private final boolean waitForCycle;private long firstAt=-1,lastAt;private double lastReady,media,wall,slowRatio,gap=0,cycleRatio;private int samples;private double plannedGap;
 WatchBuffer(double duration){this(duration,false);}
 WatchBuffer(double duration,boolean waitForCycle){this.duration=Math.max(0,duration);this.waitForCycle=waitForCycle;}
 synchronized void planGap(double seconds){plannedGap=Double.isFinite(seconds)?Math.max(0,seconds):0;}
 synchronized void completeCycle(double seconds,double elapsed){if(seconds>0&&elapsed>0)cycleRatio=cycleRatio==0?elapsed/seconds:.6*cycleRatio+.4*elapsed/seconds;}
 synchronized void commit(double ready,long now){
  if(firstAt<0){firstAt=lastAt=now;lastReady=ready;return;}
  double produced=ready-lastReady,seconds=(now-lastAt)/1000.0;
  // Commit a measured audio quantum, not a sample-sized context tail.
  // Tiny tails used to turn a 2 s REST round trip into a near-zero speed.
  if(produced<3.999||seconds<=0)return;
  media+=produced;wall+=seconds;double ratio=seconds/produced;
  slowRatio=samples++==0?ratio:Math.max(ratio,slowRatio*.85+ratio*.15);
  gap=Math.max(gap,seconds);lastAt=now;lastReady=ready;
 }
 synchronized double speed(long now){
  if(samples<(waitForCycle&&cycleRatio>0?1:2)||media<(waitForCycle&&cycleRatio>0?4:8)||wall<=0||(waitForCycle&&cycleRatio==0))return 0;
  // Include time spent decoding/uploading the next window, and use the slower
  // of the whole observed pipeline and recent batches, with a 20% margin.
  double stalled=Math.max(0,(now-lastAt)/1000.0);
  return .8/Math.max(cycleRatio,Math.max(slowRatio,(wall+stalled)/media));
 }
 synchronized double required(double played,double playbackSpeed,long now){
  double remaining=Math.max(0,duration-played),rate=speed(now),s=Math.max(.25,playbackSpeed);
  if(rate==0)return remaining; // Measure first; never start from an arbitrary tiny prefix.
  double safety=Math.max(Math.max(20*s,Math.min(120,gap*1.5)*s),plannedGap*1.25*s);
  return Math.min(remaining,Math.max(safety,remaining*(1-rate/s)+safety));
 }
 synchronized long waitSeconds(double played,double ready,double playbackSpeed,long now){
  double rate=speed(now);if(rate==0)return -1;
  // The reserve already contains the safety margin; estimate its fill time
  // from measured production, without applying that margin a second time.
  return (long)Math.ceil(Math.max(0,required(played,playbackSpeed,now)-(ready-played))/(rate/.8));
 }
 synchronized String describe(double played,double ready,double playbackSpeed,long now){
  double rate=speed(now);if(rate==0)return "قياس سرعة الفصل · تجهيز احتياطي للمشاهدة";
  long wait=waitSeconds(played,ready,playbackSpeed,now);
  return "سرعة الفصل "+String.format(java.util.Locale.US,"%.2f",rate/.8)+"× · الاحتياطي "+format(Math.max(0,ready-played))+" · انتظار مقدّر "+format(wait);
 }
 private static String format(double seconds){long n=(long)Math.ceil(seconds);return n/60+":"+String.format(java.util.Locale.US,"%02d",n%60);}
}
