package com.madar.files;

/** A measured production budget, in media seconds. No device/server speed is assumed. */
final class WatchBuffer {
 private final double duration;private final double initialReserve;private final boolean waitForCycle,progressive;private long firstAt=-1,lastAt;private double lastReady,media,wall,slowRatio,gap=0,cycleRatio;private int samples;private double plannedGap;
 WatchBuffer(double duration){this(duration,false);}
 WatchBuffer(double duration,boolean waitForCycle){this(duration,waitForCycle,false);}
 WatchBuffer(double duration,boolean waitForCycle,boolean progressive){this(duration,waitForCycle,progressive,0);}
 WatchBuffer(double duration,boolean waitForCycle,boolean progressive,double initialReserve){this.duration=Math.max(0,duration);this.waitForCycle=waitForCycle;this.progressive=progressive;this.initialReserve=initialReserve;}
 synchronized double startupRequired(double played,double playbackSpeed,long now){
  return Math.min(Math.max(0,duration-played),Math.max(required(played,playbackSpeed,now),initialReserve));
 }
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
  double limit=remaining;
  if(progressive){
   // Start with a usable prefix and replenish at a bounded target if GPU or upload is slower.
   double target=rate==0?6*s:Math.max(6*s,Math.min(18*s,gap*s));
   return Math.min(limit,target);
  }
  // For the legacy policy, budget the total predicted production deficit.
  if(rate==0)return Math.min(limit,30*s);
  double safety=Math.max(Math.max(20*s,Math.min(120,gap*1.5)*s),plannedGap*1.25*s);
  return Math.min(limit,Math.max(safety,remaining*(1-rate/s)+safety));
 }
 static double seekRequired(double remaining,double playbackSpeed){return Math.min(Math.max(0,remaining),3*Math.max(.25,playbackSpeed));}
 synchronized long waitSeconds(double played,double ready,double playbackSpeed,long now){
  double rate=speed(now);if(rate==0)return -1;
  // The reserve already contains the safety margin; estimate its fill time
  // from measured production, without applying that margin a second time.
  return (long)Math.ceil(Math.max(0,required(played,playbackSpeed,now)-(ready-played))/(rate/.8));
 }
 synchronized String describe(double played,double ready,double playbackSpeed,long now,boolean starting){
  if(starting&&initialReserve>0){
   double target=startupRequired(played,playbackSpeed,now);
   return "احتياطي المشاهدة "+format(Math.max(0,ready-played))+" / "+format(target);
  }
  double rate=speed(now);if(rate==0)return "قياس سرعة الفصل · تجهيز احتياطي للمشاهدة";
  long wait=waitSeconds(played,ready,playbackSpeed,now);
  return "سرعة الفصل "+String.format(java.util.Locale.US,"%.2f",rate/.8)+"× · الاحتياطي "+format(Math.max(0,ready-played))+" · انتظار مقدّر "+format(wait);
 }
 private static String format(double seconds){long n=(long)Math.ceil(seconds);return n/60+":"+String.format(java.util.Locale.US,"%02d",n%60);}
}
