package com.madar.files;

import android.media.*;
import android.content.*;
import android.os.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.*;

/** PCM has its own audio-priority feeder. Video follows its clock without flushing PCM. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class CleanAudioController implements AutoCloseable {
 interface Status {void show(String text,boolean waiting);}
 private final Context context;private final AudioManager manager;private final AudioFocusRequest focus;private boolean focused,hasFocusRequest;private final ExoPlayer video;private final LiveSeparation.Session session;private final Status status;
 private final Handler main=new Handler(Looper.getMainLooper());private final HandlerThread feederThread=new HandlerThread("madar-clean-audio",android.os.Process.THREAD_PRIORITY_AUDIO);private final Handler feeder;
 private final AudioTrack audio;private final Object audioLock=new Object();private RandomAccessFile input;private long cursor,baseFrame,pausedLimit;private volatile boolean closed;private boolean wanted,adjusting,rebuffered;private volatile boolean playing,feedAllowed;private float volume=1,speed=1,pitch=1;private volatile String failure="";private long lastUi,lastSync,driftSince,refillAfter;
 private final java.util.ArrayDeque<Boolean> ownPlayChanges=new java.util.ArrayDeque<>();private final java.util.ArrayDeque<Long> ownSeeks=new java.util.ArrayDeque<>();
 // Diagnostics used by the native continuity regression tests (no source audio is recorded).
 int resetCount,syncSeekCount;private long lastPlayed;
 CleanAudioController(Context context,ExoPlayer video,LiveSeparation.Session session,long positionMs,boolean wanted,Status status){
  this.context=context.getApplicationContext();this.video=video;this.session=session;this.status=status;this.wanted=wanted;session.watching=true;manager=this.context.getSystemService(AudioManager.class);
  focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()).setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(change->{if(closed)return;if(change==AudioManager.AUDIOFOCUS_GAIN)focused=true;else{focused=false;if(change==AudioManager.AUDIOFOCUS_LOSS){this.wanted=false;hasFocusRequest=false;}pause();}},main).build();
  audio=new AudioTrack.Builder().setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(176400,3*AudioTrack.getMinBufferSize(44100,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT))).build();
  video.setVolume(0);video.setAudioAttributes(video.getAudioAttributes(),false);video.addListener(listener);androidx.core.content.ContextCompat.registerReceiver(this.context,noisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
  move(positionMs);feederThread.start();feeder=new Handler(feederThread.getLooper());feeder.post(feed);if(wanted)requestFocus();main.post(tick);
 }
 private final Player.Listener listener=new Player.Listener(){
  @Override public void onPlayWhenReadyChanged(boolean play,int reason){if(!ownPlayChanges.isEmpty()&&ownPlayChanges.peekFirst()==play){ownPlayChanges.removeFirst();return;}if(!adjusting)setWanted(play);}
  @Override public void onPositionDiscontinuity(Player.PositionInfo old,Player.PositionInfo next,int reason){if(reason!=Player.DISCONTINUITY_REASON_SEEK)return;
   // Media3 can deliver these callbacks after seekTo returns. A temporary boolean
   // is insufficient: clock corrections must NEVER become user seeks/PCM flushes.
   if(!ownSeeks.isEmpty()&&Math.abs(ownSeeks.peekFirst()-next.positionMs)<=2){ownSeeks.removeFirst();return;}
   if(!adjusting)move(next.positionMs);
  }
  @Override public void onPlayerError(PlaybackException error){wanted=false;pause();failure="تعذر تشغيل الفيديو؛ الصوت الأصلي مكتوم";}
 };
 private void seekVideo(long target){if(video.getCurrentPosition()==target)return;ownSeeks.addLast(target);video.seekTo(target);}
 private void setVideoPlaying(boolean value){if(video.getPlayWhenReady()==value)return;ownPlayChanges.addLast(value);video.setPlayWhenReady(value);}
 private void pause(){feedAllowed=false;adjusting=true;try{setVideoPlaying(false);synchronized(audioLock){if(playing){playedLocked();pausedLimit=cursor;refillAfter=SystemClock.elapsedRealtime()+100;}audio.pause();playing=false;}}finally{adjusting=false;}}
 void move(long positionMs){pause();synchronized(audioLock){audio.flush();resetCount++;double start=session.offsetUs/1e6,at=Math.max(start,Math.min(session.ready(),positionMs/1000.0));cursor=Math.max(0,Math.min(session.frames,Math.round((at-start)*44100)));baseFrame=cursor;lastPlayed=cursor;pausedLimit=cursor;rebuffered=false;seekVideo(Math.round((start+cursor/44100.0)*1000));}driftSince=0;lastSync=SystemClock.elapsedRealtime();}
 private final BroadcastReceiver noisy=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){if(!closed)setWanted(false);}};
 private void requestFocus(){if(hasFocusRequest)return;hasFocusRequest=true;focused=manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;if(!focused){hasFocusRequest=false;wanted=false;failure="الصوت مشغول بتطبيق آخر؛ اضغط تشغيل للمحاولة";}}
 void setWanted(boolean value){if(closed)return;wanted=value;if(value){failure="";requestFocus();}else{pause();if(hasFocusRequest)manager.abandonAudioFocusRequest(focus);hasFocusRequest=false;focused=false;}}
 boolean waiting(){return wanted&&!playing;}
 boolean wanted(){return wanted;}
 int audioSession(){return audio.getAudioSessionId();}
 void settings(float speed,float pitch,float volume){this.speed=speed;this.pitch=pitch;this.volume=volume;synchronized(audioLock){audio.setVolume(volume);if(playing)audio.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(pitch));}video.setVolume(0);}
 long playedFrames(){synchronized(audioLock){return playedLocked();}}
 int underruns(){synchronized(audioLock){return audio.getUnderrunCount();}}
 // A paused/starved hardware counter can still report its last output quantum.
 // Prefilling a new PCM prefix must not turn that stale quantum into heard words.
 private long playedLocked(){long head=baseFrame+(audio.getPlaybackHeadPosition()&0xffffffffL);return lastPlayed=Math.max(lastPlayed,Math.min(playing?cursor:pausedLimit,head));}
 private final Runnable feed=new Runnable(){@Override public void run(){if(closed)return;try{synchronized(audioLock){
  if(session.pcm!=null&&input==null)input=new RandomAccessFile(session.pcm,"r");
  if(input!=null&&feedAllowed){long head=audio.getPlaybackHeadPosition()&0xffffffffL;if(baseFrame+head>cursor)baseFrame=cursor-head;byte[] bytes=feedBuffer;for(int i=0;i<16&&session.frames>cursor;i++){int count=(int)Math.min(bytes.length/4,session.frames-cursor)*4;input.seek(cursor*4);input.readFully(bytes,0,count);int n=audio.write(bytes,0,count,AudioTrack.WRITE_NON_BLOCKING);if(n<0)throw new IOException("AudioTrack write "+n);if(n%4!=0)throw new IOException("إطار صوت غير مكتمل");cursor+=n/4;if(n<count)break;}}
 }}catch(Exception error){failure="تعذرت متابعة الصوت المنظّف: "+error.getMessage();}if(!closed)feeder.postDelayed(this,10);}};
 private final byte[] feedBuffer=new byte[16384];
 private final Runnable tick=new Runnable(){@Override public void run(){if(closed)return;try{
  video.setVolume(0);long frames=playedFrames();double played=session.offsetUs/1e6+frames/44100.0,ready=session.ready();long now=SystemClock.elapsedRealtime();
  if(!failure.isEmpty()){wanted=false;pause();}
  if(playing&&(video.getPlaybackState()==Player.STATE_BUFFERING||video.getPlaybackSuppressionReason()!=Player.PLAYBACK_SUPPRESSION_REASON_NONE))pause();
  long queued;synchronized(audioLock){queued=cursor-frames;}
  double reserve=session.waitForComplete?Double.POSITIVE_INFINITY:session.buffer==null?(rebuffered?8:3)*Math.max(1,speed):session.buffer.required(frames/44100.0,speed,now);
  if(wanted&&focused&&!playing&&video.getPlaybackState()==Player.STATE_READY&&ready>played&&now>=refillAfter&&((ready-played>=reserve)||session.done)){
   // Only arm prefill when resuming is intended; never submit a tiny refill
   // into the final hardware quantum of a paused/starved AudioTrack.
   feedAllowed=true;
   if(queued>0&&(queued>=Math.min(8820,session.frames-frames)||session.done)){adjusting=true;try{if(Math.abs(video.getCurrentPosition()/1000.0-played)>.25)seekVideo(Math.round(played*1000));setVideoPlaying(true);synchronized(audioLock){audio.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(pitch));long at=playedLocked();baseFrame=at-(audio.getPlaybackHeadPosition()&0xffffffffL);audio.play();playing=true;}lastSync=now;driftSince=0;}finally{adjusting=false;}}
  }
  if(playing){
   if(frames>=session.frames){pause();rebuffered=true;if(session.done)wanted=false;}
   else if(video.getPlaybackState()==Player.STATE_READY&&now-lastSync>1500&&Math.abs(video.getCurrentPosition()/1000.0-played)>.25){if(driftSince==0)driftSince=now;else if(now-driftSince>=350){syncSeekCount++;seekVideo(Math.round(played*1000));lastSync=now;driftSince=0;}}
   else driftSince=0;
  }
  String text=!failure.isEmpty()?failure:session.failed?session.error:session.done?"الصوت المنظّف · اكتمل الفصل":"الصوت المنظّف · جاهز إلى "+stamp(Math.round(ready*1000))+(session.waitForComplete?" · انتظار اكتمال الفصل قبل المشاهدة":session.buffer!=null?" · "+session.buffer.describe(frames/44100.0,session.frames/44100.0,speed,now):wanted&&!playing?" · تجهيز مخزون للمشاهدة المستقرة":"");
  if(now-lastUi>=250){lastUi=now;status.show(text,wanted&&!playing||session.failed||!failure.isEmpty());}
 }catch(Exception error){wanted=false;pause();failure="تعذرت متابعة الصوت المنظّف: "+error.getMessage();status.show(failure,true);}main.postDelayed(this,30);}};
 private static String stamp(long ms){return String.format(java.util.Locale.US,"%d:%02d",ms/60000,ms/1000%60);}
 @Override public void close(){if(closed)return;closed=true;main.removeCallbacksAndMessages(null);feeder.removeCallbacksAndMessages(null);pause();video.removeListener(listener);if(hasFocusRequest)manager.abandonAudioFocusRequest(focus);try{context.unregisterReceiver(noisy);}catch(IllegalArgumentException ignored){}video.setAudioAttributes(video.getAudioAttributes(),true);synchronized(audioLock){audio.release();try{if(input!=null)input.close();}catch(IOException ignored){}}feederThread.quitSafely();session.watching=false;}
}
