package com.madar.files;

import android.app.*;
import android.content.*;
import android.net.Uri;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual network/DOM/decoder regression checks, isolated from heavyweight model tests. */
public class PlayerSourceTest {
 private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
 private final Context context=instrumentation.getTargetContext();
 private byte[] asset(String name)throws Exception{try(InputStream in=instrumentation.getContext().getAssets().open(name)){return in.readAllBytes();}}
 private Object field(Object owner,String name)throws Exception{java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
 private MockResponse bytes(byte[] media,String type,RecordedRequest request){int at=0;String range=request.getHeader("Range");if(range!=null&&range.matches("bytes=[0-9]+-.*"))at=Integer.parseInt(range.substring(6,range.indexOf('-')));if(at>=media.length)return new MockResponse().setResponseCode(416);MockResponse response=new MockResponse().setHeader("Content-Type",type).setBody(new okio.Buffer().write(media,at,media.length-at));if(range!=null)response.setResponseCode(206).setHeader("Content-Range","bytes "+at+"-"+(media.length-1)+"/"+media.length);return response;}
 private PlayerActivity open(String uri)throws Exception{Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(PlayerActivity.class.getName(),null,false);try{instrumentation.startActivitySync(new Intent(context,OpenMediaActivity.class).setAction(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri),"video/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));PlayerActivity activity=(PlayerActivity)instrumentation.waitForMonitorWithTimeout(monitor,10000);assertNotNull(activity);return activity;}finally{instrumentation.removeMonitor(monitor);}}
 private void playing(PlayerActivity activity,long timeout)throws Exception{ExoPlayer player=(ExoPlayer)field(activity,"player");long until=System.currentTimeMillis()+timeout;final boolean[] ready={false};while(System.currentTimeMillis()<until){ExoPlayer current=(ExoPlayer)field(activity,"player");instrumentation.runOnMainSync(()->ready[0]=current.getDuration()>1000&&current.getCurrentPosition()>250);if(ready[0])return;Thread.sleep(40);}fail("Playback did not start: "+field(activity,"lastPlaybackError"));}
 @Test public void browserUserAgentRedirectCookiesAndCallerHeaderAliasesWork()throws Exception{
  assertTrue(MediaResolver.providerPage(Uri.parse("https://streamtape.to/v/Abc123/title.mp4")));assertFalse(MediaResolver.providerPage(Uri.parse("https://streamtape.to/get_video?id=Abc123")));
  byte[] media=asset("sample.mp4");AtomicInteger authorized=new AtomicInteger();
  Intent aliases=new Intent().setData(Uri.parse("https://media.example.invalid/stream|User-Agent=Custom%20Player&Referer=https%3A%2F%2Fref.example%2F")).putExtra("Cookie","ticket=caller");assertEquals("https://media.example.invalid/stream",ExternalMedia.uri(aliases).toString());assertEquals("Custom Player",ExternalMedia.headers(aliases).get("User-Agent"));assertEquals("ticket=caller",ExternalMedia.headers(aliases).get("Cookie"));assertEquals("https://ref.example/",ExternalMedia.headers(aliases).get("Referer"));
  try(MockWebServer server=new MockWebServer()){
   server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){if(!MediaHttp.USER_AGENT.equals(request.getHeader("User-Agent")))return new MockResponse().setResponseCode(403);if(request.getPath().startsWith("/entry"))return new MockResponse().setResponseCode(302).setHeader("Location","/movie").setHeader("Set-Cookie","ticket=ready; Path=/");if(!"ticket=ready".equals(request.getHeader("Cookie")))return new MockResponse().setResponseCode(403);authorized.incrementAndGet();return bytes(media,"video/mp4",request);}});server.start(java.net.InetAddress.getByName("127.0.0.1"),0);String entry=server.url("/entry?private=not-logged").newBuilder().host("127.0.0.1").build().toString();PlayerActivity activity=open(entry);try{playing(activity,15000);assertTrue(authorized.get()>0);PlayerInput input=(PlayerInput)field(activity,"input");assertEquals("ticket=ready",input.http.headersFor(input.uri).get("Cookie"));AudioPipeline.Decoded pcm=RemoteWatchAudio.decode(context,input.http,input.mime,400000,1500000,new AtomicBoolean());try{assertTrue(pcm.pcm.length()>40000);assertEquals(400000,pcm.offsetUs,1000);}finally{pcm.pcm.delete();}}finally{instrumentation.runOnMainSync(activity::finish);}
  }
 }
 @Test public void providerHtmlResolvesItsDynamicVideoWithoutCopyingOrExposingTheLink()throws Exception{
  byte[] media=asset("sample.mp4");AtomicBoolean pageCookie=new AtomicBoolean(),referrer=new AtomicBoolean();
  try(MockWebServer server=new MockWebServer()){
   server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){if(request.getPath().startsWith("/episode"))return new MockResponse().setHeader("Content-Type","text/html").setHeader("Set-Cookie","page=yes; Path=/").setBody("<!doctype html><html><body><div id='robotlink' hidden></div><script>setTimeout(()=>document.getElementById('robotlink').innerHTML=location.origin+('xxx/get_video?id=private').substring(3),200)</script></body></html>");if(request.getPath().startsWith("/get_video"))return new MockResponse().setResponseCode(302).setHeader("Location","/opaque-media?token=private");if(request.getPath().startsWith("/opaque-media")){pageCookie.set("page=yes".equals(request.getHeader("Cookie")));referrer.set(request.getHeader("Referer")!=null);if(!pageCookie.get())return new MockResponse().setResponseCode(403);return bytes(media,"video/mp4",request);}return new MockResponse().setResponseCode(404);}});server.start(java.net.InetAddress.getByName("127.0.0.1"),0);String original=server.url("/episode?private=not-logged").newBuilder().host("127.0.0.1").build().toString();PlayerActivity activity=open(original);try{playing(activity,35000);PlayerInput input=(PlayerInput)field(activity,"input");assertEquals(Uri.parse(original).toString(),input.identity);assertEquals("/opaque-media",input.uri.getPath());assertNull(input.file);assertTrue(pageCookie.get());assertTrue(referrer.get());String diagnostic=context.getSharedPreferences("player_diagnostics",0).getString("last","");assertFalse(diagnostic.contains("not-logged"));assertFalse(diagnostic.contains("page=yes"));}finally{instrumentation.runOnMainSync(activity::finish);}
  }
 }
 @Test public void opaqueHlsResponseIsDetectedFromContentRatherThanItsUrl()throws Exception{
  byte[] playlist=asset("watch-hls.m3u8"),segment=asset("watch-hls-000.ts");
  try(MockWebServer server=new MockWebServer()){
   server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){return request.getPath().startsWith("/stream")?bytes(playlist,"application/vnd.apple.mpegurl",request):bytes(segment,"video/mp2t",request);}});server.start(java.net.InetAddress.getByName("127.0.0.1"),0);PlayerActivity activity=open(server.url("/stream?id=episode").newBuilder().host("127.0.0.1").build().toString());try{playing(activity,20000);assertEquals("application/x-mpegURL",((PlayerInput)field(activity,"input")).mime);}finally{instrumentation.runOnMainSync(activity::finish);}
  }
 }
 @Test public void warmGpuLeaseReusesKernelAndLateCancellationCannotPoisonNextJob()throws Exception{
  File file=File.createTempFile("gpu-lease-",".mp4",context.getCacheDir());try(OutputStream out=new FileOutputStream(file)){out.write(asset("sample.mp4"));}
  PlayerActivity activity=(PlayerActivity)instrumentation.startActivitySync(new Intent(context,PlayerActivity.class).putExtra("path",file.getPath()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  String project=context.getSharedPreferences("colab_connection",0).getString("project","madarfiles");AtomicBoolean previous=new AtomicBoolean(),next=new AtomicBoolean();ColabMusic.Session second=null;
  try{playing(activity,12000);ColabMusic.Session first=MusicGpuSession.acquire(context,new ColabCredentials.Grant("test-only-memory-token",project),previous,(p,m)->{});first.kernel="retained-kernel";first.batch=7;first.healthy=true;first.close();assertTrue(MusicGpuSession.available(context));java.lang.reflect.Field generation=MusicGpuSession.class.getDeclaredField("generation");generation.setAccessible(true);long retired=generation.getLong(null);
   second=MusicGpuSession.acquire(context,null,next,(p,m)->{});assertSame(first,second);assertEquals("retained-kernel",second.kernel);assertEquals(7,second.batch);try(ColabRuntime other=new ColabRuntime(context,new ColabCredentials.Grant("test-only",project),new AtomicBoolean())){boolean blocked=false;try{MusicGpuSession.beforeRuntime(other);}catch(IOException expected){blocked=true;}assertTrue("Another tool must not delete the active GPU runtime",blocked);}previous.set(true);Thread.sleep(150);assertFalse(second.cancel.get());second.healthy=true;second.close();second=null;MusicGpuSession.expire(retired);assertTrue(MusicGpuSession.available(context));MusicGpuSession.stopIdle();assertFalse(MusicGpuSession.available(context));
  }finally{if(second!=null){second.healthy=false;second.close();}MusicGpuSession.stopIdle();instrumentation.runOnMainSync(activity::finish);file.delete();}
 }
 @Test public void softwareAudioReallyDecodesAdditionalFormatsAndCanSupplyPcmForSeparation()throws Exception{
  assertTrue(androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable());for(String type:new String[]{MimeTypes.AUDIO_AAC,MimeTypes.AUDIO_AC3,MimeTypes.AUDIO_E_AC3,MimeTypes.AUDIO_DTS,MimeTypes.AUDIO_TRUEHD,MimeTypes.AUDIO_FLAC,MimeTypes.AUDIO_ALAC,MimeTypes.AUDIO_OPUS,MimeTypes.AUDIO_VORBIS,MimeTypes.AUDIO_MPEG,MimeTypes.AUDIO_AMR_NB,MimeTypes.AUDIO_AMR_WB})assertTrue(type,androidx.media3.decoder.ffmpeg.FfmpegLibrary.supportsFormat(type));
  boolean previous=context.getSharedPreferences("player",0).getBoolean("software_audio",false);context.getSharedPreferences("player",0).edit().putBoolean("software_audio",true).commit();
  try{for(String name:new String[]{"codec-ac3.mkv","codec-eac3.mkv","codec-dca.mkv","codec-flac.mkv","codec-libopus.mkv","codec-libmp3lame.mkv","codec-mp2.mkv","codec-alac.m4a"}){
   File file=File.createTempFile("codec-",name.endsWith("m4a")?".m4a":".mkv",context.getCacheDir());try(OutputStream out=new FileOutputStream(file)){out.write(asset(name));}PlayerActivity activity=(PlayerActivity)instrumentation.startActivitySync(new Intent(context,PlayerActivity.class).putExtra("path",file.getPath()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   try{playing(activity,12000);assertTrue(name+": "+field(activity,"audioDecoder"),((String)field(activity,"audioDecoder")).startsWith("ffmpeg"));ExoPlayer player=(ExoPlayer)field(activity,"player");instrumentation.runOnMainSync(()->assertTrue(player.getAudioDecoderCounters().renderedOutputBufferCount>0));File output=File.createTempFile("software-pcm-",".pcm",context.getCacheDir());try{androidx.media3.decoder.ffmpeg.FfmpegPcm.Result result=androidx.media3.decoder.ffmpeg.FfmpegPcm.decode(file,output,400000,1400000,true,new AtomicBoolean());assertEquals(400000,result.offsetUs,2000);assertEquals(1.0,output.length()/(4.0*result.rate),.004);}finally{output.delete();}}finally{instrumentation.runOnMainSync(activity::finish);file.delete();}
  }}finally{context.getSharedPreferences("player",0).edit().putBoolean("software_audio",previous).commit();}
 }
}
