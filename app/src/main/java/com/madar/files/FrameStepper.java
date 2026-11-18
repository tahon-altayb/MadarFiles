package com.madar.files;
import android.media.MediaExtractor;
import java.io.*;
/** Step on real presentation timestamps, including variable-frame-rate sources. */
final class FrameStepper {
 static double step(File source,double seconds,int direction)throws Exception{
  if(!Double.isFinite(seconds)||seconds<0||(direction!=1&&direction!=-1))throw new IOException("موضع إطار غير صالح");
  MediaExtractor extractor=new MediaExtractor();try{extractor.setDataSource(source.getPath());int track=-1;for(int i=0;i<extractor.getTrackCount();i++)if(extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME).startsWith("video/")){track=i;break;}if(track<0)throw new IOException("لا يوجد مسار فيديو");extractor.selectTrack(track);long target=Math.round(seconds*1000000),best=direction>0?Long.MAX_VALUE:-1;extractor.seekTo(Math.max(0,target-(direction<0?1:0)),MediaExtractor.SEEK_TO_PREVIOUS_SYNC);long deadline=android.os.SystemClock.elapsedRealtime()+2000;int future=0;
   for(int i=0;i<10000&&android.os.SystemClock.elapsedRealtime()<deadline;i++){long pts=extractor.getSampleTime();if(pts<0)break;if(direction>0&&pts>target+100)best=Math.min(best,pts);if(direction<0&&pts<target-100)best=Math.max(best,pts);if(pts>target+2000000&&++future>32)break;if(!extractor.advance())break;}
   return best<0||best==Long.MAX_VALUE?seconds:best/1000000.0;
  }finally{extractor.release();}
 }
}
