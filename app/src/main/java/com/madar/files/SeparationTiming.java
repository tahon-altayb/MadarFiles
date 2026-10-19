package com.madar.files;
/** Monotonic chunk timing. Model loading, decode and final mux are separate stages. */
final class SeparationTiming {
 private double secondsPerMediaSecond; private int samples;
 void record(double mediaSeconds,double wallSeconds){if(mediaSeconds<=0||wallSeconds<=0)return;double value=wallSeconds/mediaSeconds;secondsPerMediaSecond=samples++==0?value:.7*secondsPerMediaSecond+.3*value;}
 long remainingSeconds(double remainingMedia){return samples==0?-1:(long)Math.ceil(Math.max(0,remainingMedia)*secondsPerMediaSecond);}
 double speed(){return samples==0?0:1/secondsPerMediaSecond;}
 String describe(double remainingMedia){long seconds=remainingSeconds(remainingMedia);if(seconds<0)return "جارٍ قياس سرعة الفصل";if(seconds==0)return "اكتمل الفصل · جارٍ تجهيز الصوت للحفظ";return "المتبقي للفصل تقريبًا "+(seconds>=60?seconds/60+" د ":"")+seconds%60+" ث · السرعة "+String.format(java.util.Locale.US,"%.2f",speed())+"×";}
}
