package com.madar.files;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.media.MediaMetadata;
import android.media.session.*;
import android.os.*;
import androidx.media3.common.Player;

/** Foreground media owner and system controls, independent of the video surface. */
public final class PlaybackService extends Service {
 static final String ACTION="com.madar.files.PLAYBACK_";
 private final Handler timer=new Handler(Looper.getMainLooper());
 private PlaybackSession playback;private MediaSession media;private NotificationManager notifications;private boolean foreground;private long idleAt;private int lastState=-1;private String lastText="";
 @Override public void onCreate(){super.onCreate();notifications=getSystemService(NotificationManager.class);notifications.createNotificationChannel(new NotificationChannel("playback","مشغّل مدار",NotificationManager.IMPORTANCE_LOW));media=new MediaSession(this,"MadarPlayback");media.setCallback(new MediaSession.Callback(){
  @Override public void onPlay(){command("PLAY");}@Override public void onPause(){command("PAUSE");}@Override public void onStop(){command("STOP");}@Override public void onSeekTo(long at){if(playback!=null)playback.player.seekTo(at);}@Override public void onRewind(){command("REWIND");}@Override public void onFastForward(){command("FORWARD");}
 },timer);}
 static PendingIntent action(Context context,String session,String command,int request){return PendingIntent.getService(context,request,new Intent(context,PlaybackService.class).setAction(ACTION+command).putExtra("session",session),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  PlaybackSession current=PlaybackSession.current;
  if(current==null||intent==null||!current.id.equals(intent.getStringExtra("session"))){if(playback==null)stopSelfResult(startId);return START_NOT_STICKY;}
  if(playback!=current){playback=current;lastState=-1;lastText="";media.setSessionActivity(open());media.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,playback.input.title).putString(MediaMetadata.METADATA_KEY_ARTIST,"مدار · مشغّل الفيديو").putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,artwork()).build());media.setActive(true);}
  // Enter foreground before processing even a pause/stop sent during a handoff.
  if(Build.VERSION.SDK_INT>=29)startForeground(83,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);else startForeground(83,notification());foreground=true;
  if(intent.getAction()!=null&&intent.getAction().startsWith(ACTION))command(intent.getAction().substring(ACTION.length()));
  timer.removeCallbacks(tick);if(playback!=null)timer.post(tick);return START_NOT_STICKY;
 }
 private void command(String command){if(playback==null||playback.closed)return;switch(command){case "PLAY":playback.setWanted(true);break;case "PAUSE":playback.setWanted(false);break;case "TOGGLE":playback.setWanted(!playback.wanted());break;case "REWIND":playback.player.seekTo(Math.max(0,playback.position()-10000));break;case "FORWARD":playback.player.seekTo(playback.position()+10000);break;case "STOP":playback.close();playback=null;stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;media.setActive(false);stopSelf();break;}if(playback!=null){if(playback.wanted()&&!foreground){startForeground(83,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);foreground=true;}update();}}
 private PendingIntent open(){return PendingIntent.getActivity(this,83,new Intent(this,PlayerActivity.class).putExtra("playbackSession",playback.id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);}
 private Notification notification(){
  String text=playback==null?"تجهيز المشغّل":playback.cleanRequested?playback.waiting?"تجهيز الصوت المنظّف · يستمر الفصل في الخلفية":"الصوت المنظّف · إزالة الموسيقى":playback.wanted()?"تشغيل الفيديو في الخلفية":"متوقف مؤقتًا";
  Notification.Builder n=new Notification.Builder(this,"playback").setSmallIcon(R.drawable.ic_launcher).setColor(0xff294743).setContentTitle(playback==null?"مدار":playback.input.title).setContentText(text).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(playback!=null&&playback.wanted()).setStyle(new Notification.MediaStyle().setMediaSession(media.getSessionToken()).setShowActionsInCompactView(0,1,2));
  if(playback!=null){n.setContentIntent(open()).setDeleteIntent(action(this,playback.id,"STOP",87));n.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_rew,"رجوع 10 ثوانٍ",action(this,playback.id,"REWIND",84)).build());n.addAction(new Notification.Action.Builder(playback.wanted()?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play,playback.wanted()?"إيقاف مؤقت":"تشغيل",action(this,playback.id,"TOGGLE",85)).build());n.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"إغلاق المشغّل",action(this,playback.id,"STOP",86)).build());}return n.build();
 }
 private void update(){if(playback==null||playback.closed)return;boolean play=playback.wanted();int state=!play?PlaybackState.STATE_PAUSED:playback.clean!=null&&playback.clean.waiting()||playback.player.getPlaybackState()==Player.STATE_BUFFERING?PlaybackState.STATE_BUFFERING:PlaybackState.STATE_PLAYING;
  media.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE|PlaybackState.ACTION_SEEK_TO|PlaybackState.ACTION_REWIND|PlaybackState.ACTION_FAST_FORWARD|PlaybackState.ACTION_STOP).setState(state,playback.position(),playback.speed,SystemClock.elapsedRealtime()).build());
  String text=playback.cleanRequested+":"+playback.waiting;if(lastState!=state||!lastText.equals(text)){lastState=state;lastText=text;notifications.notify(83,notification());}
  if(play)idleAt=0;else if(idleAt==0)idleAt=SystemClock.elapsedRealtime();else if(SystemClock.elapsedRealtime()-idleAt>10*60*1000L){stopForeground(STOP_FOREGROUND_DETACH);foreground=false;}
 }
 private final Runnable tick=new Runnable(){@Override public void run(){PlaybackSession current=PlaybackSession.current;if(current!=null&&!current.closed&&current!=playback){playback=current;lastState=-1;media.setSessionActivity(open());media.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,current.input.title).putString(MediaMetadata.METADATA_KEY_ARTIST,"مدار").build());}if(playback==null||playback.closed){playback=null;stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;stopSelf();return;}update();timer.postDelayed(this,1000);}};
 private Bitmap artwork(){Bitmap bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(bitmap);Paint p=new Paint(3);p.setShader(new LinearGradient(0,0,256,256,new int[]{0xff37544f,0xff132421},null,Shader.TileMode.CLAMP));c.drawRoundRect(0,0,256,256,42,42,p);p.setShader(null);p.setColor(0x30ffffff);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);c.drawRoundRect(9,9,247,247,36,36,p);p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setColor(0xfff1e9d8);p.setTextSize(62);c.drawText("مدار",128,148,p);return bitmap;}
 @Override public void onTaskRemoved(Intent root){if(playback!=null&&!playback.wanted())command("STOP");}
 @Override public void onDestroy(){timer.removeCallbacksAndMessages(null);if(playback!=null&&PlaybackSession.current==playback)playback.close();if(media!=null){media.setActive(false);media.release();}super.onDestroy();}
 @Override public IBinder onBind(Intent intent){return null;}
}
