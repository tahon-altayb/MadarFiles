package com.madar.files;

import android.util.Base64;
import java.io.*;
import java.util.*;
import org.json.*;

/** Bounded lossless transfer for a whole file or one growing watch window. No per-job WebSocket. */
final class MusicCloudJob {
 static final int INPUT_FRAMES=32*ColabMusic.RATE,OUTPUT_FRAMES=16*ColabMusic.RATE;
 static long now(){return android.os.SystemClock.elapsedRealtime();}
 static void schema(JSONObject state,String folder,long frames)throws IOException{
  schema(state,folder,frames,MusicPipeline.LEGACY);
 }
 static void schema(JSONObject state,String folder,long frames,String profile)throws IOException{
  if(state.optInt("protocol")!=2||!folder.equals(state.optString("job"))||state.optLong("frames",-1)!=frames||state.optInt("sample_rate")!=44100||state.optInt("channels")!=2||state.optInt("output_frames")!=OUTPUT_FRAMES||!MusicPipeline.fingerprint(profile).equals(state.optString("model_sha256"))||!MusicPipeline.checked(profile).equals(state.optString("pipeline",MusicPipeline.LEGACY)))throw new IOException("Colab · MUSIC_RESULT_SCHEMA");
 }
 static ColabMusic.Result run(ColabMusic.Session session,AudioPipeline.Decoded audio,MusicSeparator.Progress progress,LiveSeparation.Session live)throws Exception{
  long sourceFrames=audio.pcm.length()/4,frames=Math.round(sourceFrames*44100.0/audio.sampleRate);if(frames<=0)throw new IOException("Colab · MUSIC_DURATION");
  ColabRuntime runtime=session.runtime;ColabDiagnostics diagnostic=session.diagnostic;boolean complete=false,cold=session.batch==0;long began=now();
  session.awaitStorage();String folder=session.root+"_b"+(session.batch++);runtime.jupyter("PUT","api/contents/"+folder,new JSONObject().put("type","directory"),1024*1024);
  File output=File.createTempFile("madar_cloud_vocals_",".pcm",session.context.getCacheDir());ColabMusic.Result result=new ColabMusic.Result();result.pcm=output;
  if(live!=null)LiveSeparation.attach(live,output,audio.offsetUs);
  try{
   long uploadAt=now(),wireBytes=0;JSONArray inputs=new JSONArray();diagnostic.record("UPLOAD_AUDIO");
   try(RandomAccessFile input=new RandomAccessFile(audio.pcm,"r")){
    for(long start=0;start<frames;start+=INPUT_FRAMES){ColabProbe.check(session.cancel);session.checkInitialization();int keep=(int)Math.min(INPUT_FRAMES,frames-start);
     float[] samples=MusicSeparator.readResampled(input,audio.sampleRate,start,keep,sourceFrames);byte[] data=new byte[keep*4];
     for(int i=0;i<samples.length;i++){int n=Math.max(-32768,Math.min(32767,Math.round(samples[i]*32768)));data[2*i]=(byte)n;data[2*i+1]=(byte)(n>>8);}
     MusicWire.Packed pack=MusicWire.encode(data);int index=inputs.length();inputs.put(pack.metadata);wireBytes+=pack.data.length;
     runtime.put(folder+"/input_"+index+".pcm"+(pack.compressed?".zlib":""),Base64.encodeToString(pack.data,Base64.NO_WRAP),"base64");
     progress.update(17+(int)(9*(start+keep)/frames),"رفع الصوت دون فقد · "+((start+keep)/44100)+" / "+(frames/44100)+" ثانية");
    }
   }
   runtime.put(folder+"/request.json",new JSONObject().put("protocol",2).put("pipeline",session.pipeline).put("frames",frames).put("inputs",inputs).toString(),"text");
   session.lastUploadMs=now()-uploadAt;diagnostic.record("UPLOAD_COMPLETE");diagnostic.measure("UPLOAD",session.lastUploadMs,wireBytes,frames);
   long readyAt=now();session.awaitReady();session.lastColdStartMs=cold?Math.max(0,now()-began):0;diagnostic.measure("PREPARE",now()-readyAt,0,0);
   MusicMailbox.submit(runtime,session.root,folder);diagnostic.record("MUSIC_SUBMITTED");
   final long[] fetched={0},fetchMs={0},gpuMs={0};long processAt=now();
   try(OutputStream out=new BufferedOutputStream(new FileOutputStream(output))){
    MusicMailbox.await(runtime,session.root,folder,session.cancel,diagnostic::record,state->{
     String stage=state.optString("stage");if(ColabDiagnostics.stageKnown(stage))diagnostic.record("STAGE_"+stage.toUpperCase(Locale.ROOT));
     if(state.has("pack_count")){
      schema(state,folder,frames,session.pipeline);long count=state.optLong("pack_count",-1),expected=(frames+OUTPUT_FRAMES-1)/OUTPUT_FRAMES;
      if(count<0||count>expected)throw new IOException("Colab · MUSIC_PACK_SCHEMA");JSONArray recent=state.optJSONArray("recent_packs");
      while(fetched[0]<count){ColabProbe.check(session.cancel);long at=now(),index=fetched[0];JSONObject meta=null;
       if(recent!=null)for(int i=0;i<recent.length();i++){JSONObject item=recent.getJSONObject(i);if(item.optLong("index",-1)==index){meta=item;break;}}
       if(meta==null)meta=ColabRuntime.parseJson(runtime.getText(folder+"/pack_"+index+".json",65536));
       int bytes=(int)(Math.min(OUTPUT_FRAMES,frames-index*OUTPUT_FRAMES)*4);
       if(meta.optLong("index",-1)!=index||meta.optLong("bytes",-1)!=bytes||!meta.optString("sha256").matches("[a-f0-9]{64}"))throw new IOException("Colab · MUSIC_PACK_SCHEMA");
       byte[] pcm=MusicWire.fetch(runtime,folder+"/pack_"+index+".pcm",meta,bytes);fetchMs[0]+=now()-at;
       if(output.getUsableSpace()<pcm.length+16L*1024*1024)throw new IOException("Colab · MUSIC_DISK");out.write(pcm);out.flush();fetched[0]++;
       if(live!=null)LiveSeparation.publish(live,output.length()/4);
       progress.update(Math.max(27,Math.min(97,state.optInt("progress",27))),"استرجاع الصوت المنظّف · "+(output.length()/4/44100)+" / "+(frames/44100)+" ثانية");
      }
      gpuMs[0]=Math.max(gpuMs[0],state.optLong("gpu_ms",0));
     }
    });
   }
   diagnostic.record("FETCH_RESULT");JSONObject manifest=ColabRuntime.parseJson(runtime.getText(folder+"/result.json",65536));schema(manifest,folder,frames,session.pipeline);
   if(!"cuda".equals(manifest.optString("provider"))||manifest.optLong("completed_frames",-1)!=frames||manifest.optLong("pack_count",-1)!=fetched[0]||fetched[0]!=(frames+OUTPUT_FRAMES-1)/OUTPUT_FRAMES||output.length()!=frames*4)throw new IOException("Colab · MUSIC_OUTPUT_LENGTH");
   session.lastProcessMs=now()-processAt;diagnostic.measure("PROCESS_FETCH",session.lastProcessMs,output.length(),frames);diagnostic.measure("FETCH",fetchMs[0],output.length(),frames);diagnostic.measure("GPU",gpuMs[0],0,frames);diagnostic.record("RESULT_VERIFIED");complete=true;return result;
  }finally{if(!complete)output.delete();}
 }
 private MusicCloudJob(){}
}
