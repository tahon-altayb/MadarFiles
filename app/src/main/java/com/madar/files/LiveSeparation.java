package com.madar.files;
import java.io.*;
/** Single personal playback session. Only committed PCM frames are readable. */
final class LiveSeparation {
 static volatile Session current;
 static final class Session {final String id=java.util.UUID.randomUUID().toString();final File source;final boolean video;volatile File pcm;volatile long frames,playedFrames;volatile long offsetUs;volatile boolean done,failed,watching;volatile String error="";volatile WatchBuffer buffer;volatile boolean waitForComplete;Session(File f,boolean v){source=f;video=v;}double ready(){return offsetUs/1e6+frames/44100.0;}}
 static synchronized Session begin(File source,boolean video){Session old=current;if(old!=null&&old.done&&!old.watching&&old.pcm!=null)old.pcm.delete();return current=new Session(source,video);}
 static synchronized void attach(Session s,File pcm,long offset){s.pcm=pcm;s.offsetUs=offset;}
 static synchronized void publish(Session s,long frames){if(s.pcm==null||frames<0||frames>s.pcm.length()/4||frames<s.frames)throw new IllegalArgumentException("PCM commit order");if(frames>s.frames&&s.buffer!=null)s.buffer.commit(frames/44100.0,android.os.SystemClock.elapsedRealtime());s.frames=frames;}
 static synchronized boolean retain(File f){Session s=current;return s!=null&&f.equals(s.pcm);}
 static synchronized void end(Session s,String error){s.error=error==null?"":error;s.failed=error!=null;s.done=true;}
 static synchronized void leave(Session s){s.watching=false;if(s.done&&s.pcm!=null){s.pcm.delete();if(current==s)current=null;}}
}
