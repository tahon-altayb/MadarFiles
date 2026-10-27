package com.madar.files;
import android.app.*;
import android.os.*;
import android.content.*;
import android.media.*;
import android.view.*;
import android.widget.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.ui.PlayerView;
import java.io.*;
/** Video is always muted. A bounded, verified PCM prefix supplies the only audio. */
public class SeparationPlayerActivity extends Activity {
 private LiveSeparation.Session session;private ExoPlayer video;private AudioTrack audio;private final Handler timer=new Handler(Looper.getMainLooper());private TextView status;private SeekBar seek;private boolean wanted=true,playing,closed;private long cursor,baseFrame;private Button toggle;private RandomAccessFile input;
 @Override public void onCreate(Bundle b){super.onCreate(b);session=LiveSeparation.current;if(session==null||!session.id.equals(getIntent().getStringExtra("session"))){finish();return;}session.watching=true;LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(16,16,16,16);root.setBackgroundColor(0xff10251e);status=new TextView(this);status.setTextColor(0xfffaf5e8);root.addView(status);video=new ExoPlayer.Builder(this).build();video.setVolume(0);video.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(session.source)));video.prepare();PlayerView view=new PlayerView(this);view.setPlayer(video);view.setUseController(false);root.addView(view,new LinearLayout.LayoutParams(-1,0,1));seek=new SeekBar(this);seek.setMax(1000);root.addView(seek);seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){pause();}public void onProgressChanged(SeekBar s,int n,boolean user){if(user)move(session.ready()*n/1000.0);}public void onStopTrackingTouch(SeekBar s){wanted=true;}});toggle=new Button(this);toggle.setText("إيقاف مؤقت");toggle.setOnClickListener(v->{wanted=!wanted;if(!wanted)pause();toggle.setText(wanted?"إيقاف مؤقت":"متابعة");});root.addView(toggle);Button back=new Button(this);back.setText("العودة · يستمر الفصل في الخلفية");back.setOnClickListener(v->finish());root.addView(back);setContentView(root);
  audio=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(17640,AudioTrack.getMinBufferSize(44100,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT))).build();timer.post(tick);
 }
 private void pause(){playing=false;video.pause();audio.pause();}
 private void move(double seconds){pause();audio.flush();cursor=Math.max(0,Math.min(session.frames,(long)((seconds-session.offsetUs/1e6)*44100)));baseFrame=cursor;video.seekTo((long)(1000*(session.offsetUs/1e6+cursor/44100.0)));}
 private final Runnable tick=new Runnable(){public void run(){if(closed)return;try{
  double played=session.offsetUs/1e6+(baseFrame+(audio.getPlaybackHeadPosition()&0xffffffffL))/44100.0;double ready=session.ready();status.setText(session.failed?"توقف الفصل: "+session.error:session.done?"اكتمل الفصل · الصوت المعالج فقط":"جاهز للمشاهدة حتى "+String.format(java.util.Locale.US,"%.1f",ready)+" ثانية · ينتظر المقطع الجزء التالي تلقائيًا");
  if(session.pcm!=null&&input==null)input=new RandomAccessFile(session.pcm,"r");
  if(wanted&&input!=null&&session.frames>cursor){if(!playing&&(ready-played>.5||session.done)){playing=true;video.seekTo((long)(played*1000));video.play();audio.play();}if(playing){int frames=(int)Math.min(882,session.frames-cursor);byte[] data=new byte[frames*4];input.seek(cursor*4);input.readFully(data);int written=audio.write(data,0,data.length,AudioTrack.WRITE_NON_BLOCKING);if(written>0)cursor+=written/4;}}
  if(playing){if(ready-played<(session.done?.001:.05)){pause();if(session.done)wanted=false;}else if(session.video&&Math.abs(video.getCurrentPosition()/1000.0-played)>.12)video.seekTo((long)(played*1000));}
  if(!seek.isPressed()&&ready>0)seek.setProgress((int)(1000*Math.max(0,Math.min(ready,played))/ready));
 }catch(Exception e){pause();wanted=false;status.setText("تعذرت المتابعة: "+e.getMessage());}timer.postDelayed(this,15);}};
 @Override protected void onPause(){super.onPause();wanted=false;if(audio!=null)pause();if(toggle!=null)toggle.setText("متابعة");}
 @Override protected void onDestroy(){closed=true;timer.removeCallbacksAndMessages(null);if(video!=null)video.release();if(audio!=null)audio.release();try{if(input!=null)input.close();}catch(Exception ignored){}if(session!=null)LiveSeparation.leave(session);super.onDestroy();}
}
