package com.madar.files;

import android.media.*;
import android.content.*;
import android.os.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.*;

/** The cleaned PCM hardware clock controls the existing full-screen video player. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class CleanAudioController implements AutoCloseable {
 interface Status {void show(String text,boolean waiting);}
 private final Context context;private final AudioManager manager;private final AudioFocusRequest focus;private boolean focused,hasFocusRequest;private final ExoPlayer video;private final LiveSeparation.Session session;private final Status status;private final Handler main=new Handler(Looper.getMainLooper());private final AudioTrack audio;
 private RandomAccessFile input;private boolean wanted,playing,adjusting,closed;private long cursor,baseFrame;private float volume=1,speed=1,pitch=1;private String failure="";private long lastUi;
 CleanAudioController(Context context,ExoPlayer video,LiveSeparation.Session session,long positionMs,boolean wanted,Status status){this.context=context.getApplicationContext();manager=this.context.getSystemService(AudioManager.class);focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()).setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(change->{if(closed)return;if(change==AudioManager.AUDIOFOCUS_GAIN){focused=true;}else{focused=false;if(change==AudioManager.AUDIOFOCUS_LOSS){this.wanted=false;hasFocusRequest=false;}pause();}},main).build();this.video=video;this.session=session;this.status=status;this.wanted=wanted;session.watching=true;
  audio=new AudioTrack.Builder().setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(35280,3*AudioTrack.getMinBufferSize(44100,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT))).build();
  video.setVolume(0);video.setAudioAttributes(video.getAudioAttributes(),false);video.addListener(listener);androidx.core.content.ContextCompat.registerReceiver(this.context,noisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);move(positionMs);if(wanted)requestFocus();main.post(tick);
 }
 private final Player.Listener listener=new Player.Listener(){
  @Override public void onPlayWhenReadyChanged(boolean play,int reason){if(!adjusting)setWanted(play);}
  @Override public void onPositionDiscontinuity(Player.PositionInfo old,Player.PositionInfo next,int reason){if(!adjusting&&reason==Player.DISCONTINUITY_REASON_SEEK)move(next.positionMs);}
  @Override public void onPlayerError(PlaybackException error){wanted=false;pause();failure="تعذر تشغيل الفيديو؛ الصوت الأصلي مكتوم";}
 };
 private void pause(){playing=false;adjusting=true;try{video.pause();audio.pause();}finally{adjusting=false;}}
 void move(long positionMs){pause();audio.flush();double start=session.offsetUs/1e6,at=Math.max(start,Math.min(session.ready(),positionMs/1000.0));cursor=Math.max(0,Math.min(session.frames,Math.round((at-start)*44100)));baseFrame=cursor;adjusting=true;try{video.seekTo(Math.round((start+cursor/44100.0)*1000));}finally{adjusting=false;}}
 private final BroadcastReceiver noisy=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){if(!closed)setWanted(false);}};
 private void requestFocus(){if(hasFocusRequest)return;hasFocusRequest=true;focused=manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;if(!focused){hasFocusRequest=false;wanted=false;failure="الصوت مشغول بتطبيق آخر؛ اضغط تشغيل للمحاولة";}}
 void setWanted(boolean value){if(closed)return;wanted=value;if(value){failure="";requestFocus();}else{pause();if(hasFocusRequest)manager.abandonAudioFocusRequest(focus);hasFocusRequest=false;focused=false;}}
 boolean waiting(){return wanted&&!playing;}
 boolean wanted(){return wanted;}
 int audioSession(){return audio.getAudioSessionId();}
 void settings(float speed,float pitch,float volume){this.speed=speed;this.pitch=pitch;this.volume=volume;audio.setVolume(volume);if(playing)audio.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(pitch));video.setVolume(0);}
 private final Runnable tick=new Runnable(){@Override public void run(){if(closed)return;try{
  video.setVolume(0);double played=session.offsetUs/1e6+(baseFrame+(audio.getPlaybackHeadPosition()&0xffffffffL))/44100.0,ready=session.ready();
  if(session.pcm!=null&&input==null)input=new RandomAccessFile(session.pcm,"r");
  if(playing&&(video.getPlaybackState()==Player.STATE_BUFFERING||video.getPlaybackSuppressionReason()!=Player.PLAYBACK_SUPPRESSION_REASON_NONE))pause();
  if(wanted&&focused&&input!=null&&ready-played>.001){
   if(!playing&&video.getPlaybackState()==Player.STATE_READY&&(ready-played>.5||session.done)){adjusting=true;try{if(Math.abs(video.getCurrentPosition()/1000.0-played)>.12)video.seekTo(Math.round(played*1000));playing=true;video.play();audio.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(pitch));audio.play();}finally{adjusting=false;}}
   if(playing&&session.frames>cursor){int frames=(int)Math.min(1764,session.frames-cursor);byte[] bytes=new byte[frames*4];input.seek(cursor*4);input.readFully(bytes);int written=audio.write(bytes,0,bytes.length,AudioTrack.WRITE_NON_BLOCKING);if(written>0)cursor+=written/4;}
  }
  if(playing){if(ready-played<(session.done?.001:.05)){pause();if(session.done)wanted=false;}else if(Math.abs(video.getCurrentPosition()/1000.0-played)>.12){adjusting=true;try{video.seekTo(Math.round(played*1000));}finally{adjusting=false;}}}
  String text=!failure.isEmpty()?failure:session.failed?session.error:session.done?"الصوت المنظّف · اكتمل الفصل":"الصوت المنظّف · جاهز من "+stamp(session.offsetUs/1000)+" إلى "+stamp(Math.round(ready*1000))+(wanted&&!playing?" · انتظار الجزء التالي":"");if(SystemClock.elapsedRealtime()-lastUi>=250){lastUi=SystemClock.elapsedRealtime();status.show(text,wanted&&!playing||session.failed||!failure.isEmpty());}
 }catch(Exception error){wanted=false;pause();failure="تعذرت متابعة الصوت المنظّف: "+error.getMessage();status.show(failure,true);}main.postDelayed(this,15);}};
 private static String stamp(long ms){return String.format(java.util.Locale.US,"%d:%02d",ms/60000,ms/1000%60);}
 @Override public void close(){if(closed)return;closed=true;main.removeCallbacksAndMessages(null);pause();video.removeListener(listener);if(hasFocusRequest)manager.abandonAudioFocusRequest(focus);try{context.unregisterReceiver(noisy);}catch(IllegalArgumentException ignored){}video.setAudioAttributes(video.getAudioAttributes(),true);audio.release();try{if(input!=null)input.close();}catch(IOException ignored){}session.watching=false;}
}
