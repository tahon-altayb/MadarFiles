package com.madar.files;
import java.util.*;
/** Pad speech boundaries and join nearby utterances without inventing speech in long gaps. */
final class SpeechRegions {
 /** Join clauses across decoder input-buffer boundaries; keep inference bounded. */
 static List<int[]> continuous(int[] intervals,int length){
  List<int[]> out=new ArrayList<>();int pad=4800,max=24*16000;
  for(int i=0;i+1<intervals.length;i+=2){
   int start=Math.max(0,Math.min(length,intervals[i])-pad),end=(int)Math.min(length,(long)intervals[i+1]+pad);
   if(end<=start)continue;
   if(!out.isEmpty()){
    int[] previous=out.get(out.size()-1);
    if(start-previous[1]<=32000&&(long)Math.max(previous[1],end)-previous[0]<=max){previous[1]=Math.max(previous[1],end);continue;}
    // Divide only shared padding, so adjacent windows do not replay samples.
    if(start<previous[1]){int boundary=start+(previous[1]-start)/2;previous[1]=boundary;start=boundary;}
   }
   for(int at=start;at<end;){int next=(int)Math.min(end,(long)at+max);out.add(new int[]{at,next});at=next;}
  }
  return out;
 }
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
