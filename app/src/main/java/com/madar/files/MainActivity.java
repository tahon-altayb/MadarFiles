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
                    String file=r.getUrl().getPath();if(file==null||file.equals("/"))file="index.html";else file=file.substring(1);
                    if(!file.matches("index\\.html|style\\.css|app\\.js"))return blocked();
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
        @JavascriptInterface public void request(String value){if(value.length()>20000)return;runOnUiThread(()->{try{handle(new JSONObject(value));}catch(Exception e){error("تعذر تنفيذ الطلب: "+message(e));}});}
    }
    private void handle(JSONObject request)throws Exception {
        String action=request.getString("action");
        switch(action){
            case "ready":ready=true;history();restoreSource();if(VideoExportService.lastEvent!=null)send(new JSONObject(VideoExportService.lastEvent));if(Intent.ACTION_SEND.equals(getIntent().getAction())){Uri u=getIntent().getParcelableExtra(Intent.EXTRA_STREAM);getIntent().setAction(Intent.ACTION_MAIN);if(u!=null)importFile(u,null);}break;
            case "pick":if(isBusy())return;Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);String k=request.optString("kind","image");requestedKind=k;pick.setType(k.equals("video")?"video/*":k.equals("pdf")?"application/pdf":"image/*");startActivityForResult(pick,10);break;
            case "export":export(request.getJSONObject("options"));break;
            case "history":history();break;
            case "save":saving=FilesUtil.resolve(this,request.getString("name"));Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(FilesUtil.mime(saving)).putExtra(Intent.EXTRA_TITLE,saving.getName());startActivityForResult(save,11);break;
            case "share":case "view":openOutput(FilesUtil.resolve(this,request.getString("name")),action.equals("share"));break;
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
                String kind=mime.startsWith("image/")?"image":mime.startsWith("video/")?"video":mime.equals("application/pdf")?"pdf":expected;
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
                }else if(kind.equals("video")){
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
        if(kind.equals("video")){
            if(o.optString("tool").equals("audio")&&!info.optBoolean("hasAudio"))throw new IOException("الفيديو لا يحتوي على صوت");
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},20);
            Intent service=new Intent(this,VideoExportService.class).putExtra("input",input.getAbsolutePath()).putExtra("options",o.toString()).putExtra("duration",info.getLong("duration")).putExtra("height",info.getInt("height"));startForegroundService(service);
        }else{
            working=true;worker.execute(()->{try{File f=kind.equals("image")?ImageProcessor.export(this,input,o):PdfProcessor.export(this,input,o);working=false;send(FilesUtil.info(f).put("type","result"));}catch(Exception|OutOfMemoryError e){working=false;error("لم يكتمل التصدير. "+message(e));}});
        }
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
    @Override protected void onDestroy(){ready=false;if(receiverRegistered)unregisterReceiver(updates);worker.shutdown();if(web!=null){web.removeJavascriptInterface("Android");web.destroy();web=null;}super.onDestroy();}
}
