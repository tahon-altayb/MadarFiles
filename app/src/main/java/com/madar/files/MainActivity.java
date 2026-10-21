package com.madar.files;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private WebView web;
    private PreviewProxy mediaProxy;private LocalMediaServer mediaServer;private File webAssets;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService previews=Executors.newSingleThreadExecutor();
    private volatile int previewToken;private volatile int stepToken;private final ExecutorService navigation=Executors.newSingleThreadExecutor();
    private volatile int timelineToken;
    private volatile int waveformToken;
    private final ExecutorService waveforms=Executors.newSingleThreadExecutor();
    private volatile File source;
    private volatile JSONObject sourceInfo;
    private volatile boolean working;
    private File saving;
    private final List<File> collectionFiles=new ArrayList<>();
    private boolean collectionImages;
    private boolean pickingImages,pickingBatch,collectionBatch;
    private JSONArray collectionNames=new JSONArray();
    private String requestedKind;private volatile boolean reviewTranslating;private final java.util.concurrent.atomic.AtomicBoolean reviewCancel=new java.util.concurrent.atomic.AtomicBoolean();
    private boolean ready;
    private boolean receiverRegistered;
    private final BroadcastReceiver updates=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){String value=i.getStringExtra("event");if(value!=null)try{send(new JSONObject(value));}catch(JSONException ignored){}}};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        web=new WebView(this);web.setBackgroundColor(Color.rgb(248,244,237));
        // A parent container must own the insets: WebView padding does not inset HTML content.
        FrameLayout root=new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(248,244,237));
        root.addView(web,new FrameLayout.LayoutParams(-1,-1));
        root.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(b.left,b.top,b.right,b.bottom);}else{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());}return insets;});
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setDomStorageEnabled(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setBlockNetworkLoads(false);settings.setMediaPlaybackRequiresUserGesture(false);
        mediaProxy=new PreviewProxy(this,this::send);
        try{webAssets=new File(getCacheDir(),"web_ui");if(!webAssets.isDirectory()&&!webAssets.mkdirs())throw new IOException("Web assets unavailable");for(String name:new String[]{"index.html","style.css","app.js","crop.js","studio.js","experience.js","recovery.js"})try(InputStream in=getAssets().open(name);OutputStream out=new FileOutputStream(new File(webAssets,name))){FilesUtil.copy(in,out);}mediaServer=new LocalMediaServer((route,name)->{File target;if(route.matches("index\\.html|style\\.css|app\\.js|crop\\.js|studio\\.js|experience\\.js|recovery\\.js"))return new LocalMediaServer.Media(new File(webAssets,route),route.endsWith(".html")?"text/html":route.endsWith(".css")?"text/css":"application/javascript");if(route.equals("source")){target=source;String mime=sourceInfo==null?"video/mp4":sourceInfo.optString("mime","video/mp4");if(sourceInfo!=null){String guessed=java.net.URLConnection.guessContentTypeFromName(sourceInfo.optString("name"));if(guessed!=null&&(guessed.startsWith("video/")||guessed.startsWith("audio/")))mime=guessed;}return new LocalMediaServer.Media(target,mime);}if(route.equals("proxy")&&name!=null&&name.matches("madar_proxy_[0-9]+\\.mp4"))target=new File(getCacheDir(),name);else if(route.equals("draft")&&name!=null&&name.matches("madar_preview_[0-9]+\\.(mp4|m4a|wav|aac|mp3|flac|ogg)"))target=new File(getCacheDir(),name);else if(route.equals("layer")&&name!=null&&name.matches("madar_layer_[0-9]+\\.png"))target=new File(getCacheDir(),name);else if(route.equals("result"))target=FilesUtil.resolve(this,name);else return null;return new LocalMediaServer.Media(target,FilesUtil.mime(target));});}catch(IOException e){android.util.Log.w("Madar","Local media transport unavailable",e);}
        web.addJavascriptInterface(new Bridge(),"Android");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                if(mediaServer!=null&&mediaServer.owns(r.getUrl()))return null;
                String path=r.getUrl().toString();
                if(path.startsWith("data:image/png;base64,")||path.startsWith("data:image/jpeg;base64,")||path.startsWith("data:image/webp;base64,"))return null;
                if(path.equals("https://madar.local/")||path.startsWith("https://madar.local/")){
                    String route=r.getUrl().getPath();
                    if("/source".equals(route)||"/result".equals(route)||"/draft".equals(route)||"/layer".equals(route)||"/proxy".equals(route)){
                        try{String draftName=r.getUrl().getQueryParameter("name");if("/proxy".equals(route)&&(draftName==null||!draftName.matches("madar_proxy_[0-9]+\\.mp4")))return blocked();if("/layer".equals(route)&&(draftName==null||!draftName.matches("madar_layer_[0-9]+\\.png")))return blocked();if("/draft".equals(route)&&(draftName==null||!draftName.matches("madar_preview_[0-9]+\\.(mp4|m4a|wav|aac|mp3|flac|ogg)")))return blocked();File target="/source".equals(route)?source:("/draft".equals(route)||"/layer".equals(route)||"/proxy".equals(route))?new File(getCacheDir(),draftName):FilesUtil.resolve(MainActivity.this,draftName);return mediaResponse(target,r);}catch(Exception e){return blocked();}
                    }
                    String file=r.getUrl().getPath();if(file==null||file.equals("/"))file="index.html";else file=file.substring(1);
                    if(!file.matches("index\\.html|style\\.css|app\\.js|crop\\.js|studio\\.js|experience\\.js|recovery\\.js"))return blocked();
                    try{return new WebResourceResponse(file.endsWith("css")?"text/css":file.endsWith("js")?"application/javascript":"text/html","UTF-8",getAssets().open(file));}catch(IOException e){return blocked();}
                }return blocked();
            }
        });
        setContentView(root);root.requestApplyInsets();web.loadUrl(mediaServer==null?"https://madar.local/":mediaServer.base()+"/index.html");
        IntentFilter filter=new IntentFilter(VideoExportService.EVENT);
        androidx.core.content.ContextCompat.registerReceiver(this,updates,filter,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered=true;
    }
    private void restoreSource(){
        try{String path=getPreferences(MODE_PRIVATE).getString("sourcePath",null),data=getPreferences(MODE_PRIVATE).getString("sourceInfo",null);
            if(path!=null&&data!=null){File f=new File(path);if(f.isFile()&&f.getCanonicalPath().startsWith(getCacheDir().getCanonicalPath()+File.separator)){source=f;sourceInfo=new JSONObject(data);send(sourceInfo);}}}catch(Exception ignored){}
    }
    private WebResourceResponse blocked(){return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
    final class Bridge {
        @JavascriptInterface public void request(String value){if(value.length()>2000000)return;runOnUiThread(()->{try{handle(new JSONObject(value));}catch(Exception e){error("تعذر تنفيذ الطلب: "+message(e));}});}
    }
    private void handle(JSONObject request)throws Exception {
        String action=request.getString("action");
        switch(action){
            case "ready":ready=true;history();restoreSource();if(VideoExportService.lastEvent!=null)send(new JSONObject(VideoExportService.lastEvent));if(ImageBatchService.active||ImageBatchService.starting)batchStatus();if(Intent.ACTION_SEND.equals(getIntent().getAction())){Uri u=getIntent().getParcelableExtra(Intent.EXTRA_STREAM);getIntent().setAction(Intent.ACTION_MAIN);if(u!=null)importFile(u,null);}break;
            case "prepareMediaPreview":if(source!=null&&!isBusy())mediaProxy.prepare(source,request.getInt("token"));break;
            case "cancelMediaPreview":mediaProxy.stopPreparing();break;
            case "pick":if(isBusy())return;Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);String k=request.optString("kind","image");requestedKind=k;pick.setType(k.equals("audio")?"audio/*":k.equals("video")?"video/*":k.equals("pdf")?"application/pdf":"image/*");startActivityForResult(pick,10);break;
            case "pickCollection":if(isBusy())return;pickingImages=request.optBoolean("images");pickingBatch=request.optBoolean("batch");startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(pickingImages?"image/*":"application/pdf").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true),12);break;
            case "batchStatus":batchStatus();break;
            case "startImageBatch":startImageBatch(request.getJSONArray("order"),request.getJSONObject("options"));break;
            case "resumeImageBatch":if(isBusy())return;startBatchService();break;
            case "pauseImageBatch":startService(new Intent(this,ImageBatchService.class).setAction("pause"));break;
            case "clearImageBatch":if(isBusy())return;worker.execute(()->{try{ImageBatchQueue.clear(this);batchStatus();}catch(Exception e){error("تعذر مسح الطابور");}});break;
            case "exportCollection":exportCollection(request.getJSONArray("order"));break;
            case "export":export(request.getJSONObject("options"));break;
            case "cutoutPreview":int cutToken=request.optInt("token",0);previewToken=cutToken;File cutSource=source;String cutColor=request.optString("color","#eadcc7");boolean transparent=request.optBoolean("transparent",true);String cutModel=request.optString("cutModel","fine");double cutThreshold=request.optDouble("cutThreshold",50),cutSoftness=request.optDouble("cutSoftness",50);if(cutSource!=null)previews.execute(()->{if(cutToken!=previewToken)return;Bitmap b=null,out=null;try{b=FilesUtil.image(cutSource,640);out=PersonCutout.cut(MainActivity.this,b,transparent,Color.parseColor(cutColor),cutModel,cutThreshold,cutSoftness);ByteArrayOutputStream bytes=new ByteArrayOutputStream();out.compress(Bitmap.CompressFormat.PNG,100,bytes);if(cutToken==previewToken)send(new JSONObject().put("type","cutoutPreview").put("token",cutToken).put("thumbnail","data:image/png;base64,"+android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP)));}catch(Exception e){if(cutToken==previewToken)event("previewError","message","تعذر إنشاء معاينة العزل");}finally{if(b!=null)b.recycle();if(out!=null)out.recycle();}});break;
            case "cancelWaveform":waveformToken++;break;
            case "waveform":int waveToken=request.optInt("token",0);waveformToken=waveToken;File waveSource=source;if(waveSource!=null)waveforms.execute(()->{try{JSONObject result=AudioWaveform.read(waveSource,()->waveformToken!=waveToken||source!=waveSource);if(waveformToken==waveToken&&source==waveSource)send(result.put("type","waveform").put("token",waveToken));}catch(java.util.concurrent.CancellationException ignored){}catch(Exception e){if(waveformToken==waveToken&&source==waveSource)try{send(new JSONObject().put("type","waveformError").put("token",waveToken).put("message","تعذر إنشاء الموجة الصوتية؛ يمكنك متابعة التحرير"));}catch(Exception ignored){}}});break;
            case "timelineFrames":int frameToken=request.optInt("token",0);timelineToken=frameToken;File frameSource=source;if(frameSource!=null)previews.execute(()->{try{JSONArray frames=TimelineFrames.read(frameSource,()->timelineToken!=frameToken||source!=frameSource||Thread.currentThread().isInterrupted());if(timelineToken==frameToken&&source==frameSource)send(new JSONObject().put("type","timelineFrames").put("token",frameToken).put("frames",frames));}catch(Exception e){if(timelineToken==frameToken&&source==frameSource)event("timelineFramesError","message","تعذر إنشاء الصور المصغرة؛ يمكنك متابعة التحرير");}});break;
            case "stepFrame":int requestedStep=request.optInt("token");stepToken=requestedStep;double at=request.getDouble("time");int direction=request.getInt("direction");File stepSource=source;if(stepSource!=null)navigation.execute(()->{if(stepToken!=requestedStep)return;try{double time=FrameStepper.step(stepSource,at,direction);if(stepToken==requestedStep&&source==stepSource)send(new JSONObject().put("type","frameStep").put("token",requestedStep).put("time",time));}catch(Exception e){if(stepToken==requestedStep)event("frameStepError","message","تعذر الانتقال إلى الإطار المجاور");}});break;
            case "pickLayer":if(isBusy())return;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),13);break;
            case "preview":previewPage(request.optInt("page",1),request.optInt("token",0),request.optString("compression",""));break;
            case "copyText":String text=request.getString("text");if(text.length()>1000000)throw new IOException("النص طويل جدًا");((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("مدار",text));Toast.makeText(this,"تم نسخ النص",Toast.LENGTH_SHORT).show();break;
            case "translateReviewed":if(isBusy())return;JSONArray reviewed=request.getJSONArray("cues");if(reviewed.length()<1||reviewed.length()>1000)throw new IOException("اختر من سطر إلى 1000 سطر");String from=request.getString("language"),to=request.getString("target");working=true;reviewTranslating=true;reviewCancel.set(false);event("busy","message","ترجمة النص المراجع محليًا");worker.execute(()->{try{JSONArray translated=LocalTranslation.translate(reviewed,from,to,reviewCancel,(p,m)->event("workProgress","message",m));send(new JSONObject().put("type","subtitles").put("cues",translated).put("originalCues",reviewed).put("language",from).put("target",to));}catch(java.util.concurrent.CancellationException e){event("cancelled",null,null);}catch(Exception e){error("تعذرت ترجمة النص. "+message(e));}finally{working=false;reviewTranslating=false;}});break;
            case "saveSubtitles":JSONArray cues=request.getJSONArray("cues");if(cues.length()>10000)throw new IOException("عدد أسطر الترجمة كبير جدًا");worker.execute(()->{try{File f=SpeechProcessor.srt(this,cues);send(FilesUtil.info(f).put("type","result"));history();}catch(Exception e){error("تعذر حفظ الترجمة");}});break;
            case "saveText":String content=request.getString("text"),format=request.optString("format","txt");worker.execute(()->{try{File f=TextProcessor.save(this,content,format);send(FilesUtil.info(f).put("type","result"));history();}catch(Exception e){error("تعذر حفظ النص. "+message(e));}});break;
            case "projectSave":String key=request.getString("key");JSONArray segments=request.getJSONArray("segments");if(key.length()>500||segments.length()>50)throw new IOException("المشروع كبير جدًا");JSONArray layers=request.optJSONArray("layers");if(layers!=null&&layers.length()>20)throw new IOException("الحد 20 عنصرًا");getSharedPreferences("projects",0).edit().putString(key,new JSONObject().put("segments",segments).put("layers",layers==null?new JSONArray():layers).toString()).apply();Toast.makeText(this,"تم حفظ المشروع لهذا الملف",Toast.LENGTH_SHORT).show();break;
            case "projectLoad":String data=getSharedPreferences("projects",0).getString(request.getString("key"),null);if(data==null)throw new IOException("لا يوجد مشروع محفوظ لهذا الملف");JSONObject project=data.startsWith("[")?new JSONObject().put("segments",new JSONArray(data)).put("layers",new JSONArray()):new JSONObject(data);send(project.put("type","project"));break;
            case "history":history();break;
            case "save":saving=FilesUtil.resolve(this,request.getString("name"));Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(FilesUtil.mime(saving)).putExtra(Intent.EXTRA_TITLE,saving.getName());startActivityForResult(save,11);break;
            case "share":case "view":openOutput(FilesUtil.resolve(this,request.getString("name")),action.equals("share"));break;
            case "play":File playable=request.has("name")?FilesUtil.resolve(this,request.getString("name")):source;if(playable!=null)startActivity(new Intent(this,PlayerActivity.class).putExtra("path",playable.getAbsolutePath()).putExtra("title",playable==source?sourceInfo.optString("name"):playable.getName()));break;
            case "previewSource":if(source!=null){VideoView video=new VideoView(this);MediaController controls=new MediaController(this);video.setMediaController(controls);controls.setAnchorView(video);video.setVideoURI(Uri.fromFile(source));AlertDialog dialog=new AlertDialog.Builder(this).setTitle("معاينة الفيديو").setView(video).setPositiveButton("إغلاق",null).create();dialog.setOnDismissListener(d->video.stopPlayback());dialog.show();video.setOnPreparedListener(mp->{mp.setLooping(false);video.start();});video.setOnErrorListener((mp,what,extra)->{error("تعذر تشغيل هذا الفيديو في المعاينة");dialog.dismiss();return true;});}break;
            case "cancel":if(reviewTranslating){reviewCancel.set(true);break;}startService(new Intent(this,VideoExportService.class).setAction("cancel"));break;
            default:throw new IllegalArgumentException("طلب غير معروف");
        }
    }
    private boolean isBusy(){if(working||VideoExportService.active||ImageBatchService.active||ImageBatchService.starting){Toast.makeText(this,"انتظر انتهاء العملية الحالية",Toast.LENGTH_SHORT).show();return true;}return false;}
    private void importFile(Uri uri,String expected){
        if(isBusy())return;if(!"content".equals(uri.getScheme())){error("اختر الملف من مدير الملفات");return;}
        working=true;event("busy","message","جارٍ قراءة الملف");
        worker.execute(()->{
            File imported=null;
            try{
                String name="ملف",mime=getContentResolver().getType(uri);long declared=-1;
                try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){if(cursor!=null&&cursor.moveToFirst()){name=cursor.getString(0);if(!cursor.isNull(1))declared=cursor.getLong(1);}}
                if(mime==null||mime.isEmpty()){mime=java.net.URLConnection.guessContentTypeFromName(name);if(mime==null)mime="";}
                String kind=mime.startsWith("image/")?"image":mime.startsWith("audio/")?"audio":mime.startsWith("video/")?"video":mime.equals("application/pdf")?"pdf":expected;
                if(kind==null&&name!=null&&name.toLowerCase(Locale.ROOT).endsWith(".pdf"))kind="pdf";
                if(kind==null)throw new IOException("نوع الملف غير مدعوم. اختر صورة أو فيديو أو PDF");if(mime.isEmpty()||mime.equals("application/octet-stream"))mime=kind.equals("video")?"video/mp4":kind.equals("audio")?"audio/mp4":kind.equals("image")?"image/jpeg":"application/pdf";
                long available=getFilesDir().getUsableSpace();if(declared>available-64L*1024*1024)throw new IOException("المساحة المتاحة لا تكفي لاستيراد الملف");
                imported=File.createTempFile("madar_input_",".media",getCacheDir());
                try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(imported)){
                    if(in==null)throw new IOException("تعذر قراءة الملف");byte[] buffer=new byte[65536];long count=0;int n;
                    while((n=in.read(buffer))!=-1){count+=n;if(count>available-64L*1024*1024)throw new IOException("المساحة غير كافية");out.write(buffer,0,n);}
                }
                JSONObject info=new JSONObject().put("type","source").put("kind",kind).put("name",name==null?"ملف":name).put("size",imported.length()).put("mime",mime);
                if(kind.equals("image")){
                    final int[] dimensions=new int[2];Bitmap b=ImageDecoder.decodeBitmap(ImageDecoder.createSource(imported),(d,i,s)->{dimensions[0]=i.getSize().getWidth();dimensions[1]=i.getSize().getHeight();float scale=Math.min(1f,640f/Math.max(dimensions[0],dimensions[1]));d.setTargetSize(Math.max(1,Math.round(dimensions[0]*scale)),Math.max(1,Math.round(dimensions[1]*scale)));d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);});
                    try{info.put("width",dimensions[0]).put("height",dimensions[1]).put("thumbnail",FilesUtil.thumbnail(b));}finally{b.recycle();}
                }else if(kind.equals("audio")){try(MediaMetadataRetriever mmr=new MediaMetadataRetriever()){mmr.setDataSource(imported.getAbsolutePath());info.put("duration",Long.parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))).put("hasAudio",true).put("height",0);}}else if(kind.equals("video")){
                    try(MediaMetadataRetriever mmr=new MediaMetadataRetriever()){mmr.setDataSource(imported.getAbsolutePath());String hasVideo=mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);if(!"yes".equals(hasVideo))throw new IOException("لا يوجد مسار فيديو قابل للقراءة");int w=Integer.parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)),h=Integer.parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));String rot=mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);if("90".equals(rot)||"270".equals(rot)){int t=w;w=h;h=t;}String fps=mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);if(fps!=null)try{info.put("fps",Double.parseDouble(fps));}catch(NumberFormatException ignored){}info.put("width",w).put("height",h).put("duration",Long.parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))).put("hasAudio","yes".equals(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)));Bitmap b=mmr.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,480,480);if(b!=null)try{info.put("thumbnail",FilesUtil.thumbnail(b));}finally{b.recycle();}}
                }else{
                    try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(imported,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer pdf=new PdfRenderer(fd)){if(pdf.getPageCount()==0)throw new IOException("ملف PDF فارغ");info.put("pages",pdf.getPageCount());Bitmap b=PdfProcessor.render(pdf,0,400);try{info.put("thumbnail",FilesUtil.thumbnail(b));}finally{b.recycle();}}
                }
                mediaProxy.cancel();File old=source;source=imported;sourceInfo=info;getPreferences(MODE_PRIVATE).edit().putString("sourcePath",source.getAbsolutePath()).putString("sourceInfo",info.toString()).apply();imported=null;if(old!=null)old.delete();working=false;send(info);
            }catch(Exception|OutOfMemoryError e){working=false;error("تعذر فتح الملف. "+message(e));}finally{if(imported!=null)imported.delete();}
        });
    }
    private void export(JSONObject o)throws Exception{
        if(isBusy())return;mediaProxy.stopPreparing();File input=source;JSONObject info=sourceInfo;if(input==null||info==null)throw new IOException("اختر الملف أولًا");
        String kind=info.getString("kind");if(!kind.equals(o.getString("kind")))throw new IOException("نوع الملف لا يطابق الأداة");
        if(kind.equals("video")||kind.equals("audio")){
            if((o.optString("tool").equals("audio")||o.optString("tool").equals("music"))&&!info.optBoolean("hasAudio"))throw new IOException("الفيديو لا يحتوي على صوت");
            if(o.optString("tool").equals("music")&&!o.optBoolean("sample",false)&&(info.optLong("duration",0)<=0||info.optLong("duration",0)>60000))throw new IOException("الفصل المحلي متاح حتى دقيقة واحدة. للمقاطع الأطول استخدم Colab يدويًا؛ الربط السحابي غير متاح بعد.");
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},20);
            Intent service=new Intent(this,VideoExportService.class).putExtra("input",input.getAbsolutePath()).putExtra("options",o.toString()).putExtra("duration",info.getLong("duration")).putExtra("height",info.optInt("height",0));startForegroundService(service);
        }else{
            working=true;worker.execute(()->{try{
                String tool=o.optString("tool");
                if(tool.equals("layoutWord")){File f=LayoutWordExporter.export(this,input,o.optInt("first",1),o.optInt("last",1),o.optString("ocrLanguage","ara"),o.optString("pdfTextMode").equals("ocr"));working=false;send(FilesUtil.info(f).put("type","result"));}
                else if(tool.equals("ocr")||tool.equals("text")||tool.equals("word")){
                    String text=kind.equals("image")?TextProcessor.image(this,input,o.optString("ocrLanguage","ara")):TextProcessor.pdf(this,input,o.optInt("first",1),o.optInt("last",1),o.optString("ocrLanguage","ara"),o.optString("pdfTextMode").equals("ocr"),p->event("workProgress","message",p));
                    working=false;if(text.trim().isEmpty())throw new IOException("لم يُعثر على نص واضح في الملف");send(new JSONObject().put("type","text").put("text",text));
                }else{File f=kind.equals("image")?ImageProcessor.export(this,input,o):PdfProcessor.export(this,input,o);working=false;send(FilesUtil.info(f).put("type","result"));}
            }catch(Exception|OutOfMemoryError e){working=false;error("لم يكتمل التصدير. "+message(e));}});
        }
    }
    private void previewPage(int page,int token,String compression){
        previewToken=token;File file=source;if(file==null)return;previews.execute(()->{if(token!=previewToken)return;File extracted=null,compressed=null;try{
            File render=file;int index=page-1;
            if(!compression.isEmpty()){
                Context previewContext=new ContextWrapper(this){@Override public File getFilesDir(){File d=new File(getCacheDir(),"pdf-preview-work");d.mkdirs();return d;}};
                extracted=PdfProcessor.export(previewContext,file,new JSONObject().put("tool","extract").put("first",page).put("last",page));compressed=PdfOptimizer.compress(previewContext,extracted,compression);render=compressed;index=0;
            }
            if(token!=previewToken)return;
            try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(render,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd)){if(index<0||index>=renderer.getPageCount())throw new IOException("صفحة غير موجودة");Bitmap bitmap=PdfProcessor.render(renderer,index,900);try{if(token==previewToken)send(new JSONObject().put("type","preview").put("page",page).put("token",token).put("thumbnail",FilesUtil.thumbnail(bitmap)));}finally{bitmap.recycle();}}
        }catch(Exception|OutOfMemoryError e){try{send(new JSONObject().put("type","previewError").put("token",token).put("message","تعذر عرض هذه الصفحة"));}catch(Exception ignored){}}finally{if(extracted!=null)extracted.delete();if(compressed!=null)compressed.delete();}});
    }
    private WebResourceResponse mediaResponse(File file,WebResourceRequest request)throws Exception{
        if(file==null||!file.isFile())return blocked();long length=file.length(),start=0,end=length-1;String range=null;for(Map.Entry<String,String> header:request.getRequestHeaders().entrySet())if(header.getKey().equalsIgnoreCase("Range"))range=header.getValue();boolean partial=false;
        if(range!=null&&range.matches("bytes=[0-9]+-[0-9]*")){String[] values=range.substring(6).split("-",-1);start=Long.parseLong(values[0]);if(!values[1].isEmpty())end=Math.min(end,Long.parseLong(values[1]));partial=true;}
        if(start<0||start>=length||end<start)return blocked();FileInputStream stream=new FileInputStream(file);stream.getChannel().position(start);final long count=end-start+1;
        InputStream limited=new FilterInputStream(stream){long left=count;@Override public int read()throws IOException{if(left<=0)return -1;int v=super.read();if(v>=0)left--;return v;}@Override public int read(byte[] b,int off,int len)throws IOException{if(left<=0)return -1;int n=super.read(b,off,(int)Math.min(left,len));if(n>0)left-=n;return n;}};
        Map<String,String> headers=new HashMap<>();headers.put("Accept-Ranges","bytes");headers.put("Content-Length",Long.toString(count));headers.put("Cache-Control","no-store");if(partial)headers.put("Content-Range","bytes "+start+"-"+end+"/"+length);
        String mime=file==source&&sourceInfo!=null?sourceInfo.optString("mime","video/mp4"):FilesUtil.mime(file);if(file==source&&sourceInfo!=null){String guessed=java.net.URLConnection.guessContentTypeFromName(sourceInfo.optString("name"));if(guessed!=null&&(guessed.startsWith("video/")||guessed.startsWith("audio/")))mime=guessed;if(mime.contains("*")||mime.equals("application/octet-stream"))mime=sourceInfo.optString("kind").equals("audio")?"audio/mp4":"video/mp4";}return new WebResourceResponse(mime,null,partial?206:200,partial?"Partial Content":"OK",headers,limited);
    }
    private String message(Throwable e){if(e instanceof OutOfMemoryError)return "الملف يتجاوز الذاكرة المتاحة؛ جرّب دقة أقل.";if(e instanceof SecurityException)return "الملف محمي أو تعذر الحصول على إذن قراءته.";String m=e.getMessage();return m==null?"تحقق من الملف والمساحة المتاحة.":m;}
    private void openOutput(File f,boolean share){
        Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",f);Intent i=new Intent(share?Intent.ACTION_SEND:Intent.ACTION_VIEW).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);i.setClipData(ClipData.newRawUri("Madar",uri));if(share){i.setType(FilesUtil.mime(f));i.putExtra(Intent.EXTRA_STREAM,uri);}else i.setDataAndType(uri,FilesUtil.mime(f));try{startActivity(Intent.createChooser(i,share?"مشاركة الملف":"فتح الملف"));}catch(ActivityNotFoundException e){error("لا يوجد تطبيق مناسب لفتح الملف");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;if(request==12){importCollection(data);return;}if(request==13&&data.getData()!=null){importLayer(data.getData());return;}if(data.getData()==null)return;if(request==10)importFile(data.getData(),requestedKind);else if(request==11&&saving!=null){File f=saving;Uri dest=data.getData();saving=null;worker.execute(()->{try(InputStream in=new FileInputStream(f);OutputStream out=getContentResolver().openOutputStream(dest,"w")){if(out==null)throw new IOException("تعذر إنشاء الملف");FilesUtil.copy(in,out);event("saved",null,null);}catch(Exception e){error("تعذر الحفظ. "+message(e));}});}}
    private void importLayer(Uri uri){previews.execute(()->{File temp=null;Bitmap b=null;try{temp=File.createTempFile("madar_layer_input_",".image",getCacheDir());try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temp)){if(in==null)throw new IOException("تعذر قراءة الصورة");byte[] buffer=new byte[65536];long total=0;int n;while((n=in.read(buffer))!=-1){total+=n;if(total>32L*1024*1024)throw new IOException("اختر صورة أقل من 32 ميجابايت");out.write(buffer,0,n);}}b=FilesUtil.image(temp,1600);File result=File.createTempFile("madar_layer_",".png",getCacheDir());try(OutputStream out=new FileOutputStream(result)){b.compress(Bitmap.CompressFormat.PNG,100,out);}float factor=Math.min(1,480f/Math.max(b.getWidth(),b.getHeight()));Bitmap thumbnail=Bitmap.createScaledBitmap(b,Math.max(1,Math.round(b.getWidth()*factor)),Math.max(1,Math.round(b.getHeight()*factor)),true);try{send(new JSONObject().put("type","layerImage").put("name",result.getName()).put("thumbnail",FilesUtil.thumbnail(thumbnail)));}finally{if(thumbnail!=b)thumbnail.recycle();}}catch(Exception e){error("تعذر إضافة الصورة. "+message(e));}finally{if(temp!=null)temp.delete();if(b!=null)b.recycle();}});}
    private void importCollection(Intent data){
        if(isBusy())return;List<Uri> uris=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)uris.add(data.getData());
        if(uris.isEmpty()||uris.size()>20){error("اختر من ملف واحد إلى 20 ملفًا");return;}boolean images=pickingImages,batch=pickingBatch;working=true;event("busy","message","جارٍ تجهيز الملفات");
        worker.execute(()->{List<File> imported=new ArrayList<>();try{
            JSONArray names=new JSONArray();long budget=Math.min(512L*1024*1024,getCacheDir().getUsableSpace()-64L*1024*1024),total=0;
            for(Uri uri:uris){if(!"content".equals(uri.getScheme()))throw new IOException("اختر الملفات من مدير الملفات");String name="ملف "+(imported.size()+1);try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);}
                File f=File.createTempFile("madar_collection_",images?".image":".pdf",getCacheDir());imported.add(f);try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(f)){if(in==null)throw new IOException("تعذر قراءة الملف");byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){total+=n;if(total>budget)throw new IOException("حجم الملفات يتجاوز المساحة المتاحة أو حد 512 ميجابايت");out.write(buffer,0,n);}}
                names.put(new JSONObject().put("id",imported.size()-1).put("name",name));
            }
            for(File old:collectionFiles)old.delete();collectionFiles.clear();collectionFiles.addAll(imported);imported.clear();collectionImages=images;collectionBatch=batch;collectionNames=names;working=false;send(new JSONObject().put("type","collection").put("files",names).put("images",images).put("batch",batch));
        }catch(Exception|OutOfMemoryError e){working=false;error("تعذر تجهيز الملفات. "+message(e));}finally{for(File f:imported)f.delete();}});
    }
    private void exportCollection(JSONArray order)throws Exception{
        if(isBusy())return;if(order.length()<1||order.length()>20)throw new IOException("حدد الملفات أولًا");List<File> selected=new ArrayList<>();Set<Integer> used=new HashSet<>();for(int i=0;i<order.length();i++){int index=order.getInt(i);if(index<0||index>=collectionFiles.size()||!used.add(index))throw new IOException("ترتيب الملفات غير صالح");selected.add(collectionFiles.get(index));}boolean images=collectionImages;working=true;event("busy","message","جارٍ إنشاء PDF");
        worker.execute(()->{try{File output=DocumentCollection.export(this,selected,images);working=false;send(FilesUtil.info(output).put("type","result"));history();}catch(Exception|OutOfMemoryError e){working=false;error("تعذر إنشاء PDF. "+message(e));}});
    }
    private void startImageBatch(JSONArray order,JSONObject options)throws Exception{
        if(isBusy())return;if(!collectionImages||!collectionBatch||order.length()<1||order.length()>20)throw new IOException("اختر صور الدفعة أولًا");
        List<File> selected=new ArrayList<>();JSONArray names=new JSONArray();Set<Integer> used=new HashSet<>();for(int i=0;i<order.length();i++){int index=order.getInt(i);if(index<0||index>=collectionFiles.size()||!used.add(index))throw new IOException("ترتيب الصور غير صالح");selected.add(collectionFiles.get(index));names.put(collectionNames.getJSONObject(index).getString("name"));}JSONObject safe=ImageBatchQueue.validate(options);working=true;event("busy","message","حفظ صور الطابور للاستئناف");
        worker.execute(()->{try{ImageBatchQueue.create(this,selected,names,safe);runOnUiThread(()->{working=false;try{startBatchService();}catch(Exception e){error("تعذر بدء الطابور. "+message(e));}});}catch(Exception|OutOfMemoryError e){working=false;error("تعذر تجهيز الطابور. "+message(e));}});
    }
    private void startBatchService(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},20);ImageBatchService.starting=true;try{startForegroundService(new Intent(this,ImageBatchService.class));}catch(RuntimeException e){ImageBatchService.starting=false;throw e;}}
    private void batchStatus(){worker.execute(()->{try{JSONObject state=ImageBatchQueue.read(this);if(state==null)state=new JSONObject().put("status","empty").put("jobs",new JSONArray());send(ImageBatchQueue.view(state,ImageBatchService.active||ImageBatchService.starting));}catch(Exception e){error("تعذر قراءة طابور الصور");}});}
    private void history(){worker.execute(()->{try{File[] files=FilesUtil.directory(this).listFiles(f->f.isFile()&&!f.getName().endsWith(".partial"));JSONArray a=new JSONArray();if(files!=null){Arrays.sort(files,(x,y)->Long.compare(y.lastModified(),x.lastModified()));for(int i=0;i<Math.min(50,files.length);i++)a.put(FilesUtil.info(files[i]));}send(new JSONObject().put("type","history").put("files",a));}catch(Exception e){error("تعذر قراءة النتائج");}});}
    private void event(String type,String key,Object value){try{JSONObject o=new JSONObject().put("type",type);if(key!=null)o.put(key,value);send(o);}catch(JSONException ignored){}}
    private void error(String message){event("error","message",message);}
    private void send(JSONObject event){runOnUiThread(()->{try{if(mediaServer!=null)event.put("mediaBase",mediaServer.base());}catch(JSONException ignored){}if(ready&&web!=null)web.evaluateJavascript("window.receive("+event.toString()+");",null);});}
    @Override protected void onResume(){super.onResume();if(ready&&(ImageBatchService.active||ImageBatchService.starting))batchStatus();if(ready&&VideoExportService.active&&VideoExportService.lastEvent!=null)try{send(new JSONObject(VideoExportService.lastEvent));}catch(JSONException ignored){}}
    @Override protected void onDestroy(){if(mediaServer!=null)mediaServer.close();if(mediaProxy!=null)mediaProxy.cancel();reviewCancel.set(true);ready=false;if(receiverRegistered)unregisterReceiver(updates);worker.execute(()->{for(File f:collectionFiles)f.delete();collectionFiles.clear();});worker.shutdown();previews.shutdownNow();navigation.shutdownNow();waveformToken++;waveforms.shutdownNow();if(web!=null){web.removeJavascriptInterface("Android");web.destroy();web=null;}super.onDestroy();}
}
