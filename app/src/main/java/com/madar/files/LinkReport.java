package com.madar.files;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «تقرير آخر تحميل»: how long each stage of a link took (the engine's start and update check, reading the page,
 * each downloaded part with its size and speed, joining picture and sound, saving), so a slow download on a tablet
 * says where its time went. It holds no link, title or file name: only the site's engine name, format numbers,
 * sizes and times.
 */
final class LinkReport {
 private static final String PREFS="link_report";
 private final Context context;private final long began=SystemClock.elapsedRealtime();private final String startedAt=java.time.Instant.now().toString(),kind;
 private final List<String> lines=new ArrayList<>();private long partAt=-1,partBytes=0,downloadAt=-1,downloaded=0;private int parts=0;private boolean reading=false;
 /** The reading of qualities just before (within half an hour) heads the download's report: together they are the whole wait. */
 private final String before;
 LinkReport(Context c,String kind){
  context=c.getApplicationContext();this.kind=kind;String previous=last(context);long at=context.getSharedPreferences(PREFS,0).getLong("at",0);
  before=!kind.equals("قراءة الجودات")&&previous.contains("(قراءة الجودات)")&&System.currentTimeMillis()-at<30L*60000?previous+"\n\n":"";
 }
 private long seconds(){return (SystemClock.elapsedRealtime()-began)/1000;}
 synchronized void mark(String code){lines.add(seconds()+"s · "+code);save();}
 synchronized void measure(String code,long ms){lines.add(seconds()+"s · "+code+" · "+(ms/1000)+"."+(ms%1000/100)+" ث");save();}
 private static final Pattern EXTRACTOR=Pattern.compile("^\\[([A-Za-z][A-Za-z0-9:_-]{1,30})\\] "),FORMATS=Pattern.compile("Downloading \\d+ format\\(s\\): ([A-Za-z0-9+_-]{1,40})"),SIZE=Pattern.compile("100(?:\\.0)?% of\\s+~?\\s*([0-9.]+)\\s*([KMG]i?B)");
 /** One line of the engine's output: the stages are read from it (nothing of the line itself is kept beyond the names above). */
 synchronized void line(String value){
  if(value==null)return;String line=value.trim();if(line.isEmpty())return;Matcher m=EXTRACTOR.matcher(line);if(!m.find())return;String tag=m.group(1);
  switch(tag){
   case "info":{Matcher f=FORMATS.matcher(line);if(f.find()){reading=false;lines.add(seconds()+"s · PAGE_READ · الصيغ "+f.group(1));save();}return;}
   case "download":
    if(line.startsWith("[download] Destination:")){if(downloadAt<0)downloadAt=SystemClock.elapsedRealtime();partAt=SystemClock.elapsedRealtime();partBytes=0;parts++;lines.add(seconds()+"s · PART_"+parts+"_START");save();return;}
    Matcher s=SIZE.matcher(line);if(s.find()&&partAt>=0){double n=Double.parseDouble(s.group(1));String unit=s.group(2);long bytes=(long)(n*(unit.startsWith("G")?1073741824L:unit.startsWith("M")?1048576L:1024L));partBytes=bytes;downloaded+=bytes;long ms=Math.max(1,SystemClock.elapsedRealtime()-partAt);
     lines.add(seconds()+"s · PART_"+parts+"_DONE · "+mb(bytes)+" · "+(ms/1000)+" ث · "+speed(bytes,ms));partAt=-1;save();}
    else if(line.contains("has already been downloaded")){lines.add(seconds()+"s · PART_CACHED");save();}
    return;
   case "Merger":lines.add(seconds()+"s · MERGE_START");save();return;
   case "FixupM4a":case "FixupM3u8":case "FixupDuplicateMoov":case "FixupTimestamp":case "FixupStretched":lines.add(seconds()+"s · FIX_"+tag.substring(5).toUpperCase(Locale.ROOT));save();return;
   case "ExtractAudio":lines.add(seconds()+"s · AUDIO_CONVERT");save();return;
   case "hlsnative":case "dashsegments":lines.add(seconds()+"s · PIECES_"+tag.toUpperCase(Locale.ROOT));save();return;
   default:
    if(!reading&&parts==0&&!tag.equals("debug")){reading=true;lines.add(seconds()+"s · PAGE_READING · "+tag.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9:_-]",""));save();}
  }
 }
 synchronized void finished(long bytes){
  if(downloadAt>=0){long ms=Math.max(1,SystemClock.elapsedRealtime()-downloadAt);lines.add(seconds()+"s · DOWNLOAD_ALL · "+mb(downloaded)+" · "+speed(downloaded,ms));}
  lines.add(seconds()+"s · SAVED · "+mb(bytes));lines.add("المدة الكلية: "+seconds()+" ثانية");save();
 }
 synchronized void failed(Throwable error){String name=error==null?"":error.getClass().getSimpleName();lines.add(seconds()+"s · FAILED"+(name.matches("[A-Za-z]{1,40}")?" · "+name:""));save();}
 private static String mb(long bytes){return String.format(Locale.ROOT,"%.1f ميجا",bytes/1048576.0);}
 private static String speed(long bytes,long ms){return String.format(Locale.ROOT,"%.2f ميجا/ث",bytes/1048576.0/(ms/1000.0));}
 private String network(){
  try{ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);NetworkCapabilities n=manager.getNetworkCapabilities(manager.getActiveNetwork());
   if(n==null)return "غير متصل";String type=n.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"WIFI":n.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"CELLULAR":"OTHER";
   boolean v6=false;android.net.LinkProperties link=manager.getLinkProperties(manager.getActiveNetwork());
   if(link!=null)for(android.net.LinkAddress address:link.getLinkAddresses()){java.net.InetAddress ip=address.getAddress();if(ip instanceof java.net.Inet6Address&&!ip.isLinkLocalAddress()&&!ip.isSiteLocalAddress()&&!ip.isLoopbackAddress())v6=true;}
   return type+" · حتى "+(n.getLinkDownstreamBandwidthKbps()/1000)+" ميجابت حسب النظام · IPv6 "+(v6?"موجود":"غير موجود")+" · التحميل عبر IPv4";
  }catch(Exception e){return "غير معروفة";}
 }
 private void save(){
  String build;try{android.content.pm.PackageInfo p=context.getPackageManager().getPackageInfo(context.getPackageName(),0);build=p.versionName+" ("+p.getLongVersionCode()+")";}catch(Exception e){build="?";}
  StringBuilder text=new StringBuilder(before+"مدار "+build+" · تقرير آخر تحميل ("+kind+")\nوقت البدء: "+startedAt+"\nالشبكة: "+network()+"\nAndroid API: "+android.os.Build.VERSION.SDK_INT+" · المعالج: "+Runtime.getRuntime().availableProcessors()+" أنوية\n");
  for(String line:lines)text.append(line).append('\n');
  context.getSharedPreferences(PREFS,0).edit().putString("last",text.toString().trim()).putLong("at",System.currentTimeMillis()).apply();
 }
 static String last(Context c){return c.getSharedPreferences(PREFS,0).getString("last","");}
}
