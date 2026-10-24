package com.madar.files;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.*;
import com.google.android.gms.common.api.*;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** OAuth stays native; tokens never enter the WebView, reports, saved state or app files. */
public final class ColabActivity extends Activity {
 private static final int AUTHORIZE=7201;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private AtomicBoolean cancel=new AtomicBoolean();private int generation;private boolean busy,awaitingResolution,destroyed;
 private boolean authorizeOnly,cleanupMode,automatic;private Button cleanup;
 private EditText project;private Button connect,stop,copy;private TextView status;private String report="",activeProject="";
 @Override public void onCreate(Bundle saved){
  super.onCreate(saved);authorizeOnly=getIntent().getBooleanExtra("authorizeSpeech",false);automatic=authorizeOnly&&getIntent().getBooleanExtra("autoStart",false);boolean textOnly=getIntent().getBooleanExtra("textOnly",false),musicOnly=getIntent().getBooleanExtra("musicOnly",false);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);int pad=(int)(20*getResources().getDisplayMetrics().density);box.setPadding(pad,pad,pad,pad);box.setBackgroundColor(Color.rgb(248,244,237));
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(box);setContentView(scroll);
  scroll.setOnApplyWindowInsetsListener((view,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);}else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});scroll.requestApplyInsets();
  TextView title=text(authorizeOnly?(musicOnly?"فصل الموسيقى على Colab":textOnly?"إعادة ترجمة النص على Colab":"التفريغ والترجمة على Colab"):"اتصال Colab · تجربة الربط",23);box.addView(title);
  box.addView(text(authorizeOnly?(musicOnly?"سيُرفع الصوت فقط إلى جلستك في Colab لفصل الموسيقى بنموذج UVR-MDX-NET-Voc_FT على المعالج. لا تُستخدم حصة Gemini. قد تُستهلك وحدات Colab؛ سيحاول مدار إنهاء جلسته بعد المعالجة أو الإلغاء.":textOnly?"سيُرسل النص المراجع وتوقيته فقط إلى جلسة Colab الخاصة بحسابك. لا يُرفع الصوت ولا يُعاد التفريغ. يعمل نموذج الترجمة على GPU وقد يستهلك وحدات Colab Pro، وسيحاول مدار إنهاء جلسته بعد المعالجة أو الإلغاء.":"سيُرفع الصوت فقط إلى جلسة Colab الخاصة بحسابك. تعمل النماذج على GPU وقد تستهلك وحدات Colab Pro. سيحاول مدار إنهاء جلسته بعد المعالجة أو الإلغاء. أول تشغيل يحتاج تنزيل النماذج على Colab؛ ابدأ بعينة 30 ثانية."):"اختر حساب Google الذي أضفته إلى مستخدمي الاختبار. سيطلب مدار صلاحية Colab، ثم يعرض اشتراكك والموارد المؤهلة لحسابك. هذا الفحص لا يرفع ملفات ولا يبدأ جلسة معالجة. لتجربة المعالجة اختر Colab من أداة التفريغ والترجمة.",16));
  box.addView(text("معرّف مشروع Google Cloud الموافق عليه",16));project=new EditText(this);project.setSingleLine(true);project.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);project.setTextDirection(View.TEXT_DIRECTION_LTR);project.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);project.setText(getSharedPreferences("colab_connection",0).getString("project","madarfiles"));box.addView(project);
  connect=new Button(this);connect.setText(authorizeOnly?"تسجيل الدخول وبدء المعالجة":"تسجيل الدخول وفحص Colab");connect.setOnClickListener(v->{cleanupMode=false;start();});box.addView(connect);
  stop=new Button(this);stop.setText(authorizeOnly?"العودة دون تشغيل":"إلغاء الفحص");stop.setEnabled(false);stop.setOnClickListener(v->{cancel.set(true);generation++;awaitingResolution=false;complete("أُلغي الفحص. لم تُنشأ جلسة معالجة.");if(authorizeOnly)finish();});box.addView(stop);
  status=text("جاهز للاتصال. لا تحتاج إلى لصق مفتاح API هنا.",16);status.setTextIsSelectable(true);box.addView(status);
  copy=new Button(this);copy.setText("نسخ نتيجة الفحص");copy.setVisibility(authorizeOnly?View.GONE:View.VISIBLE);copy.setEnabled(false);copy.setOnClickListener(v->{if(!report.isEmpty()){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("فحص Colab",report));Toast.makeText(this,"نُسخت النتيجة دون رموز دخول",Toast.LENGTH_SHORT).show();}});box.addView(copy);
  Button processingReport=new Button(this);processingReport.setText("حالة آخر معالجة Colab");processingReport.setVisibility(authorizeOnly?View.GONE:View.VISIBLE);processingReport.setOnClickListener(v->{String value=ColabDiagnostics.last(this);if(value.isEmpty())value="لا توجد معالجة Colab مسجلة بعد.";final String summary=value;new AlertDialog.Builder(this).setTitle("حالة المعالجة").setMessage(summary).setPositiveButton("نسخ التقرير",(dialog,which)->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("حالة معالجة Colab",summary));Toast.makeText(this,"نُسخ تقرير المعالجة",Toast.LENGTH_SHORT).show();}).setNegativeButton("إغلاق",null).show();});box.addView(processingReport);
  cleanup=new Button(this);cleanup.setText("إنهاء جلسة مدار السابقة");cleanup.setVisibility(authorizeOnly?View.GONE:View.VISIBLE);cleanup.setOnClickListener(v->{cleanupMode=true;start();});box.addView(cleanup);
  if(getSharedPreferences("colab_owned_runtime",0).contains("name"))box.addView(text("توجد جلسة مدار لم يُؤكّد إنهاؤها. اضغط إنهاء جلسة مدار السابقة؛ قد تستمر في استهلاك وحدات الحساب حتى إيقافها.",16));
  Button close=new Button(this);close.setText("العودة إلى مدار");close.setOnClickListener(v->finish());box.addView(close);
  if(automatic){for(int i=0;i<box.getChildCount();i++){View child=box.getChildAt(i);if(child!=title&&child!=status&&child!=stop)child.setVisibility(View.GONE);}status.setText("جارٍ توصيل Colab وبدء المعالجة…");if(saved==null)start();}
  if(saved!=null){cleanupMode=saved.getBoolean("cleanupMode");project.setText(saved.getString("project",project.getText().toString()));String previous=saved.getString("report","");if(!previous.isEmpty())complete(previous);if(saved.getBoolean("pending")){activeProject=saved.getString("activeProject","");awaitingResolution=true;setBusy(true);status.setText("بانتظار إكمال موافقة Google؛ يمكنك إلغاء الفحص وإعادته.");}else if(saved.getBoolean("busy"))complete("أُوقف الفحص عند إعادة فتح الشاشة؛ اضغط اتصال لإعادته.");}
 }
 private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(Color.rgb(37,48,45));view.setPadding(0,12,0,12);return view;}
 private void setBusy(boolean value){busy=value;connect.setEnabled(!value);project.setEnabled(!value);if(cleanup!=null)cleanup.setEnabled(!value);stop.setEnabled(value);copy.setEnabled(!value&&!report.isEmpty());}
 private void complete(String value){awaitingResolution=false;report=value;status.setText(value);setBusy(false);if(automatic){connect.setVisibility(View.VISIBLE);connect.setText("إعادة الاتصال والمتابعة");stop.setEnabled(true);}}
 private void start(){
  if(busy)return;String selected=project.getText().toString().trim();if(!ColabProbe.validProject(selected)){complete("أدخل معرّف المشروع، مثل madarfiles، وليس اسم العرض أو رقم المشروع.");return;}
  if(GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)!=ConnectionResult.SUCCESS){complete("خدمات Google Play غير متاحة أو تحتاج تحديثًا على هذا الجهاز. حدّثها ثم أعد الفحص.");return;}
  activeProject=selected;getSharedPreferences("colab_connection",0).edit().putString("project",selected).apply();cancel.set(true);cancel=new AtomicBoolean();report="";setBusy(true);status.setText("جارٍ طلب موافقة حساب Google…");int ticket=++generation;
  AuthorizationRequest request=AuthorizationRequest.builder().setRequestedScopes(Collections.singletonList(new Scope(ColabProbe.SCOPE))).build();
  Identity.getAuthorizationClient(this).authorize(request).addOnSuccessListener(this,result->{if(!current(ticket))return;try{if(result.hasResolution()){PendingIntent pending=result.getPendingIntent();if(pending==null)throw new IllegalStateException();awaitingResolution=true;startIntentSenderForResult(pending.getIntentSender(),AUTHORIZE,null,0,0,0);}else accepted(result,ticket);}catch(Exception e){complete("تعذر فتح موافقة Google؛ أعد الاتصال.");}}).addOnFailureListener(this,e->{if(current(ticket))authFailure(e);});
 }
 private boolean current(int ticket){return !destroyed&&!isFinishing()&&busy&&!cancel.get()&&ticket==generation;}
 @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
  super.onActivityResult(requestCode,resultCode,data);if(requestCode!=AUTHORIZE||!awaitingResolution)return;awaitingResolution=false;
  if(resultCode!=RESULT_OK||data==null){complete("لم تكتمل موافقة Google. يمكنك إعادة الاتصال عند الرغبة.");return;}
  try{accepted(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data),generation);}catch(Exception e){authFailure(e);}
 }
 private void accepted(AuthorizationResult result,int ticket){
  if(!current(ticket))return;
  String token=result.getAccessToken();if(token==null||token.isEmpty()||!result.getGrantedScopes().contains(ColabProbe.SCOPE)){complete("لم تُمنح صلاحية Colab كاملة. راجع حساب الاختبار وصلاحية colaboratory في إعدادات Google.");return;}
  if(authorizeOnly){ColabCredentials.put(token,activeProject);setResult(RESULT_OK);finish();return;}
  AtomicBoolean operation=cancel;String selected=activeProject;boolean cleanupRequested=cleanupMode;status.setText("تم تصريح الدخول؛ جارٍ فحص Colab…");
  worker.execute(()->{try{String summary;if(cleanupRequested){try(ColabRuntime runtime=new ColabRuntime(this,new ColabCredentials.Grant(token,selected),operation)){runtime.cleanupPrevious();summary="تم تأكيد إنهاء جلسة مدار السابقة؛ لم تُمس جلساتك الأخرى.";}}else summary=ColabProbe.run(token,selected,operation,message->runOnUiThread(()->{if(current(ticket))status.setText(message);}));runOnUiThread(()->{if(current(ticket))complete(summary);});}
   catch(CancellationException ignored){}
   catch(Exception e){int statusCode=e instanceof ColabProbe.Failure?((ColabProbe.Failure)e).status:0;String message=e instanceof ColabProbe.Failure?e.getMessage():e instanceof org.json.JSONException?"استجابة Colab غير مكتملة؛ أعد الفحص أو أرسل النتيجة للمراجعة.":e instanceof java.io.IOException?e.getMessage():"تعذر إكمال فحص Colab؛ أعد المحاولة.";
    runOnUiThread(()->{if(current(ticket)){if(statusCode==401)Identity.getAuthorizationClient(this).clearToken(ClearTokenRequest.builder().setToken(token).build());complete("مدار 1.4.10 · فحص Colab\nالمشروع: "+selected+"\nلم يكتمل الفحص: "+message+"\nلم تُنشأ جلسة ولم تُرفع ملفات.");}});
   }
  });
 }
 private void authFailure(Exception e){int code=e instanceof ApiException?((ApiException)e).getStatusCode():-1;String hint=code==10?"تحقق من عميل Android: الحزمة com.madar.files وبصمة توقيع مدار، ومن تفعيل إعدادات OAuth.":code==7?"تحقق من اتصال الشبكة وخدمات Google Play.":code==16?"أُلغيت موافقة Google.":"تحقق من إضافة الحساب إلى مستخدمي الاختبار وصلاحية Colab، ثم أعد المحاولة.";complete("تعذر تصريح حساب Google ("+code+").\n"+hint);}
 @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("project",project.getText().toString());out.putString("activeProject",activeProject);out.putString("report",report);out.putBoolean("pending",awaitingResolution);out.putBoolean("busy",busy);out.putBoolean("cleanupMode",cleanupMode);}
 @Override protected void onDestroy(){destroyed=true;generation++;cancel.set(true);worker.shutdownNow();super.onDestroy();}
}
