package com.madar.files;

import android.app.Activity;
import android.net.Uri;
import android.os.*;
import android.webkit.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONTokener;

/** Resolve a provider page to its real media request; no app/account bridge is exposed to the page. */
final class MediaResolver implements AutoCloseable {
 interface Callback {void ready(MediaHttp.Probe media);void failed(String message);}
 private final Activity activity;private final PlayerInput source;private final Callback callback;private final Handler main=new Handler(Looper.getMainLooper());
 private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"madar-media-resolver");t.setDaemon(true);return t;});
 private final Set<String> candidates=new HashSet<>();private WebView web;private android.app.AlertDialog dialog;private boolean closed,alternateTried;private int probes;private long began;
 MediaResolver(Activity activity,PlayerInput source,Callback callback){this.activity=activity;this.source=source;this.callback=callback;}
 void start(){if(streamtape(source.uri)){openPage();return;}began=SystemClock.elapsedRealtime();main.postDelayed(()->fail("لم يقدّم المصدر رابط فيديو قابلًا للتشغيل؛ أعد اختيار السيرفر من التطبيق المصدر"),45000);io.execute(()->{try{MediaHttp.Probe result=MediaHttp.probe(source.http,source.uri,source.http.headersFor(source.uri));main.post(()->{if(closed)return;if(result.mime!=null&&!result.html)complete(result);else openPage();});}catch(Exception error){main.post(this::openPage);}});}
 @SuppressWarnings("SetJavaScriptEnabled") private void openPage(){
  if(closed||web!=null||activity.isFinishing())return;
  try{web=new WebView(activity);web.setAlpha(0);activity.addContentView(web,new android.widget.FrameLayout.LayoutParams(1,1));WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);settings.setMediaPlaybackRequiresUserGesture(true);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setUserAgentString(source.headers.get("User-Agent"));
   CookieManager cookies=CookieManager.getInstance();cookies.setAcceptCookie(true);cookies.setAcceptThirdPartyCookies(web,true);String cookie=source.http.headersFor(source.uri).get("Cookie");if(cookie!=null)for(String part:cookie.split(";"))cookies.setCookie(source.uri.toString(),part.trim()+"; Path=/");
   web.setWebChromeClient(new WebChromeClient(){@Override public void onPermissionRequest(PermissionRequest request){request.deny();}@Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){callback.invoke(origin,false,false);}});
   web.setWebViewClient(new WebViewClient(){
    @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !ExternalMedia.network(request.getUrl());}
    @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){Uri uri=request.getUrl();if(isAd(uri))return new WebResourceResponse("text/plain","utf-8",new java.io.ByteArrayInputStream(new byte[0]));if(ExternalMedia.network(uri)&&MediaHttp.looksPlayable(uri)){Map<String,String> headers=new HashMap<>(request.getRequestHeaders());main.post(()->candidate(uri,headers));}return null;}
    @Override public void onPageFinished(WebView view,String url){inspect();}
    @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame()){if(streamtape(source.uri)&&!alternateTried&&!"streamtape.com".equals(source.uri.getHost())){alternateTried=true;Uri alternate=embed(source.uri).buildUpon().authority("streamtape.com").build();view.loadUrl(alternate.toString());}else fail("تعذرت قراءة صفحة المصدر · WebView "+error.getErrorCode()+"؛ أعد اختيار السيرفر");}}
    @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,android.net.http.SslError error){handler.cancel();fail("تعذر التحقق من شهادة المصدر الآمنة");}
   });
   web.setDownloadListener((url,userAgent,contentDisposition,mimetype,length)->candidate(Uri.parse(url),Collections.singletonMap("User-Agent",userAgent)));
   Uri page=streamtape(source.uri)?embed(source.uri):source.uri;web.loadUrl(page.toString(),source.http.headersFor(page));main.postDelayed(()->fail("انتهت مهلة تجهيز المصدر؛ أعد المحاولة أو اختر سيرفرًا آخر"),60000);main.postDelayed(poll,800);main.postDelayed(this::showPage,2000);
  }catch(Exception error){fail("تعذر تجهيز صفحة الوسائط على الجهاز");}
 }
 static boolean providerPage(Uri uri){return streamtape(uri)&&!uri.getPathSegments().isEmpty()&&java.util.Arrays.asList("e","v").contains(uri.getPathSegments().get(0));}
 static boolean streamtape(Uri uri){return uri.getHost()!=null&&java.util.Arrays.asList("streamtape.to","streamtape.com").contains(uri.getHost().toLowerCase(java.util.Locale.ROOT));}
 private static Uri embed(Uri uri){java.util.List<String> p=uri.getPathSegments();if(p.size()>=2&&(p.get(0).equals("v")||p.get(0).equals("e"))&&p.get(1).matches("[A-Za-z0-9]+"))return uri.buildUpon().path("/e/"+p.get(1)).build();return uri;}
 private static boolean isAd(Uri uri){String host=uri.getHost();if(host==null)return false;for(String domain:new String[]{"doubleclick.net","googlesyndication.com","googleadservices.com","adnxs.com"})if(host.equals(domain)||host.endsWith("."+domain))return true;return false;}
 private void showPage(){if(closed||web==null||dialog!=null||activity.isFinishing())return;if(web.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)web.getParent()).removeView(web);web.setAlpha(1);dialog=new android.app.AlertDialog.Builder(activity).setTitle("تجهيز الفيديو من المصدر").setMessage("إذا طلب المصدر تفاعلًا، أكمله هنا. يعود مدار تلقائيًا إلى المشغّل عند اكتشاف الفيديو.").setView(web).setNegativeButton("رجوع",(d,n)->fail("أوقفت تجهيز المصدر")).setOnCancelListener(d->fail("أوقفت تجهيز المصدر")).create();dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,(int)(activity.getResources().getDisplayMetrics().heightPixels*.85));}
 private final Runnable poll=new Runnable(){public void run(){if(closed)return;inspect();main.postDelayed(this,800);}};
 private void inspect(){if(closed||web==null)return;web.evaluateJavascript("(()=>{for(const m of document.querySelectorAll('video,audio'))m.muted=true;const links=Array.from(document.querySelectorAll('video,video source')).map(v=>v.currentSrc||v.src);for(const id of ['robotlink','videolink']){const e=document.getElementById(id);if(e&&e.textContent.trim())try{links.push(new URL(e.textContent.trim(),location.href).href)}catch(_){}}return JSON.stringify(links.filter(Boolean));})()",value->{if(closed)return;try{Object decoded=new JSONTokener(value).nextValue();if(!(decoded instanceof String))return;JSONArray urls=new JSONArray((String)decoded);for(int i=0;i<Math.min(8,urls.length());i++){String url=urls.optString(i);if(!url.startsWith("blob:"))candidate(Uri.parse(url),Collections.emptyMap());}}catch(Exception ignored){}});}
 private void candidate(Uri uri,Map<String,String> observed){
  if(closed||!ExternalMedia.network(uri)||probes>=24||!candidates.add(uri.toString()))return;String host=uri.getHost();if(host==null||host.matches("(?i).*(doubleclick|googlesyndication|googleadservices|googletagmanager|adnxs)\\..*"))return;probes++;
  android.content.Intent safe=new android.content.Intent().putExtra("android.media.intent.extra.HTTP_HEADERS",ExternalMedia.bundle(observed));Map<String,String> headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);headers.putAll(source.http.headersFor(uri));headers.putAll(ExternalMedia.headers(safe));if(web!=null)headers.put("User-Agent",web.getSettings().getUserAgentString());String cookie=CookieManager.getInstance().getCookie(uri.toString());if(cookie!=null&&cookie.length()<=8192)headers.put("Cookie",cookie);headers.putIfAbsent("Referer",Objects.equals(source.uri.getHost(),uri.getHost())?source.uri.toString():source.uri.buildUpon().encodedPath("/").clearQuery().fragment(null).build().toString());
  io.execute(()->{try{MediaHttp.Session session=new MediaHttp.Session(uri,headers);MediaHttp.Probe result=MediaHttp.probe(session,uri,session.headersFor(uri));if(result.mime!=null&&!result.html)main.post(()->complete(result));}catch(Exception ignored){}});
 }
 private void complete(MediaHttp.Probe media){if(closed)return;close();callback.ready(media);}
 private void fail(String message){if(closed)return;close();callback.failed(message);}
 @Override public void close(){if(closed)return;closed=true;main.removeCallbacksAndMessages(null);if(dialog!=null){dialog.dismiss();dialog=null;}if(web!=null){web.stopLoading();web.loadUrl("about:blank");if(web.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)web.getParent()).removeView(web);web.destroy();web=null;}io.shutdownNow();}
}
