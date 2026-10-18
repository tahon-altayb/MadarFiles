package com.madar.files;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import androidx.media3.common.*;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.*;
import org.json.JSONObject;
import java.io.File;
import java.util.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class VideoExportService extends Service {
    static final String EVENT="com.madar.files.EXPORT";
    static volatile boolean active=false;
    static volatile String lastEvent;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Transformer transformer;
    private File partial,output;
    private boolean terminal;
    private long began;
    private NotificationManager notifications;
    @Override public void onCreate(){super.onCreate();notifications=getSystemService(NotificationManager.class);notifications.createNotificationChannel(new NotificationChannel("exports","تصدير الفيديو",NotificationManager.IMPORTANCE_LOW));}
    private Notification notification(int progress){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel=PendingIntent.getService(this,1,new Intent(this,VideoExportService.class).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"exports").setSmallIcon(com.madar.files.R.drawable.ic_launcher).setContentTitle("مدار · تصدير الفيديو").setContentText(progress<0?"جارٍ التجهيز…":progress+"%").setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setProgress(100,Math.max(0,progress),progress<0).addAction(new Notification.Action.Builder(null,"إلغاء",cancel).build()).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if("cancel".equals(intent.getAction())){if(active)finish("cancelled",null);else stopSelf();return START_NOT_STICKY;}
        if(active)return START_NOT_STICKY;
        try{
            if(Build.VERSION.SDK_INT>=35)startForeground(7,notification(-1),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);else startForeground(7,notification(-1),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            active=true;terminal=false;began=System.currentTimeMillis();
            JSONObject o=new JSONObject(intent.getStringExtra("options"));String tool=o.getString("tool");boolean audio=tool.equals("audio");
            File input=new File(intent.getStringExtra("input"));if(!input.isFile()||!input.getCanonicalPath().startsWith(getCacheDir().getCanonicalPath()+File.separator))throw new IllegalArgumentException("ملف المصدر غير موجود");
            long duration=intent.getLongExtra("duration",0);if(duration<=0)throw new IllegalArgumentException("مدة الفيديو غير معروفة");
            MediaItem.Builder media=new MediaItem.Builder().setUri(Uri.fromFile(input));
            if(tool.equals("trim")){
                double start=o.getDouble("start"),end=o.getDouble("end");if(!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<=start||end*1000>duration+100)throw new IllegalArgumentException("حدود القص غير صالحة");
                media.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(Math.round(start*1000)).setEndPositionMs(Math.min(duration,Math.round(end*1000))).build());
            }
            List<Effect> effects=new ArrayList<>();int height=o.optInt("height",0),originalHeight=intent.getIntExtra("height",0);
            if(!audio&&height>0&&height<originalHeight)effects.add(Presentation.createForHeight(height));
            EditedMediaItem edited=new EditedMediaItem.Builder(media.build()).setRemoveAudio(tool.equals("mute")).setRemoveVideo(audio).setEffects(new Effects(Collections.emptyList(),effects)).build();
            output=FilesUtil.output(this,audio?"m4a":"mp4");partial=new File(output.getAbsolutePath()+".partial");
            Transformer.Builder builder=new Transformer.Builder(this).setAudioMimeType(MimeTypes.AUDIO_AAC);
            if(!audio)builder.setVideoMimeType(o.optString("format","mp4").equals("hevc")?MimeTypes.VIDEO_H265:MimeTypes.VIDEO_H264);
            int bitrate=o.optInt("bitrate",0);
            if(bitrate>0)builder.setEncoderFactory(new DefaultEncoderFactory.Builder(this).setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate((int)FilesUtil.bounded(bitrate,500000,50000000)).build()).build());
            builder.addListener(new Transformer.Listener(){
                @Override public void onCompleted(Composition composition,ExportResult result){if(partial==null||partial.length()==0||!partial.renameTo(output)){finish("error","تعذر حفظ نتيجة الفيديو");return;}try{publish(FilesUtil.info(output).put("type","result"));}catch(Exception e){finish("error","تعذر قراءة النتيجة");return;}finish("done",null);}
                @Override public void onError(Composition composition,ExportResult result,ExportException error){finish("error","تعذر تصدير الفيديو على هذا الجهاز ("+error.errorCode+"). جرّب H.264 ودقة أقل وتأكد من المساحة المتاحة.");}
            });
            transformer=builder.build();transformer.start(edited,partial.getAbsolutePath());
            publish(new JSONObject().put("type","progress").put("progress",-1));handler.post(progress);
        }catch(Exception e){finish("error",e.getMessage()==null?"تعذر بدء التصدير":e.getMessage());}
        return START_NOT_STICKY;
    }
    private final Runnable progress=new Runnable(){@Override public void run(){if(!active||terminal||transformer==null)return;try{if(System.currentTimeMillis()-began>5L*60*60*1000){finish("error","توقف التصدير بعد تجاوز خمس ساعات");return;}ProgressHolder p=new ProgressHolder();int state=transformer.getProgress(p);int value=state==Transformer.PROGRESS_STATE_AVAILABLE?p.progress:-1;publish(new JSONObject().put("type","progress").put("progress",value));notifications.notify(7,notification(value));handler.postDelayed(this,750);}catch(Exception e){finish("error","تعذر متابعة التصدير");}}};
    private void publish(JSONObject o){lastEvent=o.toString();sendBroadcast(new Intent(EVENT).setPackage(getPackageName()).putExtra("event",lastEvent));}
    private void finish(String type,String message){
        if(terminal)return;terminal=true;handler.removeCallbacksAndMessages(null);if(transformer!=null){transformer.cancel();transformer=null;}if(!type.equals("done")){if(partial!=null)partial.delete();try{JSONObject event=new JSONObject().put("type",type);if(message!=null)event.put("message",message);publish(event);}catch(Exception ignored){}}
        active=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public void onTimeout(int startId,int fgsType){finish("error","أوقف النظام المعالجة بعد انتهاء الوقت المتاح؛ جرّب فيديو أقصر");}
    @Override public void onDestroy(){if(active&&!terminal)finish("error","توقفت معالجة الفيديو");super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
