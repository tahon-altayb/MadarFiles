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
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService previews=Executors.newSingleThreadExecutor();
    private volatile int previewToken;
    private volatile File source;
    private volatile JSONObject sourceInfo;
    private volatile boolean working;
    private File saving;
    private String requestedKind;
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
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setDomStorageEnabled(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setBlockNetworkLoads(true);
        web.addJavascriptInterface(new Bridge(),"Android");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                String path=r.getUrl().toString();
                if(path.equals("https://madar.local/")||path.startsWith("https://madar.local/")){
                    String route=r.getUrl().getPath();
                    if("/source".equals(route)||"/result".equals(route)){
                        try{File target="/source".equals(route)?source:FilesUtil.resolve(MainActivity.this,r.getUrl().getQueryParameter("name"));return mediaResponse(target,r);}catch(Exception e){return blocked();}
                    }
                    String file=r.getUrl().getPath();if(file==null||file.equals("/"))file="index.html";else file=file.substring(1);
                    if(!file.matches("index\\.html|style\\.css|app\\.js|crop\\.js|studio\\.js"))return blocked();
                    try{return new WebResourceResponse(file.endsWith("css")?"text/css":file.endsWith("js")?"application/javascript":"text/html","UTF-8",getAssets().open(file));}catch(IOException e){return blocked();}
                }return blocked();
            }
        });
        setContentView(root);root.requestApplyInsets();web.loadUrl("https://madar.local/");
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
            case "ready":ready=true;history();restoreSource();if(VideoExportService.lastEvent!=null)send(new JSONObject(VideoExportService.lastEvent));if(Intent.ACTION_SEND.equals(getIntent().getAction())){Uri u=getIntent().getParcelableExtra(Intent.EXTRA_STREAM);getIntent().setAction(Intent.ACTION_MAIN);if(u!=null)importFile(u,null);}break;
            case "pick":if(isBusy())return;Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);String k=request.optString("kind","image");requestedKind=k;pick.setType(k.equals("audio")?"audio/*":k.equals("video")?"video/*":k.equals("pdf")?"application/pdf":"image/*");startActivityForResult(pick,10);break;
            case "export":export(request.getJSONObject("options"));break;
            case "cutoutPreview":int cutToken=request.optInt("token",0);previewToken=cutToken;File cutSource=source;String cutColor=request.optString("color","#eadcc7");boolean transparent=request.optBoolean("transparent",true);if(cutSource!=null)previews.execute(()->{if(cutToken!=previewToken)return;Bitmap b=null,out=null;try{b=FilesUtil.image(cutSource,640);out=PersonCutout.cut(b,transparent,Color.parseColor(cutColor));ByteArrayOutputStream bytes=new ByteArrayOutputStream();out.compress(Bitmap.CompressFormat.PNG,100,bytes);if(cutToken==previewToken)send(new JSONObject().put("type","cutoutPreview").put("token",cutToken).put("thumbnail","data:image/png;base64,"+android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP)));}catch(Exception e){if(cutToken==previewToken)event("previewError","message","تعذر إنشاء معاينة العزل");}finally{if(b!=null)b.recycle();if(out!=null)out.recycle();}});break;
            case "preview":previewPage(request.optInt("page",1),request.optInt("token",0));break;
            case "copyText":String text=request.getString("text");if(text.length()>1000000)throw new IOException("النص طويل جدًا");((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("مدار",text));Toast.makeText(this,"تم نسخ النص",Toast.LENGTH_SHORT).show();break;
            case "saveSubtitles":JSONArray cues=request.getJSONArray("cues");if(cues.length()>10000)throw new IOException("عدد أسطر الترجمة كبير جدًا");worker.execute(()->{try{File f=SpeechProcessor.srt(this,cues);send(FilesUtil.info(f).put("type","result"));history();}catch(Exception e){error("تعذر حفظ الترجمة");}});break;
            case "saveText":String content=request.getString("text"),format=request.optString("format","txt");worker.execute(()->{try{File f=TextProcessor.save(this,content,format);send(FilesUtil.info(f).put("type","result"));history();}catch(Exception e){error("تعذر حفظ النص. "+message(e));}});break;
            case "projectSave":String key=request.getString("key");JSONArray segments=request.getJSONArray("segments");if(key.length()>500||segments.length()>50)throw new IOException("المشروع كبير جدًا");getSharedPreferences("projects",0).edit().putString(key,segments.toString()).apply();Toast.makeText(this,"تم حفظ المشروع لهذا الملف",Toast.LENGTH_SHORT).show();break;
            case "projectLoad":String data=getSharedPreferences("projects",0).getString(request.getString("key"),null);if(data==null)throw new IOException("لا يوجد مشروع محفوظ لهذا الملف");send(new JSONObject().put("type","project").put("segments",new JSONArray(data)));break;
            case "history":history();break;
            case "save":saving=FilesUtil.resolve(this,request.getString("name"));Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(FilesUtil.mime(saving)).putExtra(Intent.EXTRA_TITLE,saving.getName());startActivityForResult(save,11);break;
            case "share":case "view":openOutput(FilesUtil.resolve(this,request.getString("name")),action.equals("share"));break;
            case "play":File playable=request.has("name")?FilesUtil.resolve(this,request.getString("name")):source;if(playable!=null)startActivity(new Intent(this,PlayerActivity.class).putExtra("path",playable.getAbsolutePath()).putExtra("title",playable==source?sourceInfo.optString("name"):playable.getName()));break;
            case "previewSource":if(source!=null){VideoView video=new VideoView(this);MediaController controls=new MediaController(this);video.setMediaController(controls);controls.setAnchorView(video);video.setVideoURI(Uri.fromFile(source));AlertDialog dialog=new AlertDialog.Builder(this).setTitle("معاينة الفيديو").setView(video).setPositiveButton("إغلاق",null).create();dialog.setOnDismissListener(d->video.stopPlayback());dialog.show();video.setOnPreparedListener(mp->{mp.setLooping(false);video.start();});video.setOnErrorListener((mp,what,extra)->{error("تعذر تشغيل هذا الفيديو في المعاينة");dialog.dismiss();return true;});}break;
            case "cancel":startService(new Intent(this,VideoExportService.class).setAction("cancel"));break;
            default:throw new IllegalArgumentException("طلب غير معروف");
        }
    }
    private boolean isBusy(){if(working||VideoExportService.active){Toast.makeText(this,"انتظر انتهاء العملية الحالية",Toast.LENGTH_SHORT).show();return true;}return false;}
    private void importFile(Uri uri,String expected){
        if(isBusy())return;if(!"content".equals(uri.getScheme())){error("اختر الملف من مدير الملفات");return;}
        working=true;event("busy","message","جارٍ قراءة الملف");
        worker.execute(()->{
            File imported=null;
            try{
                String name="ملف",mime=getContentResolver().getType(uri);long declared=-1;
                try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){if(cursor!=null&&cursor.moveToFirst()){name=cursor.getString(0);if(!cursor.isNull(1))declared=cursor.getLong(1);}}
                if(mime==null)mime="";
                String kind=mime.startsWith("image/")?"image":mime.startsWith("audio/")?"audio":mime.startsWith("video/")?"video":mime.equals("application/pdf")?"pdf":expected;
                if(kind==null&&name!=null&&name.toLowerCase(Locale.ROOT).endsWith(".pdf"))kind="pdf";
                if(kind==null)throw new IOException("نوع الملف غير مدعوم. اختر صورة أو فيديو أو PDF");
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
                    try(MediaMetadataRetriever mmr=new MediaMetadataRetriever()){mmr.setDataSource(imported.getAbsolutePath());String hasVideo=mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);if(!"yes".equals(hasVideo))throw new IOException("لا يوجد مسار فيديو قابل للقراءة");int w=Integer.parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)),h=Integer.parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));String rot=mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);if("90".equals(rot)||"270".equals(rot)){int t=w;w=h;h=t;}info.put("width",w).put("height",h).put("duration",Long.parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))).put("hasAudio","yes".equals(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)));Bitmap b=mmr.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,480,480);if(b!=null)try{info.put("thumbnail",FilesUtil.thumbnail(b));}finally{b.recycle();}}
                }else{
                    try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(imported,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer pdf=new PdfRenderer(fd)){if(pdf.getPageCount()==0)throw new IOException("ملف PDF فارغ");info.put("pages",pdf.getPageCount());Bitmap b=PdfProcessor.render(pdf,0,400);try{info.put("thumbnail",FilesUtil.thumbnail(b));}finally{b.recycle();}}
                }
                File old=source;source=imported;sourceInfo=info;getPreferences(MODE_PRIVATE).edit().putString("sourcePath",source.getAbsolutePath()).putString("sourceInfo",info.toString()).apply();imported=null;if(old!=null)old.delete();working=false;send(info);
            }catch(Exception|OutOfMemoryError e){working=false;error("تعذر فتح الملف. "+message(e));}finally{if(imported!=null)imported.delete();}
        });
    }
    private void export(JSONObject o)throws Exception{
        if(isBusy())return;File input=source;JSONObject info=sourceInfo;if(input==null||info==null)throw new IOException("اختر الملف أولًا");
        String kind=info.getString("kind");if(!kind.equals(o.getString("kind")))throw new IOException("نوع الملف لا يطابق الأداة");
        if(kind.equals("video")||kind.equals("audio")){
            if((o.optString("tool").equals("audio")||o.optString("tool").equals("music"))&&!info.optBoolean("hasAudio"))throw new IOException("الفيديو لا يحتوي على صوت");
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},20);
            Intent service=new Intent(this,VideoExportService.class).putExtra("input",input.getAbsolutePath()).putExtra("options",o.toString()).putExtra("duration",info.getLong("duration")).putExtra("height",info.optInt("height",0));startForegroundService(service);
        }else{
            working=true;worker.execute(()->{try{
                String tool=o.optString("tool");
                if(tool.equals("layoutWord")){File f=LayoutWordExporter.export(this,input,o.optInt("first",1),o.optInt("last",1));working=false;send(FilesUtil.info(f).put("type","result"));}
                else if(tool.equals("ocr")||tool.equals("text")||tool.equals("word")){
                    String text=kind.equals("image")?TextProcessor.image(this,input):TextProcessor.pdf(this,input,o.optInt("first",1),o.optInt("last",1),p->event("workProgress","message",p));
                    working=false;if(text.trim().isEmpty())throw new IOException("لم يُعثر على نص واضح في الملف");send(new JSONObject().put("type","text").put("text",text));
                }else{File f=kind.equals("image")?ImageProcessor.export(this,input,o):PdfProcessor.export(this,input,o);working=false;send(FilesUtil.info(f).put("type","result"));}
            }catch(Exception|OutOfMemoryError e){working=false;error("لم يكتمل التصدير. "+message(e));}});
        }
    }
    private void previewPage(int page,int token){
        previewToken=token;File file=source;if(file==null)return;previews.execute(()->{if(token!=previewToken)return;try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd)){
            if(page<1||page>renderer.getPageCount())return;Bitmap b=PdfProcessor.render(renderer,page-1,700);try{if(token==previewToken)send(new JSONObject().put("type","preview").put("page",page).put("token",token).put("thumbnail",FilesUtil.thumbnail(b)));}finally{b.recycle();}
        }catch(Exception e){try{send(new JSONObject().put("type","previewError").put("token",token).put("message","تعذر عرض هذه الصفحة"));}catch(Exception ignored){}}});
    }
    private WebResourceResponse mediaResponse(File file,WebResourceRequest request)throws Exception{
        if(file==null||!file.isFile())return blocked();long length=file.length(),start=0,end=length-1;String range=request.getRequestHeaders().get("Range");boolean partial=false;
        if(range!=null&&range.matches("bytes=[0-9]+-[0-9]*")){String[] values=range.substring(6).split("-",-1);start=Long.parseLong(values[0]);if(!values[1].isEmpty())end=Math.min(end,Long.parseLong(values[1]));partial=true;}
        if(start<0||start>=length||end<start)return blocked();FileInputStream stream=new FileInputStream(file);stream.getChannel().position(start);final long count=end-start+1;
        InputStream limited=new FilterInputStream(stream){long left=count;@Override public int read()throws IOException{if(left<=0)return -1;int v=super.read();if(v>=0)left--;return v;}@Override public int read(byte[] b,int off,int len)throws IOException{if(left<=0)return -1;int n=super.read(b,off,(int)Math.min(left,len));if(n>0)left-=n;return n;}};
        Map<String,String> headers=new HashMap<>();headers.put("Accept-Ranges","bytes");headers.put("Content-Length",Long.toString(count));headers.put("Cache-Control","no-store");if(partial)headers.put("Content-Range","bytes "+start+"-"+end+"/"+length);
        String mime=file==source&&sourceInfo!=null?sourceInfo.optString("mime","video/mp4"):FilesUtil.mime(file);return new WebResourceResponse(mime,null,partial?206:200,partial?"Partial Content":"OK",headers,limited);
    }
    private String message(Throwable e){if(e instanceof OutOfMemoryError)return "الملف يتجاوز الذاكرة المتاحة؛ جرّب دقة أقل.";if(e instanceof SecurityException)return "الملف محمي أو تعذر الحصول على إذن قراءته.";String m=e.getMessage();return m==null?"تحقق من الملف والمساحة المتاحة.":m;}
    private void openOutput(File f,boolean share){
        Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",f);Intent i=new Intent(share?Intent.ACTION_SEND:Intent.ACTION_VIEW).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);i.setClipData(ClipData.newRawUri("Madar",uri));if(share){i.setType(FilesUtil.mime(f));i.putExtra(Intent.EXTRA_STREAM,uri);}else i.setDataAndType(uri,FilesUtil.mime(f));try{startActivity(Intent.createChooser(i,share?"مشاركة الملف":"فتح الملف"));}catch(ActivityNotFoundException e){error("لا يوجد تطبيق مناسب لفتح الملف");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;if(request==10)importFile(data.getData(),requestedKind);else if(request==11&&saving!=null){File f=saving;Uri dest=data.getData();saving=null;worker.execute(()->{try(InputStream in=new FileInputStream(f);OutputStream out=getContentResolver().openOutputStream(dest,"w")){if(out==null)throw new IOException("تعذر إنشاء الملف");FilesUtil.copy(in,out);event("saved",null,null);}catch(Exception e){error("تعذر الحفظ. "+message(e));}});}}
    private void history(){worker.execute(()->{try{File[] files=FilesUtil.directory(this).listFiles(f->f.isFile()&&!f.getName().endsWith(".partial"));JSONArray a=new JSONArray();if(files!=null){Arrays.sort(files,(x,y)->Long.compare(y.lastModified(),x.lastModified()));for(int i=0;i<Math.min(50,files.length);i++)a.put(FilesUtil.info(files[i]));}send(new JSONObject().put("type","history").put("files",a));}catch(Exception e){error("تعذر قراءة النتائج");}});}
    private void event(String type,String key,Object value){try{JSONObject o=new JSONObject().put("type",type);if(key!=null)o.put(key,value);send(o);}catch(JSONException ignored){}}
    private void error(String message){event("error","message",message);}
    private void send(JSONObject event){runOnUiThread(()->{if(ready&&web!=null)web.evaluateJavascript("window.receive("+event.toString()+");",null);});}
    @Override protected void onResume(){super.onResume();if(ready&&VideoExportService.active&&VideoExportService.lastEvent!=null)try{send(new JSONObject(VideoExportService.lastEvent));}catch(JSONException ignored){}}
    @Override protected void onDestroy(){ready=false;if(receiverRegistered)unregisterReceiver(updates);worker.shutdown();previews.shutdownNow();if(web!=null){web.removeJavascriptInterface("Android");web.destroy();web=null;}super.onDestroy();}
}
