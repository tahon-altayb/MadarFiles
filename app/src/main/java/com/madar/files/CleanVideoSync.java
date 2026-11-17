package com.madar.files;

/** Follow the separated audio clock without flushing the video decoder. */
final class CleanVideoSync {
 static float speed(float requested,double videoAheadSeconds){
  if(Math.abs(videoAheadSeconds)<.04)return requested;
  double adjustment=Math.max(-.05,Math.min(.05,-videoAheadSeconds*.1));
  return (float)(requested*(1+adjustment));
 }
 private CleanVideoSync(){}
}
