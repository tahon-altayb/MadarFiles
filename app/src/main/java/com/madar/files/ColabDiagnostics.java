package com.madar.files;

import android.content.Context;
import android.net.*;
import org.json.JSONObject;
import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** A bounded, local report of fixed codes only. Never accepts server error text or URLs. */
final class ColabDiagnostics {
 private final Context context;private final long started=System.nanoTime();private final List<String> lines=new ArrayList<>();private String environment="";
 private static final Set<String> FIXED=new HashSet<>(Arrays.asList("MUSIC_GPU_UNAVAILABLE","MUSIC_WIRE_LENGTH","MUSIC_WIRE_HASH","MUSIC_WIRE_ENCODING","MUSIC_GPU_WHEEL","MUSIC_GPU_PROVIDER","MUSIC_JOB_PATH","JUPYTER_PATH_INVALID","RESULT_VERIFIED","MUSIC_RESULT_SCHEMA","MUSIC_OUTPUT_HASH","MUSIC_DURATION","MUSIC_CHUNKS","MUSIC_MANIFEST","MODEL_SIZE","MODEL_HASH","UPLOAD_HASH","MODEL_CONFIG","MUSIC_OUTPUT_FORMAT","MUSIC_OUTPUT_LENGTH","MUSIC_OUTPUT_FINITE","MUSIC_TIMEOUT","MUSIC_PROCESS_EXIT","ORIGINAL_RECOVERED","RESPONSE_SCHEMA","RUNTIME_ID_MISMATCH","RUNTIME_TOKEN_INVALID","RUNTIME_TOKEN_EXPIRED","RUNTIME_EXPIRY_INVALID","RUNTIME_URL_INVALID","JUPYTER_START_TIMEOUT","LOCAL_MEMORY","LOCAL_NATIVE","START","DONE","DONE_ORIGINAL_ONLY","FAILED","CANCELLED","UNCLASSIFIED","AUDIO_DECODE","AUDIO_SEPARATION","AUDIO_SEPARATION_FALLBACK","AUDIO_READY","RUNTIME_CREATE","RUNTIME_PENDING_TIMEOUT","JUPYTER_READY","JUPYTER_CONNECT","KERNEL_CONNECT","MUSIC_PREPARE","MUSIC_READY","MUSIC_START_TIMEOUT","KERNEL_CREATE","UPLOAD_AUDIO","UPLOAD_COMPLETE","FETCH_RESULT","CLEANUP_OK","CLEANUP_PENDING","WS_OPEN","WS_RETRY","WS_CONNECT_TIMEOUT","WS_NETWORK_TIMEOUT","WS_NETWORK","WS_DNS","WS_TLS","WS_CLOSED","WS_SEND","WS_LOST_BEFORE_EXECUTION","WS_FRAME_FORMAT","WS_FRAME_SIZE","EXECUTION_QUEUED","EXECUTION_ALREADY_SENT","KERNEL_DEAD","PROCESS_TIMEOUT","REST_CONTINUES","PROGRESS_RETRY","WORKER_NOT_STARTED","WORKER_HEARTBEAT_TIMEOUT","STAGE_TIMEOUT","IO_DNS","IO_TLS","IO_TIMEOUT","IO_NETWORK","RESPONSE_TOO_LARGE","RESPONSE_INVALID_JSON","PY_FILE_NOT_FOUND","PY_MODULE_MISSING","PY_IMPORT","PY_WORKER_EXIT","PY_PERMISSION","PY_SYNTAX","PY_NAME","PY_EXECUTION"));
 static boolean stageKnown(String stage){return Arrays.asList("bootstrap","ready","install","gpu_check","asr_download","asr_load","asr","asr_validate","translation_check","translation_tokenizer","translation_download","translation_load","translation","translation_review","model_download","model_load","separate","done").contains(stage);}
 static boolean safe(String code){if(FIXED.contains(code)||code.equals("WORKER_FAILED"))return true;if(!ColabSpeech.workerCode(code).equals("WORKER_FAILED"))return true;
  if(code.matches("(HTTP_|WS_HTTP_|PROGRESS_HTTP_)[1-5][0-9]{2}|WS_ATTEMPT_[12]"))return true;
  return code.startsWith("STAGE_")&&stageKnown(code.substring(6).toLowerCase(Locale.ROOT));}
 ColabDiagnostics(Context context){this.context=context;record("START");}
 synchronized void record(String code){if(!safe(code))return;if(!lines.isEmpty()&&lines.get(lines.size()-1).endsWith(" · "+code))return;
  if(lines.size()>=60)lines.remove(1);lines.add((System.nanoTime()-started)/1000000000L+"s · "+code);save();}
 void failed(Exception error){issue(error);record("FAILED");}
 void issue(Exception error){Matcher matcher=Pattern.compile("[A-Z][A-Z0-9_]+").matcher(String.valueOf(error.getMessage()));boolean found=false;while(matcher.find()){String code=matcher.group();if(safe(code)){record(code);found=true;}}if(!found)record("UNCLASSIFIED");}
 synchronized void environment(JSONObject data){StringBuilder summary=new StringBuilder();for(String key:new String[]{"torch","ctranslate2","av","transformers","accelerate","bitsandbytes","huggingface-hub","cuda"}){String version=data.optString(key);if(version.matches("[0-9]{1,3}(\\.[0-9]{1,3}){1,3}"))summary.append(key).append(": ").append(version).append('\n');}String precision=data.optString("translation_precision");if(precision.equals("NF4")||precision.equals("FP16"))summary.append("Translation precision: ").append(precision).append('\n');long disk=data.optLong("disk_free_mb",-1);if(disk>=0&&disk<=10000000)summary.append("Disk free MiB: ").append(disk).append('\n');environment=summary.toString();save();}
 private String network(){try{ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);NetworkCapabilities n=manager.getNetworkCapabilities(manager.getActiveNetwork());if(n==null)return "NONE";return (n.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"WIFI":n.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"CELLULAR":"OTHER")+(n.hasTransport(NetworkCapabilities.TRANSPORT_VPN)?" + VPN":"");}catch(Exception e){return "UNKNOWN";}}
 private void save(){String version="";try{version=context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}catch(Exception ignored){}
  String report="مدار "+version+" · حالة آخر معالجة Colab\nالشبكة: "+network()+"\nAndroid API: "+android.os.Build.VERSION.SDK_INT+"\n"+environment+String.join("\n",lines);
  context.getSharedPreferences("colab_diagnostics",0).edit().putString("report",report).apply();}
 static String last(Context context){return context.getSharedPreferences("colab_diagnostics",0).getString("report","");}

 /** Uses local monotonic time, independent of server/device wall-clock differences. */
 static final class Watch {
  String stage="bootstrap";long stageAt,lastHeartbeatAt,lastHeartbeat=-1;int progress=-1;
  Watch(long now){stageAt=now;lastHeartbeatAt=now;}
  void update(JSONObject state,long now)throws IOException{
   String next=state.optString("stage");if(!stageKnown(next))throw new IOException("Colab · RESPONSE_INVALID_JSON");int p=state.optInt("progress",27);
   if(!next.equals(stage)||p>progress){stage=next;stageAt=now;progress=p;}
   long beat=state.optLong("heartbeat",-1);if(beat>=0&&beat!=lastHeartbeat){lastHeartbeat=beat;lastHeartbeatAt=now;}
   if(now-lastHeartbeatAt>120000)throw new IOException("Colab · WORKER_HEARTBEAT_TIMEOUT");
   long limit=stage.equals("install")?13*60000L:(stage.equals("translation_load")||stage.equals("translation_download"))?20*60000L:stage.equals("asr_load")?5*60000L:10*60000L;
   if(now-stageAt>limit)throw new IOException("Colab · STAGE_TIMEOUT");
  }
 }
}

