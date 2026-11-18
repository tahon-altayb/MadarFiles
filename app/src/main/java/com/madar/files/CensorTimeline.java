package com.madar.files;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Verified, media-time masks. Missing/invalid data is NEVER equivalent to an empty mask. */
final class CensorTimeline implements AutoCloseable {
 static final String FINGERPRINT="60d4a28d268e5373b95543618a38021d54930aeef46febe42a0bf8dbc5ed3139";
 static final long MARGIN_US=100000;
 static final int MAX_BYTES=4000000;
 private final String source;private final File directory;
 private final TreeMap<Long,Block> blocks=new TreeMap<>();
 private final TreeMap<Long,Long> ready=new TreeMap<>();
 private final LinkedHashMap<Long,Clip> cache=new LinkedHashMap<>(16,.75f,true);
 private final Set<Long> loading=new HashSet<>();
 private final ExecutorService reader=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"madar-censor-masks");t.setDaemon(true);return t;});
 private boolean closed;volatile String failure="";
 static final class Frame {final long at,end;final double[][] boxes;Frame(long a,long e,double[][] b){at=a;end=e;boxes=b;}}
 static final class Clip {final long start,end;final Frame[] frames;Clip(long s,long e,Frame[] f){start=s;end=e;frames=f;}}
 private static final class Block {final long start,end;final File path;final byte[] digest;Block(long s,long e,File p,byte[] d){start=s;end=e;path=p;digest=d;}}
 CensorTimeline(File directory,String source)throws IOException{this.directory=directory;this.source=source;if(!source.matches("[a-f0-9]{32}")||!directory.isDirectory()&&!directory.mkdirs())throw new IOException("تعذر تجهيز بيانات الحجب");}
 static long integer(JSONObject value,String key)throws JSONException,IOException{Object n=value.get(key);if(!(n instanceof Integer)&&!(n instanceof Long))throw new IOException("Colab · CENSOR_SCHEMA");return ((Number)n).longValue();}
 private static long integer(JSONArray value,int key)throws JSONException,IOException{Object n=value.get(key);if(!(n instanceof Integer)&&!(n instanceof Long))throw new IOException("Colab · CENSOR_SCHEMA");return ((Number)n).longValue();}
 static Clip parse(JSONObject value,String source,long expectedStart,long expectedEnd)throws Exception{
  if(value.optInt("protocol")!=1||!FINGERPRINT.equals(value.optString("fingerprint"))||!source.equals(value.optString("source"))||!"cuda".equals(value.optString("provider")))throw new IOException("Colab · CENSOR_SCHEMA");
  long start=integer(value,"core_start_us"),end=integer(value,"core_end_us"),decode=integer(value,"decode_start_us");
  if(start!=expectedStart||end!=expectedEnd||start<0||end<=start||end-start>CensorPlan.CORE_MS*1000||decode<0||decode>start||start-decode>2000000)throw new IOException("Colab · CENSOR_SCHEMA");
  JSONArray input=value.getJSONArray("frames");if(input.length()==0||input.length()>2401)throw new IOException("Colab · CENSOR_SCHEMA");Frame[] frames=new Frame[input.length()];long previous=-1;
  for(int i=0;i<input.length();i++){
   JSONArray f=input.getJSONArray(i);if(f.length()!=3)throw new IOException("Colab · CENSOR_SCHEMA");long at=integer(f,0),until=integer(f,1);
   if(at<decode||until<=at||until-at>1100000||at>expectedEnd+2000000||i>0&&at!=previous)throw new IOException("Colab · CENSOR_SCHEMA");previous=until;
   JSONArray boxes=f.getJSONArray(2);if(boxes.length()>64)throw new IOException("Colab · CENSOR_SCHEMA");double[][] masks=new double[boxes.length()][5];Set<Long> ids=new HashSet<>();
   for(int j=0;j<boxes.length();j++){
    JSONArray b=boxes.getJSONArray(j);if(b.length()!=5)throw new IOException("Colab · CENSOR_SCHEMA");long id=integer(b,0);if(id< -1||id>1000000||!ids.add(id))throw new IOException("Colab · CENSOR_SCHEMA");masks[j][0]=id;
    for(int k=1;k<5;k++){Object n=b.get(k);if(!(n instanceof Number))throw new IOException("Colab · CENSOR_SCHEMA");double v=((Number)n).doubleValue();if(!Double.isFinite(v)||v<0||v>1)throw new IOException("Colab · CENSOR_SCHEMA");masks[j][k]=v;}
    if(masks[j][3]<=masks[j][1]||masks[j][4]<=masks[j][2])throw new IOException("Colab · CENSOR_SCHEMA");
   }frames[i]=new Frame(at,until,masks);
  }
  if(frames[0].at>start||frames[frames.length-1].end<end)throw new IOException("Colab · CENSOR_SCHEMA");return new Clip(start,end,frames);
 }
 void install(byte[] raw,long startUs,long endUs)throws Exception{
  synchronized(this){if(closed)throw new IOException("أُغلقت بيانات الحجب");}if(raw.length==0||raw.length>MAX_BYTES)throw new IOException("Colab · CENSOR_OUTPUT_LENGTH");
  Clip clip=parse(new JSONObject(new String(raw,StandardCharsets.UTF_8)),source,startUs,endUs);
  if(directory.getUsableSpace()<raw.length+16L*1024*1024)throw new IOException("المساحة لا تكفي لحفظ تقدم الحجب");
  File path=new File(directory,startUs+".json"),temporary=new File(directory,startUs+".tmp");
  try(FileOutputStream out=new FileOutputStream(temporary)){out.write(raw);out.getFD().sync();}
  byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(raw);
  synchronized(this){
   if(closed){temporary.delete();throw new IOException("أُغلقت بيانات الحجب");}
   if(!temporary.renameTo(path)){temporary.delete();throw new IOException("تعذر حفظ بيانات الحجب");}
   blocks.put(startUs,new Block(startUs,endUs,path,digest));remember(clip);
   long first=startUs,last=endUs;Map.Entry<Long,Long> previous=ready.floorEntry(first);if(previous!=null&&previous.getValue()>=first){first=previous.getKey();last=Math.max(last,previous.getValue());ready.remove(previous.getKey());}
   for(Map.Entry<Long,Long> next=ready.ceilingEntry(first);next!=null&&next.getKey()<=last;next=ready.ceilingEntry(first)){last=Math.max(last,next.getValue());ready.remove(next.getKey());}ready.put(first,last);
  }
 }
 private void remember(Clip clip){cache.put(clip.start,clip);while(cache.size()>8)cache.remove(cache.keySet().iterator().next());}
 synchronized boolean has(long startMs){return !closed&&blocks.containsKey(startMs*1000);}
 synchronized long contiguousEnd(long positionMs){if(closed)return positionMs;Map.Entry<Long,Long> first=ready.floorEntry(positionMs*1000);return first==null||first.getValue()<=positionMs*1000?positionMs:first.getValue()/1000;}
 private void request(Block block){
  if(closed||cache.containsKey(block.start)||!loading.add(block.start))return;
  reader.execute(()->{Clip result=null;String error="";try{
   if(block.path.length()>MAX_BYTES)throw new IOException();byte[] raw=java.nio.file.Files.readAllBytes(block.path.toPath());if(!java.security.MessageDigest.isEqual(block.digest,java.security.MessageDigest.getInstance("SHA-256").digest(raw)))throw new IOException();result=parse(new JSONObject(new String(raw,StandardCharsets.UTF_8)),source,block.start,block.end);
  }catch(Exception bad){error="تعذر استرجاع بيانات الحجب؛ بقيت المنطقة مغطاة";}
   synchronized(CensorTimeline.this){loading.remove(block.start);if(!closed){if(result!=null)remember(result);else failure=error;}}
  });
 }
 /** GL thread never performs disk I/O. Main/GL misses schedule one bounded load. */
 synchronized double[][] boxesAt(long timeUs){
  if(closed)return null;Map.Entry<Long,Block> entry=blocks.floorEntry(timeUs);if(entry==null||timeUs>=entry.getValue().end)return null;
  Block block=entry.getValue();Clip clip=cache.get(block.start);if(clip==null){request(block);return null;}
  Map.Entry<Long,Block> next=blocks.higherEntry(block.start);if(next!=null&&next.getKey()==block.end)request(next.getValue());
  Frame[] frames=clip.frames;int low=0,high=frames.length;
  while(low<high){int mid=(low+high)>>>1;if(frames[mid].at<=timeUs)low=mid+1;else high=mid;}
  int index=low-1;if(index<0||frames[index].end<=timeUs)return null;
  Map<Long,double[]> union=new LinkedHashMap<>();
  for(int i=index;i>=0&&frames[i].end>=timeUs-MARGIN_US;i--)merge(union,frames[i]);
  for(int i=index+1;i<frames.length&&frames[i].at<=timeUs+MARGIN_US;i++)merge(union,frames[i]);
  return union.values().toArray(new double[0][]);
 }
 private static void merge(Map<Long,double[]> output,Frame frame){for(double[] b:frame.boxes){long id=(long)b[0];double[] old=output.get(id);if(old==null)output.put(id,new double[]{b[1],b[2],b[3],b[4]});else{old[0]=Math.min(old[0],b[1]);old[1]=Math.min(old[1],b[2]);old[2]=Math.max(old[2],b[3]);old[3]=Math.max(old[3],b[4]);}}}
 synchronized int loadedClips(){return cache.size();}
 @Override public synchronized void close(){if(closed)return;closed=true;reader.shutdownNow();cache.clear();blocks.clear();ready.clear();File[] files=directory.listFiles();if(files!=null)for(File f:files)if(f.isFile())f.delete();directory.delete();}
}
