package com.madar.files;

import android.content.*;
import android.net.Uri;
import androidx.media3.common.*;
import androidx.media3.exoplayer.*;
import java.io.File;
import java.util.Collections;

/** One main-thread playback owner. Activities only attach a surface and controls. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class PlaybackSession implements AutoCloseable {
 static PlaybackSession current;
 final String id=java.util.UUID.randomUUID().toString();
 final Context context;final ExoPlayer player;final Player controls;private final androidx.media3.exoplayer.source.DefaultMediaSourceFactory sources;
 private String screenOwner;int displayMode;boolean locked;PlayerInput input;File subtitle;WatchMusicService.Job watchJob;LiveSeparation.Session cleanSession;
 CleanAudioController clean;boolean cleanRequested,legacyClean,closed;private boolean preparingWanted;
 float volume=1,speed=1,pitch=1;String status="",error="",decoder="";boolean waiting;
 interface Observer {void changed();}
 private Observer observer;
 WatchCensorService.Job censorJob;boolean censorRequested,censorWaiting,censorSurfaceReady;String censorStatus="";
 final CensorOverlay censorOverlay=new CensorOverlay();private final android.os.Handler censorMain=new android.os.Handler(android.os.Looper.getMainLooper());
 private boolean censorWanted,censorApproved;private long censorReserveMs=12000,censorUiAt;
 static PlaybackSession obtain(Context c,PlayerInput input){
  PlaybackSession s=current;if(s!=null&&!s.closed&&s.input.identity.equals(input.identity))return s;
  if(s!=null)s.close();return current=new PlaybackSession(c,input);
 }
 private PlaybackSession(Context c,PlayerInput input){
  context=c.getApplicationContext();this.input=input;
  sources=new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(input.http.factory(context),new androidx.media3.extractor.DefaultExtractorsFactory().setTsExtractorFlags(androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS|androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES));
  player=new ExoPlayer.Builder(context).setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).setRenderersFactory(new DefaultRenderersFactory(context).setExtensionRendererMode(context.getSharedPreferences("player",0).getBoolean("software_audio",false)?DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER:DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON).setEnableDecoderFallback(true)).setMediaSourceFactory(sources).build();
  player.setWakeMode(C.WAKE_MODE_LOCAL);player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);player.setHandleAudioBecomingNoisy(true);
  player.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener(){@Override public void onAudioDecoderInitialized(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime t,String name,long at,long duration){decoder=name;}});
  player.addListener(new Player.Listener(){@Override public void onEvents(Player p,Player.Events events){changed();}@Override public void onPlayerError(PlaybackException failure){error=MediaFailure.describe(failure,false);changed();}@Override public void onRenderedFirstFrame(){if(censorRequested&&censorOverlay.lastTimestampUs>=0){censorSurfaceReady=true;changed();}}@Override public void onPlayWhenReadyChanged(boolean play,int reason){if(censorRequested&&clean==null&&!play&&(reason==Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS||reason==Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY))censorWanted=false;}});
  controls=new ForwardingPlayer(player){
   @Override public boolean getPlayWhenReady(){return wanted();}
   @Override public long getCurrentPosition(){return position();}
   @Override public long getContentPosition(){return position();}
   @Override public void seekTo(long at){PlaybackSession.this.seekTo(at);}
   @Override public void seekTo(int index,long at){if(index==0)PlaybackSession.this.seekTo(at);}
   @Override public void seekBack(){PlaybackSession.this.seekTo(position()-10000);}
   @Override public void seekForward(){PlaybackSession.this.seekTo(position()+10000);}
   @Override public int getPlaybackState(){return censorWaiting||clean!=null&&clean.waiting()?Player.STATE_BUFFERING:super.getPlaybackState();}
   @Override public long getBufferedPosition(){return bufferedPosition();}
   @Override public long getTotalBufferedDuration(){return Math.max(0,getBufferedPosition()-getCurrentPosition());}
   @Override public void setPlayWhenReady(boolean play){setWanted(play);}
   @Override public void play(){setWanted(true);}
   @Override public void pause(){setWanted(false);}
  };
 }
 void claimScreen(String owner){screenOwner=owner;}
 boolean ownsScreen(String owner){return owner.equals(screenOwner);}
 void observe(Observer listener){observer=listener;if(listener!=null)listener.changed();}
 void unobserve(Observer listener){if(observer==listener)observer=null;}
 private void changed(){if(!closed&&observer!=null)observer.changed();}
 void prepare(PlayerInput next,File captions,long position,boolean play){
  boolean same=player.getMediaItemCount()>0&&player.getPlayerError()==null&&player.getPlaybackState()!=Player.STATE_IDLE&&input.uri.equals(next.uri)&&java.util.Objects.equals(subtitle,captions);
  input=next;if(same)return;sources.setDataSourceFactory(input.http.factory(context));
  boolean wasClean=cleanRequested;closeClean();player.pause();player.setVolume(wasClean?0:volume);
  File old=subtitle;subtitle=captions;if(old!=null&&!old.equals(captions))old.delete();
  MediaItem.Builder media=new MediaItem.Builder().setUri(input.uri).setMediaMetadata(new MediaMetadata.Builder().setTitle(input.title).setArtist("مدار").build());if(input.mime!=null)media.setMimeType(input.mime);
  if(subtitle!=null)media.setSubtitleConfigurations(Collections.singletonList(new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subtitle)).setMimeType(subtitle.getName().endsWith(".vtt")?MimeTypes.TEXT_VTT:MimeTypes.APPLICATION_SUBRIP).setLanguage("ar").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()));
  error="";player.setMediaItem(media.build());player.setPlaybackParameters(new PlaybackParameters(speed,pitch));player.seekTo(position);player.prepare();
  if(censorRequested)censorWanted=play;
  if(wasClean&&cleanSession!=null)useClean(cleanSession,watchJob,legacyClean,position,play);else player.setPlayWhenReady(play&&!wasClean&&!censorRequested);
 }
 boolean wanted(){return clean!=null?clean.wanted():cleanRequested?preparingWanted:censorRequested?censorWanted:player.getPlayWhenReady()&&player.getPlaybackState()!=Player.STATE_ENDED;}
 long duration(){long n=player.getDuration();return n==C.TIME_UNSET||n<0?-1:n;}
 boolean buffering(){return wanted()&&(censorWaiting||(cleanRequested?(clean==null||clean.waiting()):player.getPlaybackState()==Player.STATE_BUFFERING||player.getPlaybackState()==Player.STATE_IDLE||player.getPlaybackSuppressionReason()!=Player.PLAYBACK_SUPPRESSION_REASON_NONE));}
 long bufferedPosition(){long at=cleanRequested&&cleanSession!=null?cleanSession.bufferedAt(position()):player.getBufferedPosition();if(censorRequested&&censorJob!=null)at=Math.min(at,censorJob.timeline.contiguousEnd(position()));long duration=duration();return Math.max(0,duration>=0?Math.min(at,duration):at);}
 void seekTo(long at){if(closed)return;long end=duration();at=Math.max(0,end>=0?Math.min(at,end):at);if(censorRequested){censorApproved=false;censorReserveMs=4000;if(censorJob!=null)censorJob.requestedMs=at;}if(clean!=null)clean.move(at);else player.seekTo(at);changed();}
 long position(){return clean!=null?clean.positionMs():player.getCurrentPosition();}
 void setWanted(boolean value){if(closed)return;if(clean!=null){if(value&&cleanSession.done&&clean.playedFrames()>=cleanSession.frames)clean.move(cleanSession.offsetUs/1000);clean.setWanted(value);}else if(cleanRequested)preparingWanted=value;else{if(value&&player.getPlaybackState()==Player.STATE_ENDED)seekTo(0);if(censorRequested){censorWanted=value;player.setPlayWhenReady(value&&censorPermit());}else player.setPlayWhenReady(value);}changed();}
 void useClean(LiveSeparation.Session source,WatchMusicService.Job job,boolean legacy,long position,boolean play){
  if(source==null||job!=null&&!job.usable())throw new IllegalStateException("أُلغيت جلسة الفصل؛ ابدأ فصلًا جديدًا");
  if(clean!=null&&cleanSession==source){clean.setWanted(play);return;}
  closeClean();if(cleanSession!=source)releaseSource();cleanSession=source;watchJob=job;legacyClean=legacy;cleanRequested=true;player.pause();player.setVolume(0);
  clean=new CleanAudioController(context,player,source,position,play,(text,wait)->{status=text;waiting=wait;changed();});clean.setPlaybackGate(()->!censorRequested||censorPermit());settings(speed,pitch,volume);changed();
 }
 void original(){
  boolean play=wanted();long at=position();closeClean();cleanRequested=false;waiting=false;status="";
  player.pause();player.seekTo(at);player.setAudioAttributes(player.getAudioAttributes(),true);player.setVolume(volume);if(censorRequested){censorWanted=play;player.setPlayWhenReady(play&&censorPermit());}else player.setPlayWhenReady(play);changed();
 }
 void waitForClean(){preparingWanted=wanted();closeClean();cleanRequested=true;player.setVolume(0);player.pause();waiting=true;changed();}
 void settings(float speed,float pitch,float volume){this.speed=speed;this.pitch=pitch;this.volume=volume;player.setPlaybackParameters(new PlaybackParameters(speed,pitch));if(clean!=null)clean.settings(speed,pitch,volume);else player.setVolume(cleanRequested?0:volume);}
 private void closeClean(){if(clean!=null){clean.close();clean=null;}}
 void useCensor(WatchCensorService.Job job){
  if(job==null||!job.input.identity.equals(input.identity))throw new IllegalArgumentException("بيانات الحجب تخص مقطعًا آخر");
  boolean play=wanted();long at=position();if(censorJob!=null&&censorJob!=job)WatchCensorService.release(censorJob);
  censorJob=job;job.watching=true;censorRequested=true;censorWanted=play;censorApproved=false;censorReserveMs=12000;censorSurfaceReady=false;
  censorOverlay.timeline=job.timeline;censorOverlay.enabled=true;censorOverlay.lastTimestampUs=-1;
  player.pause();player.stop();player.setVideoEffects(Collections.singletonList(new androidx.media3.effect.OverlayEffect(Collections.singletonList(censorOverlay))));
  prepare(input,subtitle,at,play);censorMain.removeCallbacksAndMessages(null);censorMain.post(censorTick);changed();
 }
 void stopCensor(){boolean play=wanted();censorRequested=false;censorWaiting=false;censorStatus="";censorOverlay.enabled=false;censorMain.removeCallbacksAndMessages(null);if(clean!=null)clean.setPlaybackGate(null);if(censorJob!=null)WatchCensorService.release(censorJob);censorJob=null;if(clean==null&&!cleanRequested)player.setPlayWhenReady(play);changed();}
 WatchCensorService.Job detachCensorForRebuild(){WatchCensorService.Job job=censorJob;censorJob=null;censorRequested=false;censorOverlay.enabled=false;censorMain.removeCallbacksAndMessages(null);return job;}
 private boolean censorPermit(){
  if(!censorRequested)return true;WatchCensorService.Job job=censorJob;if(job==null)return false;
  long at=position();job.requestedMs=at;long left=Math.max(0,job.durationMs-at);if(left==0)return true;
  if(job.timeline.boxesAt(at*1000)==null)return false;
  if(job.waitComplete&&(!job.done||job.failed))return false;
  long ahead=job.timeline.contiguousEnd(at)-at;
  if(!censorApproved){if(ahead<Math.min(left,Math.round(censorReserveMs*Math.max(1,speed))))return false;censorApproved=true;}
  if(ahead<300&&ahead<left){censorApproved=false;censorReserveMs=8000;return false;}
  return true;
 }
 boolean censorShielded(){return censorRequested&&(!censorSurfaceReady||censorJob==null||censorJob.timeline.boxesAt(position()*1000)==null);}
 private final Runnable censorTick=new Runnable(){@Override public void run(){if(closed||!censorRequested)return;
  boolean permit=censorPermit();if(clean==null&&!cleanRequested){if(player.getPlaybackState()==Player.STATE_ENDED)censorWanted=false;boolean desired=censorWanted&&permit;if(player.getPlayWhenReady()!=desired)player.setPlayWhenReady(desired);}
  censorWaiting=wanted()&&!permit;
  WatchCensorService.Job job=censorJob;
  censorStatus=job==null?"تجهيز بيانات الحجب":!job.error.isEmpty()?job.error:!job.timeline.failure.isEmpty()?job.timeline.failure:job.done?"حجب النساء · اكتمل التحليل":job.timeline.contiguousEnd(position())<=position()?job.stage:"حجب النساء · جاهز إلى "+WatchCensorService.stamp(job.timeline.contiguousEnd(position()))+(censorWaiting?" · تجهيز احتياطي للمشاهدة":"");
  long now=android.os.SystemClock.elapsedRealtime();if(now-censorUiAt>=250){censorUiAt=now;changed();}censorMain.postDelayed(this,30);
 }};
 private void releaseSource(){if(watchJob!=null)WatchMusicService.release(watchJob);else if(legacyClean&&cleanSession!=null)LiveSeparation.leave(cleanSession);watchJob=null;cleanSession=null;legacyClean=false;}
 void foreground(){Intent intent=new Intent(context,PlaybackService.class).putExtra("session",id);if(input.uri!=null&&"content".equals(input.uri.getScheme())){intent.setData(input.uri);intent.setClipData(ClipData.newRawUri("مقطع مدار",input.uri));intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);}context.startForegroundService(intent);}
 @Override public void close(){if(closed)return;closed=true;observer=null;context.getSharedPreferences("player",0).edit().putLong(input.key,position()).apply();censorMain.removeCallbacksAndMessages(null);if(censorJob!=null)WatchCensorService.release(censorJob);closeClean();releaseSource();player.release();if(subtitle!=null)subtitle.delete();if(current==this)current=null;}
}
