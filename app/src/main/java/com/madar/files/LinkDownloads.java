package com.madar.files;

import android.content.Context;
import com.yausername.youtubedl_android.*;
import com.yausername.ffmpeg.FFmpeg;
import org.json.*;
import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Platform URLs go to yt-dlp as arguments, never through a shell. */
final class LinkDownloads {
 static final String PROCESS="madar-link-transfer";
 interface Progress{void update(int percent,String text);}
 static String url(String text)throws Exception{java.util.regex.Matcher match=java.util.regex.Pattern.compile("https?://[^\\s<>\"']+",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text.trim());if(!match.find())throw new IOException("ألصق رابط المقطع من إحدى منصات التواصل المدعومة");String url=match.group().replaceAll("[)\\]،؛]+$","");URI u=new URI(url);String host=u.getHost();if(!"https".equalsIgnoreCase(u.getScheme())||u.getUserInfo()!=null||u.getPort()!=-1||host==null)throw new IOException("استخدم رابط HTTPS صالحًا من المنصة");boolean allowed=false;for(String domain:new String[]{"youtube.com","youtu.be","tiktok.com","x.com","twitter.com","instagram.com","snapchat.com","facebook.com","fb.watch","fb.com","vimeo.com","reddit.com","redd.it","pinterest.com","pin.it","dailymotion.com","dai.ly","twitch.tv","clips.twitch.tv"})if(host.equalsIgnoreCase(domain)||host.toLowerCase(Locale.ROOT).endsWith("."+domain))allowed=true;if(!allowed)throw new IOException("الرابط ليس من المنصات المدعومة");return url;}
 static synchronized void init(Context c)throws Exception{YoutubeDL.INSTANCE.init(c);FFmpeg.INSTANCE.init(c);}
 static void cancel(){try{YoutubeDL.INSTANCE.destroyProcessById(PROCESS);}catch(Exception ignored){}}
 static YoutubeDLRequest request(String url){return options(new YoutubeDLRequest(url));}
 /** Where the engine keeps what it learned from YouTube's player (its code and the solved challenge), between readings. */
 static File engineCache(Context c){File dir=new File(c.getFilesDir(),"yt-dlp-cache");dir.mkdirs();return dir;}
 static int cachedFiles(File dir){int n=0;File[] list=dir.listFiles();if(list!=null)for(File f:list)n+=f.isDirectory()?cachedFiles(f):1;return n;}
 private static Context appContext;
 private static YoutubeDLRequest options(YoutubeDLRequest r){
  // Reading a YouTube page took 96 s on a tablet: most of it is fetching the site's player code and solving its
  // challenge, which the engine keeps and reuses only when it has a folder for it (the app gave it none).
  if(appContext!=null)r.addOption("--cache-dir",engineCache(appContext).getAbsolutePath());
  // IPv4 only. His tablet's readings took 96-100 s every time with nothing cached, and a download sat 21 s before
  // each of its two parts and then crawled at 0.4 MB/s: whole multiples of the 20 s connect timeout, the mark of a
  // network that offers IPv6 but does not carry it, where each connection waits for IPv6 to fail before using IPv4.
  r.addOption("--force-ipv4");
  r.addOption("--no-playlist");r.addOption("--no-warnings");r.addOption("--socket-timeout","20");r.addOption("--retries","3");r.addOption("--fragment-retries","3");return r;}
 /** What the last reading of a link found, kept for twenty minutes: the download starts from it instead of reading the page a second time. */
 private static File remembered(Context c){return new File(c.getCacheDir(),"link-info.json");}
 static void remember(Context c,String url,String json){
  try{File file=remembered(c);try(OutputStream out=new FileOutputStream(file)){out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));}c.getSharedPreferences("link_downloads",0).edit().putString("info_url",url).putLong("info_at",System.currentTimeMillis()).apply();}
  catch(Exception ignored){remembered(c).delete();}
 }
 static boolean fresh(Context c,String url,long now){android.content.SharedPreferences saved=c.getSharedPreferences("link_downloads",0);long at=saved.getLong("info_at",0);return remembered(c).isFile()&&remembered(c).length()>2&&url.equals(saved.getString("info_url",""))&&now>=at&&now-at<20L*60000;}
 static void update(Context c)throws Exception{appContext=c.getApplicationContext();init(c);YoutubeDL.INSTANCE.updateYoutubeDL(c,YoutubeDL.UpdateChannel.STABLE.INSTANCE);c.getSharedPreferences("link_downloads",0).edit().putLong("updated",System.currentTimeMillis()).apply();}
 static boolean maybeUpdate(Context c,Progress progress){if(System.currentTimeMillis()-c.getSharedPreferences("link_downloads",0).getLong("updated",0)>7L*86400000&&System.currentTimeMillis()-c.getSharedPreferences("link_downloads",0).getLong("attempted",0)>3600000)try{c.getSharedPreferences("link_downloads",0).edit().putLong("attempted",System.currentTimeMillis()).apply();progress.update(0,"تحديث محرك الروابط…");update(c);return true;}catch(Exception ignored){/* Bundled engine remains usable when update service is unavailable. */return true;}return false;}
 static JSONObject inspect(Context c,String value,AtomicBoolean cancel,Progress progress)throws Exception{
  LinkReport report=new LinkReport(c,"قراءة الجودات");try{return inspect(c,value,cancel,progress,report);}catch(Exception e){report.failed(e);throw e;}
 }
 private static JSONObject inspect(Context c,String value,AtomicBoolean cancel,Progress progress,LinkReport report)throws Exception{
  appContext=c.getApplicationContext();long at=android.os.SystemClock.elapsedRealtime();String url=url(value);init(c);report.measure("ENGINE_START",android.os.SystemClock.elapsedRealtime()-at);
  at=android.os.SystemClock.elapsedRealtime();boolean checked=maybeUpdate(c,progress);if(checked)report.measure("ENGINE_UPDATE",android.os.SystemClock.elapsedRealtime()-at);
  ColabProbe.check(cancel);YoutubeDLRequest r=request(url);r.addOption("--dump-single-json");r.addOption("--skip-download");at=android.os.SystemClock.elapsedRealtime();report.mark("PAGE_READING · محفوظ من قبل "+cachedFiles(engineCache(c))+" ملف");
  String text=YoutubeDL.INSTANCE.execute(r,PROCESS,null).getOut();report.measure("PAGE_READ",android.os.SystemClock.elapsedRealtime()-at);ColabProbe.check(cancel);if(text.length()>16*1024*1024)throw new IOException("بيانات المقطع أكبر من الحد");JSONObject data=new JSONObject(text);remember(c,url,text);if(data.optBoolean("is_live")||data.optString("_type").equals("playlist"))throw new IOException("اختر مقطعًا مسجلًا واحدًا؛ البث المباشر والقوائم غير مدعومين هنا");
  JSONArray source=data.optJSONArray("formats"),formats=new JSONArray();Set<Integer> heights=new TreeSet<>(Collections.reverseOrder());if(source!=null)for(int i=0;i<source.length();i++){JSONObject f=source.getJSONObject(i);int height=f.optInt("height");if(height>0&&height<=4320&&!f.optString("vcodec","none").equals("none")&&!f.optBoolean("has_drm"))heights.add(height);}for(int height:heights)formats.put(new JSONObject().put("height",height).put("label",height+"p"));
  JSONObject result=new JSONObject().put("url",url).put("title",data.optString("title","مقطع").substring(0,Math.min(300,data.optString("title","مقطع").length()))).put("duration",data.optDouble("duration",0)).put("formats",formats).put("extractor",data.optString("extractor_key",""));
  String thumb=data.optString("thumbnail");try{URI imageUri=new URI(thumb);String imageHost=imageUri.getHost();boolean safe=false;if(imageHost!=null&&"https".equals(imageUri.getScheme()))for(String domain:new String[]{"ytimg.com","tiktokcdn.com","tiktokcdn-us.com","tiktokcdn-eu.com","twimg.com","cdninstagram.com","fbcdn.net"})if(imageHost.equals(domain)||imageHost.endsWith("."+domain))safe=true;if(safe){java.net.URLConnection connection=imageUri.toURL().openConnection();connection.setConnectTimeout(8000);connection.setReadTimeout(8000);try(InputStream in=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){ColabProbe.check(cancel);if(bytes.size()+n>2*1024*1024)throw new IOException();bytes.write(buffer,0,n);}android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes.toByteArray(),0,bytes.size());if(bitmap!=null){android.graphics.Bitmap small=android.graphics.Bitmap.createScaledBitmap(bitmap,320,Math.max(1,bitmap.getHeight()*320/bitmap.getWidth()),true);result.put("thumbnail",FilesUtil.thumbnail(small));if(small!=bitmap)small.recycle();bitmap.recycle();}}}}catch(Exception ignored){ColabProbe.check(cancel);}
  report.finished(0);return result;
 }
 static File download(Context c,JSONObject options,AtomicBoolean cancel,Progress progress)throws Exception{
  LinkReport report=new LinkReport(c,options.optString("mode","video").equals("audio")?"صوت":"فيديو "+options.optInt("height",0)+"p");
  try{return download(c,options,cancel,progress,report);}catch(Exception e){report.failed(e);throw e;}
 }
 private static File download(Context c,JSONObject options,AtomicBoolean cancel,Progress progress,LinkReport report)throws Exception{
  appContext=c.getApplicationContext();
  String url=url(options.getString("url")),mode=options.optString("mode","video");int height=options.optInt("height",0);if(!Arrays.asList("video","audio").contains(mode)||height<0||height>4320)throw new IOException("خيارات التحميل غير صالحة");if(c.getFilesDir().getUsableSpace()<160L*1024*1024)throw new IOException("وفّر مساحة تخزين قبل التحميل");init(c);ColabProbe.check(cancel);File folder=new File(c.getCacheDir(),"link-"+UUID.randomUUID());if(!folder.mkdir())throw new IOException("تعذر تجهيز مجلد التحميل");boolean success=false;long seconds=0;
  try{
   // A slow tablet spent most of a download reading the page twice and fetching one piece at a time: the reading the
   // quality orbit was made from is used again (the page is read anew when that fails), and four pieces come at once.
   boolean reuse=fresh(c,url,System.currentTimeMillis());long began=android.os.SystemClock.elapsedRealtime();report.mark(reuse?"PAGE_REUSED":"PAGE_NOT_REUSED");
   for(int attempt=reuse?0:1;;attempt++){
    YoutubeDLRequest r=attempt==0?options(new YoutubeDLRequest(new java.util.ArrayList<String>())):request(url);if(attempt==0)r.addOption("--load-info-json",remembered(c).getAbsolutePath());
    r.addOption("--concurrent-fragments","4");r.addOption("--http-chunk-size","10M");
    r.addOption("--max-filesize",Math.min(1024L*1024*1024,c.getFilesDir().getUsableSpace()/5));r.addOption("--no-mtime");r.addOption("--restrict-filenames");r.addOption("-o",new File(folder,"media.%(ext)s").getAbsolutePath());if(mode.equals("audio")){r.addOption("-f","bestaudio/best");r.addOption("-x");r.addOption("--audio-format","mp3");r.addOption("--audio-quality","0");}else{String bound=height==0?"":"[height<="+height+"]";// H.264 with AAC first, in .mp4: every phone plays it, WhatsApp takes it, and Madar's tools (music removal keeps the
   // picture as it is) can save it. A YouTube clip came as AV1 in .mkv, and its music removal failed when saving.
   // Other codecs only when the chosen height has no H.264.
   r.addOption("-f","bestvideo"+bound+"[vcodec^=avc1]+bestaudio[ext=m4a]/bestvideo"+bound+"[vcodec^=avc1]+bestaudio/best"+bound+"[ext=mp4]/bestvideo"+bound+"+bestaudio/best"+bound);r.addOption("--merge-output-format","mp4/mkv");}
    try{
     YoutubeDL.INSTANCE.execute(r,PROCESS,(p,eta,line)->{report.line(line);if(cancel.get())cancel();else progress.update(Math.max(0,Math.min(99,p.intValue())),eta>0?"التحميل · متبقٍ نحو "+eta+" ثانية":"تحميل المقطع وتجهيز الملف");return kotlin.Unit.INSTANCE;});
     break;
    }catch(Exception failed){
     if(attempt!=0||cancel.get())throw failed;
     report.mark("PAGE_REUSE_FAILED");remembered(c).delete();File[] partial=folder.listFiles();if(partial!=null)for(File f:partial)f.delete();progress.update(0,"قراءة المقطع من جديد");
    }
   }
   seconds=(android.os.SystemClock.elapsedRealtime()-began)/1000;
   ColabProbe.check(cancel);File[] files=folder.listFiles(f->f.isFile()&&f.getName().matches("media\\.(mp4|mkv|webm|mov|m4a|mp3|ogg|opus)"));if(files==null||files.length!=1||files[0].length()==0)throw new IOException("لم ينتج المحرك ملفًا مكتملًا");String ext=files[0].getName().substring(files[0].getName().lastIndexOf('.')+1);File out=FilesUtil.output(c,ext);report.mark("ENGINE_DONE · "+ext.toUpperCase(java.util.Locale.ROOT));if(!files[0].renameTo(out)){try(InputStream in=new FileInputStream(files[0]);OutputStream dest=new FileOutputStream(out)){FilesUtil.copy(in,dest);}catch(Exception e){out.delete();throw e;}}success=true;report.finished(out.length());progress.update(100,"اكتمل التحميل"+(seconds>0?" · "+seconds+" ثانية":""));return out;
  }finally{File[] files=folder.listFiles();if(files!=null)for(File f:files)f.delete();folder.delete();}
 }
 static String failure(Exception e){String text=String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);if(text.contains("private")||text.contains("login")||text.contains("sign in")||text.contains("cookies"))return "هذا الرابط خاص أو يحتاج تسجيل دخول؛ افتح رابطًا عامًا متاحًا لك";if(text.contains("copyright")||text.contains("drm"))return "المقطع محمي أو غير متاح للتنزيل";if(text.contains("unsupported")||text.contains("extract"))return "تعذر قراءة الرابط؛ جرّب تحديث محرك التحميل أو شارك رابط المقطع الأصلي";if(text.contains("geo")||text.contains("country"))return "المقطع غير متاح في منطقتك";if(e instanceof IOException&&!text.contains("http")&&!text.contains("https"))return e.getMessage();return "تعذر التحميل؛ تحقق من اتصالك وإتاحة المقطع ثم حدّث المحرك وأعد المحاولة";}
}
