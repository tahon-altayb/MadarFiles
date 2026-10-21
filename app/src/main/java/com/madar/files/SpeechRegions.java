package com.madar.files;
import java.util.*;
/** Pad speech boundaries and join nearby utterances without inventing speech in long gaps. */
final class SpeechRegions {
 static List<int[]> padded(int[] intervals,int length){
  List<int[]> out=new ArrayList<>();int pad=4800;
  for(int i=0;i+1<intervals.length;i+=2){
   int start=Math.max(0,Math.min(length,intervals[i])-pad),end=Math.min(length,intervals[i+1]+pad);
   if(end<=start)continue;
   if(!out.isEmpty()&&start-out.get(out.size()-1)[1]<=32000){int[] previous=out.get(out.size()-1);previous[1]=Math.max(previous[1],end);}
   else out.add(new int[]{start,end});
  }
  return out;
 }
}
