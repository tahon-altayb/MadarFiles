package com.madar.files;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.util.Base64;
import android.widget.*;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Credentials never enter WebView, exports, logs or the APK. */
final class TranslationSettings {
 private static final String ALIAS="madar.translation.key";
 private static javax.crypto.SecretKey key() throws Exception {
  KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
  if(ks.containsAlias(ALIAS))return (javax.crypto.SecretKey)ks.getKey(ALIAS,null);
  KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
  gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return gen.generateKey();
 }
 static String credential(Context c)throws Exception {
  String saved=c.getSharedPreferences("cloud_translation",0).getString("credential","");if(saved.isEmpty())return "";
  String[] parts=saved.split(":",-1);if(parts.length!=2)throw new java.io.IOException("أعد حفظ مفتاح خدمة الترجمة");
  Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
  return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8);
 }
 static String model(Context c){return c.getSharedPreferences("cloud_translation",0).getString("model","gemini-3.8-flash");}
 static void save(Context c,String value,String model)throws Exception {
  if(!model.matches("gemini-[a-zA-Z0-9.\\-]{1,80}"))throw new java.io.IOException("تحقق من اسم نموذج Gemini");
  Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
  String encrypted=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Base64.NO_WRAP);
  if(!c.getSharedPreferences("cloud_translation",0).edit().putString("credential",encrypted).putString("model",model).commit())throw new java.io.IOException("تعذر حفظ الإعدادات");
 }
 static void show(Activity a){
  LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);int pad=(int)(20*a.getResources().getDisplayMetrics().density);box.setPadding(pad,pad,pad,pad);
  TextView note=new TextView(a);note.setText("ترجمة Gemini بالسياق. أدخل مفتاحك من Google AI Studio. يرسل هذا الوضع النص والتوقيت إلى Google عند بدء الترجمة؛ الصوت والفيديو يبقيان على الجهاز. قد تنطبق رسوم وحدود حسابك. يبقى الوضع المحلي متاحًا دون مفتاح.");box.addView(note);
  EditText secret=new EditText(a);secret.setHint("مفتاح Gemini جديد · اتركه فارغًا للاحتفاظ بالمحفوظ");secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);secret.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);box.addView(secret);
  EditText model=new EditText(a);model.setSingleLine(true);model.setText(model(a));model.setHint("اسم النموذج");box.addView(model);
  AlertDialog dialog=new AlertDialog.Builder(a).setTitle("إعداد الترجمة بالإنترنت").setView(box).setPositiveButton("حفظ",null).setNegativeButton("إلغاء",null).setNeutralButton("حذف المفتاح",(d,w)->a.getSharedPreferences("cloud_translation",0).edit().clear().apply()).create();
  dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{String value=secret.getText().toString().trim();if(value.isEmpty())value=credential(a);if(value.isEmpty()||value.length()>512||value.matches(".*\\s.*"))throw new java.io.IOException("أدخل مفتاحًا صالحًا دون مسافات");save(a,value,model.getText().toString().trim());secret.setText("");Toast.makeText(a,"حُفظ المفتاح مشفرًا على الجهاز؛ يُختبر عند الترجمة",Toast.LENGTH_LONG).show();dialog.dismiss();}catch(Exception e){Toast.makeText(a,"تعذر الحفظ؛ تحقق من المفتاح واسم النموذج",Toast.LENGTH_LONG).show();}}));dialog.show();
 }
}
