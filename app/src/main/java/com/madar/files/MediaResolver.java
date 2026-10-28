package com.madar.files;

import android.app.Activity;
import android.net.Uri;
import android.os.*;
import android.webkit.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONTokener;

/** Browser execution has no native bridge. Native fetch recovers WebView transport failures. */
final class MediaResolver implements AutoCloseable {
 interface Callback {void ready(MediaHttp.Probe media);void failed(String message);}
 private final Activity activity;private final PlayerInput source;private final Callback callback;private final Handler main=new Handler(Looper.getMainLooper());
 private final ExecutorService io=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"madar-media-resolver");t.setDaemon(true);return t;});
 private final Set<String> candidates=new HashSet<>(),pages=new HashSet<>();private final List<MediaHttp.Session> sessions=Collections.synchronizedList(new ArrayList<>());
 private WebView web;private android.app.AlertDialog dialog;private volatile boolean closed;private boolean alternateTried,nativePending,nativeLoaded;private int probes;private Uri page;private String failure="لم يقدّم المصدر رابط فيديو قابلًا للتشغيل؛ أعد اختيار السيرفر";
 MediaResolver(Activity activity,PlayerInput source,Callback callback){this.activity=activity;this.source=source;this.callback=callback;page=secureProvider(source.uri);}
 void start(){
  main.postDelayed(()->fail(failure),45000);
  if(providerPage(page)){openPage();fetchPage(page);return;}
  io.execute(()->{try{MediaHttp.Probe result=MediaHttp.probe(source.http,page,source.http.headersFor(page));main.post(()->{if(closed)return;if(result.mime!=null&&!result.html)complete(result);else openPage();});}catch(Exception error){main.post(this::openPage);}});
 }
 @SuppressWarnings("SetJavaScriptEnabled") private void openPage(){
  if(closed||web!=null||activity.isFinishing())return;
  try{
   web=new WebView(activity);web.setAlpha(0);activity.addContentView(web,new android.widget.FrameLayout.LayoutParams(1,1));WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(true);settings.setMediaPlaybackRequiresUserGesture(true);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setUserAgentString(source.http.headers.get("User-Agent"));
   CookieManager cookies=CookieManager.getInstance();cookies.setAcceptCookie(true);cookies.setAcceptThirdPartyCookies(web,true);copyCookies(source.http,page);
   web.setWebChromeClient(new WebChromeClient(){
    @Override public boolean onCreateWindow(WebView view,boolean dialog,boolean gesture,android.os.Message result){return false;}
    @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
    @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){callback.invoke(origin,false,false);}
   });
   web.setWebViewClient(new WebViewClient(){
    @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
     Uri uri=request.getUrl();if(!ExternalMedia.network(uri))return true;
     if(request.isForMainFrame()&&MediaHttp.looksPlayable(uri)){candidate(uri,request.getRequestHeaders());return true;}
     // A same-window advertisement must never replace the provider and abort resolution.
     return request.isForMainFrame()&&!allowedNavigation(page,uri);
    }
    @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){Uri uri=request.getUrl();if(isAd(uri))return new WebResourceResponse("text/plain","utf-8",new java.io.ByteArrayInputStream(new byte[0]));if(ExternalMedia.network(uri)&&MediaHttp.looksPlayable(uri)){Map<String,String> headers=new HashMap<>(request.getRequestHeaders());main.post(()->candidate(uri,headers));}return null;}
    @Override public void onPageFinished(WebView view,String url){inspect();}
    @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame())pageError(error.getErrorCode());}
    @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,android.net.http.SslError error){handler.cancel();if(error.getUrl()!=null&&error.getUrl().equals(view.getUrl()))pageError(ERROR_FAILED_SSL_HANDSHAKE);}
   });
   web.setDownloadListener((url,userAgent,contentDisposition,mimetype,length)->candidate(Uri.parse(url),Collections.singletonMap("User-Agent",userAgent)));
   web.loadUrl(page.toString(),source.http.headersFor(page));main.postDelayed(poll,300);main.postDelayed(this::showPage,3000);
  }catch(Exception error){fail("تعذر تجهيز صفحة الوسائط على الجهاز");}
 }
 static boolean providerPage(Uri uri){return streamtape(uri)&&!uri.getPathSegments().isEmpty()&&Arrays.asList("e","v").contains(uri.getPathSegments().get(0));}
 static boolean streamtape(Uri uri){String host=uri.getHost();return host!=null&&Arrays.asList("streamtape.to","streamtape.com").contains(host.toLowerCase(Locale.ROOT));}
 static Uri secureProvider(Uri uri){return streamtape(uri)&&"http".equalsIgnoreCase(uri.getScheme())?uri.buildUpon().scheme("https").build():uri;}
 static boolean allowedNavigation(Uri origin,Uri target){return ExternalMedia.network(target)&&(Objects.equals(origin.getHost(),target.getHost())||streamtape(origin)&&streamtape(target));}
 private static Uri embed(Uri uri){List<String> p=uri.getPathSegments();if(p.size()>=2&&(p.get(0).equals("v")||p.get(0).equals("e"))&&p.get(1).matches("[A-Za-z0-9]+"))return secureProvider(uri).buildUpon().path("/e/"+p.get(1)).build();return secureProvider(uri);}
 private static boolean isAd(Uri uri){String host=uri.getHost();if(host==null)return false;for(String domain:new String[]{"doubleclick.net","googlesyndication.com","googleadservices.com","adnxs.com"})if(host.equals(domain)||host.endsWith("."+domain))return true;return false;}
 /** Does not fail an already running media probe just because a page navigation failed. */
 void pageError(int code){
  if(closed)return;failure="تعذر الاتصال بصفحة المصدر · WebView "+code+"؛ لم يُكتشف رابط فيديو من الشبكة الحالية";
  if(!nativeLoaded)fetchPage(page);
  if(!nativePending&&!nativeLoaded)alternate();
 }
 private void alternate(){
  if(closed||alternateTried||!streamtape(page))return;alternateTried=true;
  Uri next=embed(source.uri).buildUpon().authority("streamtape.com".equals(page.getHost())?"streamtape.to":"streamtape.com").build();page=next;if(web!=null)web.loadUrl(next.toString(),source.http.headersFor(next));fetchPage(next);
 }
 /** Fetch through OkHttp (normal TLS, redirects, cookies), then execute the actual page's scripts. */
 private void fetchPage(Uri target){
  if(closed||nativePending||!pages.add(target.toString()))return;nativePending=true;
  io.execute(()->{MediaHttp.Session session=new MediaHttp.Session(target,source.http.headersFor(target));sessions.add(session);
   try{MediaHttp.Page result=MediaHttp.page(session,target);main.post(()->{if(closed)return;nativePending=false;
    if(result.media!=null){complete(result.media);return;}
    if(web==null)openPage();if(web==null||nativeLoaded)return;
    nativeLoaded=true;page=result.uri;copyCookies(session,result.uri);web.stopLoading();web.loadDataWithBaseURL(result.uri.toString(),result.html,"text/html","UTF-8",result.uri.toString());inspect();
   });}catch(Exception error){main.post(()->{if(closed)return;nativePending=false;if(error instanceof java.io.IOException)failure="تعذر الاتصال بصفحة المصدر عبر المتصفح وعميل الشبكة؛ جرّب سيرفرًا آخر من التطبيق المصدر";alternate();});}
  });
 }
 private void copyCookies(MediaHttp.Session session,Uri target){String cookie=session.headersFor(target).get("Cookie");if(cookie!=null)for(String part:cookie.split(";"))CookieManager.getInstance().setCookie(target.toString(),part.trim()+"; Path=/");}
 private void showPage(){if(closed||web==null||dialog!=null||activity.isFinishing())return;if(web.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)web.getParent()).removeView(web);web.setAlpha(1);dialog=new android.app.AlertDialog.Builder(activity).setTitle("تجهيز الفيديو من المصدر").setMessage("إذا طلب المصدر تفاعلًا، أكمله هنا. يعود مدار تلقائيًا إلى المشغّل عند اكتشاف الفيديو.").setView(web).setNegativeButton("رجوع",(d,n)->fail("أوقفت تجهيز المصدر")).setOnCancelListener(d->fail("أوقفت تجهيز المصدر")).create();dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,(int)(activity.getResources().getDisplayMetrics().heightPixels*.85));}
 private final Runnable poll=new Runnable(){public void run(){if(closed)return;inspect();main.postDelayed(this,600);}};
 private void inspect(){if(closed||web==null)return;web.evaluateJavascript("(()=>{for(const m of document.querySelectorAll('video,audio'))m.muted=true;const links=Array.from(document.querySelectorAll('video,video source')).map(v=>v.currentSrc||v.src);for(const id of ['robotlink','norobotlink','videolink','ideoooolink','ideoolink']){const e=document.getElementById(id);if(e&&e.textContent.trim())try{const u=new URL(e.textContent.trim(),location.href);links.push(u.href);if(u.pathname.endsWith('/get_video')&&!u.searchParams.has('stream')&&!u.searchParams.has('dl')){const play=new URL(u.href);play.searchParams.set('stream','1');links.push(play.href);const direct=new URL(u.href);direct.searchParams.set('dl','1');links.push(direct.href);}}catch(_){}}return JSON.stringify(Array.from(new Set(links.filter(Boolean))));})()",value->{if(closed)return;try{Object decoded=new JSONTokener(value).nextValue();if(!(decoded instanceof String))return;JSONArray urls=new JSONArray((String)decoded);for(int i=0;i<Math.min(16,urls.length());i++){String url=urls.optString(i);if(!url.startsWith("blob:"))candidate(Uri.parse(url),Collections.emptyMap());}}catch(Exception ignored){}});}
 private void candidate(Uri uri,Map<String,String> observed){
  uri=secureProvider(uri);if(closed||!ExternalMedia.network(uri)||probes>=24||!candidates.add(uri.toString()))return;String host=uri.getHost();if(host==null||isAd(uri))return;probes++;
  android.content.Intent safe=new android.content.Intent().putExtra("android.media.intent.extra.HTTP_HEADERS",ExternalMedia.bundle(observed));Map<String,String> headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);headers.putAll(source.http.headersFor(uri));headers.putAll(ExternalMedia.headers(safe));if(web!=null)headers.put("User-Agent",web.getSettings().getUserAgentString());String cookie=CookieManager.getInstance().getCookie(uri.toString());if(cookie!=null&&cookie.length()<=8192)headers.put("Cookie",cookie);headers.putIfAbsent("Referer",page.toString());
  final Uri target=uri;io.execute(()->{MediaHttp.Session session=new MediaHttp.Session(target,headers);sessions.add(session);try{MediaHttp.Probe result=MediaHttp.probe(session,target,session.headersFor(target));if(result.mime!=null&&!result.html)main.post(()->complete(result));}catch(Exception ignored){}});
 }
 private void complete(MediaHttp.Probe media){if(closed)return;close();callback.ready(media);}
 private void fail(String message){if(closed)return;close();callback.failed(message);}
 @Override public void close(){if(closed)return;closed=true;main.removeCallbacksAndMessages(null);if(dialog!=null){dialog.dismiss();dialog=null;}if(web!=null){web.stopLoading();web.loadUrl("about:blank");if(web.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)web.getParent()).removeView(web);web.destroy();web=null;}synchronized(sessions){for(MediaHttp.Session session:sessions)session.client.dispatcher().cancelAll();}io.shutdownNow();}
}
