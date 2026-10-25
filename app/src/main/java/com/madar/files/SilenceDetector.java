package com.madar.files;
import org.json.*;
import java.io.*;
/** Detects low-amplitude intervals, not linguistic pauses; keeps padding around speech. */
final class SilenceDetector {
 static JSONArray keep(JSONObject waveform,double thresholdDb,double minimum,double padding)throws Exception{
  if(!waveform.optBoolean("hasAudio"))throw new IOException("لا يوجد صوت لتحليل السكتات");
  if(!Double.isFinite(thresholdDb)||thresholdDb< -60||thresholdDb> -15||!Double.isFinite(minimum)||minimum<.3||minimum>3||!Double.isFinite(padding)||padding<0||padding>.3)throw new IOException("إعدادات كشف السكتات غير صالحة");
  JSONArray peaks=waveform.getJSONArray("peaks");double duration=waveform.getDouble("duration"),bin=duration/peaks.length(),threshold=Math.pow(10,thresholdDb/20);if(peaks.length()==0||!Double.isFinite(duration)||duration<=0)throw new IOException("لم ينتج التحليل صوتًا صالحًا");
  JSONArray ranges=new JSONArray();double cursor=0;for(int i=0;i<peaks.length();){if(peaks.getDouble(i)>threshold){i++;continue;}int start=i;while(i<peaks.length()&&peaks.getDouble(i)<=threshold)i++;double a=start*bin,b=i*bin;if(b-a<minimum)continue;double cutStart=Math.min(b,a+padding),cutEnd=Math.max(cutStart,b-padding);if(cutEnd-cutStart<.05)continue;if(cutStart-cursor>=.05)ranges.put(new JSONArray().put(cursor).put(cutStart));cursor=cutEnd;}
  if(duration-cursor>=.05)ranges.put(new JSONArray().put(cursor).put(duration));if(ranges.length()==0)throw new IOException("المقطع كله منخفض الصوت؛ خفّض حساسية الكشف قبل القص");if(ranges.length()>50)throw new IOException("اكتُشفت سكتات كثيرة؛ زد مدة السكتة أو اختر جزءًا أقصر (الحد 50 جزءًا)");return ranges;
 }
 static double peak(JSONObject waveform,JSONArray ranges)throws Exception{JSONArray peaks=waveform.getJSONArray("peaks");double duration=waveform.getDouble("duration"),peak=0;for(int i=0;i<peaks.length();i++){double a=i*duration/peaks.length(),b=(i+1)*duration/peaks.length();for(int j=0;j<ranges.length();j++){JSONArray range=ranges.getJSONArray(j);if(a<range.getDouble(1)&&b>range.getDouble(0)){peak=Math.max(peak,peaks.getDouble(i));break;}}}return peak;}
}
