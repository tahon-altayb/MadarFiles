package com.madar.files;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.media.MediaMetadata;
import android.media.session.*;
import android.os.*;
import androidx.media3.common.Player;

/** The media session describes the same retained clock used by foreground and PiP. */
public final class PlaybackService extends Service {
 static final String ACTION="com.madar.files.PLAYBACK_";
 private final Handler timer=new Handler(Looper.getMainLooper());
 private int latestStartId,lastState=-1;private PlaybackSession playback;private MediaSession media;
 private NotificationManager notifications;private boolean foreground;private long idleAt,lastDuration=Long.MIN_VALUE;
 private String lastText="",metadataSession="";private Bitmap art;
 @Override public void onCreate(){
  super.onCreate();notifications=getSystemService(NotificationManager.class);
  notifications.createNotificationChannel(new NotificationChannel("playback","مشغّل مدار",NotificationManager.IMPORTANCE_LOW));
  art=artwork();media=new MediaSession(this,"MadarPlayback");media.setCallback(new MediaSession.Callback(){
   @Override public void onPlay(){command("PLAY");}
   @Override public void onPause(){command("PAUSE");}
   @Override public void onStop(){command("STOP");}
   @Override public void onSeekTo(long at){if(valid()){playback.seekTo(at);update();}}
   @Override public void onRewind(){command("REWIND");}
   @Override public void onFastForward(){command("FORWARD");}
   // Let SystemUI choose the native play/pause slot in PiP.
   // Both side buttons still seek ten seconds.
   @Override public void onSkipToPrevious(){command("REWIND");}
   @Override public void onSkipToNext(){command("FORWARD");}
   @Override public void onCustomAction(String action,Bundle extras){if(action!=null&&action.startsWith(ACTION))command(action.substring(ACTION.length()));}
  },timer);
 }
 static PendingIntent action(Context c,String session,String command,int request){
  // Data identity prevents a later session from rewriting an old PiP/notification token.
  Intent intent=new Intent(c,PlaybackService.class).setAction(ACTION+command).setData(android.net.Uri.parse("madar-playback://session/"+session)).putExtra("session",session);
  return PendingIntent.getService(c,request,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
 }
 private boolean valid(){return playback!=null&&!playback.closed&&PlaybackSession.current==playback;}
 private void adopt(PlaybackSession next){playback=next;idleAt=0;lastState=-1;lastText="";lastDuration=Long.MIN_VALUE;metadataSession="";media.setSessionActivity(open());metadata();media.setActive(true);}
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  latestStartId=startId;PlaybackSession current=PlaybackSession.current;
  if(current==null||intent==null||!current.id.equals(intent.getStringExtra("session"))){if(playback==null)stopSelfResult(startId);return START_NOT_STICKY;}
  if(playback!=current)adopt(current);
  ensureForeground();
  if(intent.getAction()!=null&&intent.getAction().startsWith(ACTION))command(intent.getAction().substring(ACTION.length()));
  timer.removeCallbacks(tick);if(valid())timer.post(tick);return START_NOT_STICKY;
 }
 private void ensureForeground(){if(foreground||!valid())return;startForeground(83,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);foreground=true;}
 private void command(String command){
  if(!valid())return;
  // Android 15+ focus and Android 17 background audio require foreground BEFORE play.
  if("PLAY".equals(command)||"TOGGLE".equals(command)&&!playback.wanted())ensureForeground();
  switch(command){
   case "PLAY":playback.setWanted(true);break;
   case "PAUSE":playback.setWanted(false);break;
   case "TOGGLE":playback.setWanted(!playback.wanted());break;
   case "REWIND":playback.seekTo(playback.position()-10000);break;
   case "FORWARD":playback.seekTo(playback.position()+10000);break;
   case "STOP":playback.close();playback=null;removeNotification();stopSelfResult(latestStartId);return;
   default:return;
  }
  update();
 }
 private PendingIntent open(){return PendingIntent.getActivity(this,83,new Intent(this,PlayerActivity.class).setData(android.net.Uri.parse("madar-playback://session/"+playback.id)).putExtra("playbackSession",playback.id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);}
 private String text(){if(!valid())return "تجهيز المشغّل";if(!playback.wanted())return playback.cleanRequested?"متوقف مؤقتًا · الصوت المنظّف":"متوقف مؤقتًا · الصوت الأصلي";if(playback.buffering())return playback.cleanRequested?"تجهيز الصوت المنظّف · يستمر الفصل في الخلفية":"جارٍ تحميل الفيديو";return playback.cleanRequested?"الصوت المنظّف · إزالة الموسيقى":"الصوت الأصلي · مشغّل مدار";}
 private Notification notification(){
  Notification.Builder n=new Notification.Builder(this,"playback").setSmallIcon(R.drawable.ic_player_notification).setLargeIcon(art).setColor(0xff294743).setContentTitle(playback==null?"مدار":playback.input.title).setContentText(text()).setSubText("مشغّل مدار").setOnlyAlertOnce(true).setShowWhen(false).setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(valid()&&playback.wanted()).setCategory(Notification.CATEGORY_TRANSPORT).setStyle(new Notification.MediaStyle().setMediaSession(media.getSessionToken()).setShowActionsInCompactView(0,1,2));
  if(valid()){
   n.setContentIntent(open()).setDeleteIntent(action(this,playback.id,"STOP",87));
   n.addAction(new Notification.Action.Builder(R.drawable.ic_player_rewind,"رجوع 10 ثوانٍ",action(this,playback.id,"REWIND",84)).build());
   n.addAction(new Notification.Action.Builder(playback.wanted()?R.drawable.ic_player_pause:R.drawable.ic_player_play,playback.wanted()?"إيقاف مؤقت":"تشغيل",action(this,playback.id,"TOGGLE",85)).build());
   n.addAction(new Notification.Action.Builder(R.drawable.ic_player_forward,"تقديم 10 ثوانٍ",action(this,playback.id,"FORWARD",88)).build());
   n.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"إغلاق المشغّل",action(this,playback.id,"STOP",86)).build());
  }
  return n.build();
 }
 private void metadata(){
  if(!valid())return;long duration=playback.duration();if(lastDuration==duration&&metadataSession.equals(playback.id))return;
  lastDuration=duration;metadataSession=playback.id;media.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,playback.input.title).putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE,playback.input.title).putString(MediaMetadata.METADATA_KEY_ARTIST,"مدار · مشغّل الفيديو").putLong(MediaMetadata.METADATA_KEY_DURATION,duration).putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,art).build());
 }
 private void update(){
  if(!valid())return;metadata();boolean play=playback.wanted(),seek=playback.player.isCurrentMediaItemSeekable();
  int state=playback.player.getPlayerError()!=null?PlaybackState.STATE_ERROR:!play?playback.player.getPlaybackState()==Player.STATE_ENDED?PlaybackState.STATE_STOPPED:PlaybackState.STATE_PAUSED:playback.buffering()?PlaybackState.STATE_BUFFERING:PlaybackState.STATE_PLAYING;
  PlaybackState.Builder p=new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE|PlaybackState.ACTION_STOP|(seek?PlaybackState.ACTION_SEEK_TO|PlaybackState.ACTION_REWIND|PlaybackState.ACTION_FAST_FORWARD|PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_SKIP_TO_NEXT:0)).setBufferedPosition(playback.bufferedPosition()).setState(state,playback.position(),state==PlaybackState.STATE_PLAYING?playback.speed:0,SystemClock.elapsedRealtime());
  if(seek){p.addCustomAction(new PlaybackState.CustomAction.Builder(ACTION+"REWIND","رجوع 10 ثوانٍ",R.drawable.ic_player_rewind).build());p.addCustomAction(new PlaybackState.CustomAction.Builder(ACTION+"FORWARD","تقديم 10 ثوانٍ",R.drawable.ic_player_forward).build());}
  p.addCustomAction(new PlaybackState.CustomAction.Builder(ACTION+"STOP","إغلاق المشغّل",android.R.drawable.ic_menu_close_clear_cancel).build());
  if(state==PlaybackState.STATE_ERROR)p.setErrorMessage(playback.error);media.setPlaybackState(p.build());
  String text=text();if(lastState!=state||!lastText.equals(text)){lastState=state;lastText=text;notifications.notify(83,notification());}
  if(play)idleAt=0;else if(idleAt==0)idleAt=SystemClock.elapsedRealtime();else if(foreground&&SystemClock.elapsedRealtime()-idleAt>10*60*1000L){stopForeground(STOP_FOREGROUND_DETACH);foreground=false;}
 }
 private void removeNotification(){stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;notifications.cancel(83);media.setActive(false);}
 private final Runnable tick=new Runnable(){@Override public void run(){PlaybackSession current=PlaybackSession.current;if(current!=null&&!current.closed&&current!=playback){adopt(current);ensureForeground();}if(!valid()){playback=null;removeNotification();stopSelfResult(latestStartId);return;}update();timer.postDelayed(this,500);}};
 private Bitmap artwork(){Bitmap bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(bitmap);Paint p=new Paint(3);p.setShader(new LinearGradient(0,0,256,256,new int[]{0xff476860,0xff132421},null,Shader.TileMode.CLAMP));c.drawRoundRect(0,0,256,256,42,42,p);p.setShader(null);p.setColor(0x30ffffff);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);c.drawRoundRect(9,9,247,247,36,36,p);p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setColor(0xfff1e9d8);p.setTextSize(62);c.drawText("مدار",128,148,p);return bitmap;}
 @Override public void onTaskRemoved(Intent root){if(valid()&&!playback.wanted())command("STOP");}
 @Override public void onDestroy(){timer.removeCallbacksAndMessages(null);if(valid())playback.close();removeNotification();media.release();super.onDestroy();}
 @Override public IBinder onBind(Intent intent){return null;}
}
