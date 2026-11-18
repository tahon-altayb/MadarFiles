package com.madar.files;

import android.accounts.Account;
import android.accounts.AccountManager;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** OAuth stays native; tokens never enter the WebView, reports, saved state or app files. */
public final class ColabActivity extends Activity {
 private static final int AUTHORIZE=7201, MODEL_STORAGE=7211, CHOOSE_ACCOUNT=7212;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private AtomicBoolean cancel=new AtomicBoolean();private int generation;private boolean busy,destroyed;private int pendingRequest;
 private boolean authorizeOnly,cleanupMode,automatic,bandit,modelStorage;
 private Account banditAccount;private ColabCredentials.Grant pendingModelGrant;
 private record Retained(Account account,ColabCredentials.Grant grant){}private Button cleanup;
 private EditText project;private Button connect,stop,copy;private TextView status;private String report="",activeProject="";
 @Override public void onCreate(Bundle saved){
  super.onCreate(saved);authorizeOnly=getIntent().getBooleanExtra("authorizeSpeech",false);automatic=authorizeOnly&&getIntent().getBooleanExtra("autoStart",false);bandit=authorizeOnly&&getIntent().getBooleanExtra("bandit",false);modelStorage=authorizeOnly&&(bandit||getIntent().getBooleanExtra("musicOnly",false)||getIntent().getBooleanExtra("modelStorage",false));if(getLastNonConfigurationInstance() instanceof Retained retained){banditAccount=retained.account();pendingModelGrant=retained.grant();}boolean textOnly=getIntent().getBooleanExtra("textOnly",false),musicOnly=getIntent().getBooleanExtra("musicOnly",false);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);int pad=(int)(20*getResources().getDisplayMetrics().density);box.setPadding(pad,pad,pad,pad);box.setBackgroundColor(Color.rgb(248,244,237));
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(box);setContentView(scroll);
  scroll.setOnApplyWindowInsetsListener((view,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);}else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});scroll.requestApplyInsets();
  TextView title=text(authorizeOnly?(musicOnly?"فصل الموسيقى على Colab":textOnly?"إعادة ترجمة النص على Colab":"التفريغ والترجمة على Colab"):"اتصال Colab · تجربة الربط",23);box.addView(title);if(getIntent().getBooleanExtra("motionStudio",false))title.setText("استوديو الفيديو · Colab");boolean generating=getIntent().getBooleanExtra("generate",false);if(generating)title.setText("التوليد بالذكاء · Colab");if(bandit)title.setText("الحوار والمؤثرات · Bandit");
  box.addView(text(generating?"يُرسل وصف التوليد فقط إلى جلستك في Colab: الصور بنموذج SDXL على كرت T4، والمقاطع بنموذج Wan 2.2 على كرت L4 أو A100. أول مرة ينزل النموذج (الفيديو حوالي 20 جيجا) فتاخذ وقت أطول. قد تُستهلك وحدات حسابك، وتُغلق الجلسة بعد التنزيل.":getIntent().getBooleanExtra("motionStudio",false)?"تُرسل بيانات المشاهد والصور والصوت اللازمة إلى جلستك في Colab لقياس التوقيت أو تصدير الفيديو. قد تستهلك الجلسة وحدات حسابك بحسب المورد المتاح. تبقى جلسة الموشن مفتوحة للمراجعات حتى اعتماد النسخة أو إغلاقها صراحة. قد ينهي Colab الجلسة أو يوقفها النظام، وتبقى المشاريع والنتائج محفوظة. تعليق قوقل وتوليد الصور يخضعان لحصة حسابك.":authorizeOnly?(musicOnly?"سيُرفع الصوت فقط إلى جلستك في Colab لفصل الموسيقى بنموذج UVR-MDX-NET-Voc_FT على GPU. لا تُستخدم حصة Gemini. قد تُستهلك وحدات Colab. تُحتفظ بالجلسة والنموذج حتى 20 دقيقة بعد نجاح العملية لتسريع الملف التالي، ويمكن إيقافها من الإشعار. بدء مورد جديد يعتمد على توفره لدى Colab.":textOnly?"سيُرسل النص المراجع وتوقيته فقط إلى جلسة Colab الخاصة بحسابك. لا يُرفع الصوت ولا يُعاد التفريغ. يعمل نموذج الترجمة على GPU وقد يستهلك وحدات Colab Pro، وسيحاول مدار إنهاء جلسته بعد المعالجة أو الإلغاء.":"سيُرفع الصوت فقط إلى جلسة Colab الخاصة بحسابك. تعمل النماذج على GPU وقد تستهلك وحدات Colab Pro. سيحاول مدار إنهاء جلسته بعد المعالجة أو الإلغاء. أول تشغيل يحتاج تنزيل النماذج على Colab؛ ابدأ بعينة 30 ثانية."):"اختر حساب Google الذي أضفته إلى مستخدمي الاختبار. سيطلب مدار صلاحية Colab، ثم يعرض اشتراكك والموارد المؤهلة لحسابك. هذا الفحص لا يرفع ملفات ولا يبدأ جلسة معالجة. لتجربة المعالجة اختر Colab من أداة التفريغ والترجمة.",16));
  if(modelStorage){
   TextView storageNotice=text("يحفظ مدار أوزان النماذج في مساحة التطبيق الخاصة في Google Drive بإذنك. يمكنك متابعة المعالجة إذا لم يكتمل إذن التخزين.",16);
   storageNotice.setTag(Boolean.TRUE);box.addView(storageNotice);
  }
  box.addView(text("معرّف مشروع Google Cloud الموافق عليه",16));project=new EditText(this);project.setSingleLine(true);project.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);project.setTextDirection(View.TEXT_DIRECTION_LTR);project.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);project.setText(getSharedPreferences("colab_connection",0).getString("project","madarfiles"));box.addView(project);
  if(!authorizeOnly||musicOnly){
   Button gpu=new Button(this);String[] values={"auto","T4","L4","A100"};String[] names={"تلقائي · آخر مورد نجح، ثم T4", "T4", "L4", "A100"};
   gpu.setText("مورد GPU لإزالة الموسيقى");gpu.setOnClickListener(v->{String selected=getSharedPreferences("colab_connection",0).getString("music_gpu","auto");int index=java.util.Arrays.asList(values).indexOf(selected);new AlertDialog.Builder(this).setTitle("GPU لإزالة الموسيقى").setSingleChoiceItems(names,Math.max(0,index),(d,n)->{getSharedPreferences("colab_connection",0).edit().putString("music_gpu",values[n]).apply();d.dismiss();Toast.makeText(this,"يُستخدم في جلسة جديدة؛ الجلسة الجاهزة تُعاد الاستفادة منها",Toast.LENGTH_LONG).show();}).setNegativeButton("رجوع",null).show();});box.addView(gpu);
  }
  connect=new Button(this);connect.setText(authorizeOnly?"تسجيل الدخول وبدء المعالجة":"تسجيل الدخول وفحص Colab");connect.setOnClickListener(v->{cleanupMode=false;start();});box.addView(connect);
  stop=new Button(this);stop.setText(authorizeOnly?"العودة دون تشغيل":"إلغاء الفحص");stop.setEnabled(false);stop.setOnClickListener(v->{cancel.set(true);generation++;pendingRequest=0;complete("أُلغي الفحص. لم تُنشأ جلسة معالجة.");if(authorizeOnly)finish();});box.addView(stop);
  status=text("جاهز للاتصال. لا تحتاج إلى لصق مفتاح API هنا.",16);status.setTextIsSelectable(true);box.addView(status);
  copy=new Button(this);copy.setText("نسخ نتيجة الفحص");copy.setVisibility(authorizeOnly?View.GONE:View.VISIBLE);copy.setEnabled(false);copy.setOnClickListener(v->{if(!report.isEmpty()){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("فحص Colab",report));Toast.makeText(this,"نُسخت النتيجة دون رموز دخول",Toast.LENGTH_SHORT).show();}});box.addView(copy);
  Button processingReport=new Button(this);processingReport.setText("حالة آخر معالجة Colab");processingReport.setVisibility(authorizeOnly?View.GONE:View.VISIBLE);processingReport.setOnClickListener(v->{String value=ColabDiagnostics.last(this);if(value.isEmpty())value="لا توجد معالجة Colab مسجلة بعد.";final String summary=value;new AlertDialog.Builder(this).setTitle("حالة المعالجة").setMessage(summary).setPositiveButton("نسخ التقرير",(dialog,which)->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("حالة معالجة Colab",summary));Toast.makeText(this,"نُسخ تقرير المعالجة",Toast.LENGTH_SHORT).show();}).setNegativeButton("إغلاق",null).show();});box.addView(processingReport);
  cleanup=new Button(this);cleanup.setText("إنهاء جلسة مدار السابقة");cleanup.setVisibility(authorizeOnly?View.GONE:View.VISIBLE);cleanup.setOnClickListener(v->{cleanupMode=true;start();});box.addView(cleanup);
  if(MotionGpuSession.held())box.addView(text("جلسة الموشن مفتوحة للمراجعات؛ اعتمد النسخة من المحادثة أو أغلقها من الإشعار.",16));else if(MusicGpuSession.available(this))box.addView(text("جلسة GPU والنموذج جاهزان لإعادة الاستخدام؛ تُغلق الجلسة بعد مدة تصل إلى عشرين دقيقة خمول أو من زر الإيقاف في الإشعار.",16));else if(getSharedPreferences("colab_owned_runtime",0).contains("name"))box.addView(text("توجد جلسة مدار لم يُؤكّد إنهاؤها. اضغط إنهاء جلسة مدار السابقة؛ قد تستمر في استهلاك وحدات الحساب حتى إيقافها.",16));
  Button close=new Button(this);close.setText("العودة إلى مدار");close.setOnClickListener(v->finish());box.addView(close);
  if(automatic){for(int i=0;i<box.getChildCount();i++){View child=box.getChildAt(i);if(child!=title&&child!=status&&child!=stop&&!Boolean.TRUE.equals(child.getTag()))child.setVisibility(View.GONE);}status.setText("جارٍ توصيل Colab وبدء المعالجة…");if(saved==null)start();}
  if(saved!=null){cleanupMode=saved.getBoolean("cleanupMode");project.setText(saved.getString("project",project.getText().toString()));String previous=saved.getString("report","");if(!previous.isEmpty())complete(previous);pendingRequest=saved.getInt("pendingRequest");if(pendingRequest!=0){activeProject=saved.getString("activeProject","");setBusy(true);status.setText("بانتظار إكمال موافقة Google؛ يمكنك إلغاء الفحص وإعادته.");}else if(saved.getBoolean("busy"))complete("أُوقف الفحص عند إعادة فتح الشاشة؛ اضغط اتصال لإعادته.");}
 }
 private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(Color.rgb(37,48,45));view.setPadding(0,12,0,12);return view;}
 private void setBusy(boolean value){busy=value;connect.setEnabled(!value);project.setEnabled(!value);if(cleanup!=null)cleanup.setEnabled(!value);stop.setEnabled(value);copy.setEnabled(!value&&!report.isEmpty());}
 private void complete(String value){pendingRequest=0;banditAccount=null;pendingModelGrant=null;report=value;status.setText(value);setBusy(false);if(automatic){connect.setVisibility(View.VISIBLE);connect.setText("إعادة الاتصال والمتابعة");stop.setEnabled(true);}}
 private void start(){
  if(busy)return;String selected=project.getText().toString().trim();if(!ColabProbe.validProject(selected)){complete("أدخل معرّف المشروع، مثل madarfiles، وليس اسم العرض أو رقم المشروع.");return;}
  if(GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)!=ConnectionResult.SUCCESS){complete("خدمات Google Play غير متاحة أو تحتاج تحديثًا على هذا الجهاز. حدّثها ثم أعد الفحص.");return;}
  boolean retry=!report.isEmpty();
  activeProject=selected;getSharedPreferences("colab_connection",0).edit().putString("project",selected).apply();
  cancel.set(true);cancel=new AtomicBoolean();report="";setBusy(true);int ticket=++generation;
  if(modelStorage){
   try{
    Account[] visible=AccountManager.get(this).getAccountsByType("com.google");
    String savedAccount=getSharedPreferences("colab_connection",0).getString("model_account","");
    if(!retry)for(Account account:visible)if(account.name.equals(savedAccount)){
     banditAccount=account;authorize(ticket,AUTHORIZE);return;
    }
    status.setText("اختر حساب Google لاستخدامه مع Colab وحفظ الأوزان");
    pendingRequest=CHOOSE_ACCOUNT;
    startActivityForResult(AccountManager.newChooseAccountIntent(null,null,new String[]{"com.google"},
      null,null,null,null),CHOOSE_ACCOUNT);
   }catch(RuntimeException e){complete("تعذر فتح اختيار الحساب. تأكد من إضافة حساب Google في إعدادات الجوال، ثم أعد الاتصال.");}
   return;
  }
  authorize(ticket,AUTHORIZE);
 }
 private void authorize(int ticket,int step){
  if(!current(ticket))return;
  if(modelStorage&&banditAccount==null){complete("اختر حساب Google للمتابعة.");return;}
  boolean storage=step==MODEL_STORAGE;
  String scope=storage?ColabCredentials.MODEL_STORAGE_SCOPE:ColabProbe.SCOPE;
  status.setText(storage?"جارٍ ربط مساحة أوزان مدار الخاصة على Google Drive…":"جارٍ طلب موافقة حساب Google…");
  AuthorizationRequest request=ColabCredentials.authorizationRequest(scope,banditAccount);
  Identity.getAuthorizationClient(this).authorize(request).addOnSuccessListener(this,result->{
   if(!current(ticket))return;
   try{
    if(result.hasResolution()){
     PendingIntent pending=result.getPendingIntent();if(pending==null)throw new IllegalStateException();
     pendingRequest=step;startIntentSenderForResult(pending.getIntentSender(),step,null,0,0,0);
    }else acceptAuthorization(result,ticket,step);
   }catch(Exception e){if(storage)offerWithoutStorage();else complete("تعذر فتح موافقة Google؛ أعد الاتصال.");}
  }).addOnFailureListener(this,e->{if(current(ticket)){if(storage)offerWithoutStorage();else authFailure(e);}});
 }
 private boolean current(int ticket){return !destroyed&&!isFinishing()&&busy&&!cancel.get()&&ticket==generation;}
 @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
  super.onActivityResult(requestCode,resultCode,data);
  if(pendingRequest==0||requestCode!=pendingRequest||!current(generation))return;
  pendingRequest=0;
  if(resultCode!=RESULT_OK||data==null){if(requestCode==MODEL_STORAGE){offerWithoutStorage();return;}complete(requestCode==CHOOSE_ACCOUNT?"يمكنك اختيار حساب Google عند إعادة الاتصال.":"لم تكتمل موافقة Google. يمكنك إعادة الاتصال عند الرغبة.");return;}
  if(requestCode==CHOOSE_ACCOUNT){
   banditAccount=selectedGoogleAccount(data);
   if(banditAccount==null){complete("اختر حساب Google للمتابعة.");return;}
   authorize(generation,AUTHORIZE);return;
  }
  try{acceptAuthorization(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data),generation,requestCode);}
  catch(Exception e){if(requestCode==MODEL_STORAGE)offerWithoutStorage();else authFailure(e);}
 }
 static Account selectedGoogleAccount(Intent data){
  String name=data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
  String type=data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE);
  return name==null||name.trim().isEmpty()||!"com.google".equals(type)?null:new Account(name,type);
 }
 private void acceptAuthorization(AuthorizationResult result,int ticket,int step){
  if(step==MODEL_STORAGE)acceptModelStorage(result,ticket);else accepted(result,ticket);
 }
 private void accepted(AuthorizationResult result,int ticket){
  if(!current(ticket))return;
  String token=result.getAccessToken();if(token==null||token.isEmpty()||!result.getGrantedScopes().contains(ColabProbe.SCOPE)){complete("لم تُمنح صلاحية Colab كاملة. راجع حساب الاختبار وصلاحية colaboratory في إعدادات Google.");return;}
  if(modelStorage){
   if(banditAccount==null){complete("أعد اختيار حساب Google لمتابعة الربط.");return;}
   pendingModelGrant=new ColabCredentials.Grant(token,activeProject,null,banditAccount);
   authorize(ticket,MODEL_STORAGE);return;
  }
  if(authorizeOnly){ColabCredentials.put(token,activeProject);setResult(RESULT_OK);finish();return;}
  AtomicBoolean operation=cancel;String selected=activeProject;boolean cleanupRequested=cleanupMode;status.setText("تم تصريح الدخول؛ جارٍ فحص Colab…");
  worker.execute(()->{try{String summary;if(cleanupRequested){if(MotionStudioService.active)throw new java.io.IOException("أوقف معالجة الموشن الحالية قبل إنهاء الجلسة");MotionGpuSession.closeIdle();MusicGpuSession.stopIdle();try(ColabRuntime runtime=new ColabRuntime(this,new ColabCredentials.Grant(token,selected),operation)){runtime.cleanupPrevious();summary="تم تأكيد إنهاء جلسة مدار السابقة؛ لم تُمس جلساتك الأخرى.";}}else summary=ColabProbe.run(token,selected,operation,message->runOnUiThread(()->{if(current(ticket))status.setText(message);}));runOnUiThread(()->{if(current(ticket))complete(summary);});}
   catch(CancellationException ignored){}
   catch(Exception e){int statusCode=e instanceof ColabProbe.Failure?((ColabProbe.Failure)e).status:0;String message=e instanceof ColabProbe.Failure?e.getMessage():e instanceof org.json.JSONException?"استجابة Colab غير مكتملة؛ أعد الفحص أو أرسل النتيجة للمراجعة.":e instanceof java.io.IOException?e.getMessage():"تعذر إكمال فحص Colab؛ أعد المحاولة.";
    runOnUiThread(()->{if(current(ticket)){if(statusCode==401)Identity.getAuthorizationClient(this).clearToken(ClearTokenRequest.builder().setToken(token).build());complete("مدار 1.4.10 · فحص Colab\nالمشروع: "+selected+"\nلم يكتمل الفحص: "+message+"\nلم تُنشأ جلسة ولم تُرفع ملفات.");}});
   }
  });
 }
 private void acceptModelStorage(AuthorizationResult result,int ticket){
  if(!current(ticket))return;
  String token=result.getAccessToken();
  if(!ColabCredentials.validStorageToken(token)){offerWithoutStorage();return;}
  if(ColabCredentials.storageScopes(result.getGrantedScopes())){finishStorage(token);return;}
  // Play Services can report earlier grants as well. Check the token itself at Google's fixed endpoint.
  status.setText("جارٍ التحقق من صلاحية تخزين الأوزان…");
  worker.execute(()->{boolean allowed=ColabCredentials.verifyStorageToken(token);runOnUiThread(()->{
   if(!current(ticket))return;if(allowed)finishStorage(token);else offerWithoutStorage();
  });});
 }
 private void offerWithoutStorage(){
  if(pendingModelGrant==null){complete("أعد اختيار حساب Google للمتابعة.");return;}
  new AlertDialog.Builder(this).setTitle("إذن حفظ الأوزان")
   .setMessage("تعذر التحقق من إذن مساحة مدار الخاصة في Drive. يمكنك تشغيل المعالجة الآن وتنزيل الأوزان عند الحاجة، أو إعادة المحاولة لإكمال الربط.")
   .setPositiveButton("متابعة المعالجة",(dialog,which)->finishStorage(null))
   .setNegativeButton("إعادة المحاولة",(dialog,which)->complete("اضغط إعادة الاتصال لاختيار الحساب وإعادة طلب إذن Drive."))
   .setOnCancelListener(dialog->complete("أُلغي طلب إذن Drive.")).show();
 }
 private void finishStorage(String token){
  if(pendingModelGrant==null){complete("أعد اختيار حساب Google للمتابعة.");return;}
  if(token!=null&&pendingModelGrant.account!=null)getSharedPreferences("colab_connection",0).edit().putString("model_account",pendingModelGrant.account.name).apply();
  ColabCredentials.put(pendingModelGrant.token,pendingModelGrant.project,token,pendingModelGrant.account);
  pendingModelGrant=null;setResult(RESULT_OK);finish();
 }
 @Override public Object onRetainNonConfigurationInstance(){return banditAccount==null?null:new Retained(banditAccount,pendingModelGrant);}
 private void authFailure(Exception e){int code=e instanceof ApiException?((ApiException)e).getStatusCode():-1;String hint=code==10?"تحقق من عميل Android: الحزمة com.madar.files وبصمة توقيع مدار، ومن تفعيل إعدادات OAuth.":code==7?"تحقق من اتصال الشبكة وخدمات Google Play.":code==16?"أُلغيت موافقة Google.":"تحقق من إضافة الحساب إلى مستخدمي الاختبار وصلاحية Colab، ثم أعد المحاولة.";complete("تعذر تصريح حساب Google ("+code+").\n"+hint);}
 @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("project",project.getText().toString());out.putString("activeProject",activeProject);out.putString("report",report);out.putInt("pendingRequest",pendingRequest);out.putBoolean("busy",busy);out.putBoolean("cleanupMode",cleanupMode);}
 @Override protected void onDestroy(){destroyed=true;generation++;cancel.set(true);worker.shutdownNow();super.onDestroy();}
}
