package com.madar.files;

/** Current position first; retain analysed history and finish the rest without holes. */
final class CensorPlan {
 static final long CORE_MS=8000,CONTEXT_MS=2000;
 interface Coverage {boolean has(long startMs);}
 static long next(long requested,long duration,Coverage coverage){
  if(duration<=0)return -1;long first=Math.max(0,Math.min(duration-1,requested))/CORE_MS*CORE_MS;
  for(long at=first;at<Math.min(duration,first+64000);at+=CORE_MS)if(!coverage.has(at))return at;
  for(long at=0;at<first;at+=CORE_MS)if(!coverage.has(at))return at;
  for(long at=first+64000;at<duration;at+=CORE_MS)if(!coverage.has(at))return at;
  return -1;
 }
 private CensorPlan(){}
}
