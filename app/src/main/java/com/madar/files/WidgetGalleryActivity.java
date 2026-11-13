package com.madar.files;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** «التطبيقات المصغّرة»: every Madar widget as it looks on the home screen, with one button that asks the launcher to add it. */
public class WidgetGalleryActivity extends Activity {
 static final String[] ORDER={"links","hub","video","image","pdf","audio","qr"};
 private int dp(float v){return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,v,getResources().getDisplayMetrics()));}
 private TextView text(String value,float sp,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setTextDirection(View.TEXT_DIRECTION_RTL);t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
 static String title(String kind){return kind.equals("hub")?"مدار · كل الأدوات":"مدار · "+MadarWidgets.name(kind);}
 static String about(String kind){
  switch(kind){case "links":return "انسخ رابط المقطع من أي تطبيق، اضغط «لصق وتحميل»، واختر الجودة من المدار.";case "hub":return "كل أقسام مدار في مكان واحد، مع زر التحميل السريع.";
   case "qr":return "حوّل رابطًا أو نصًا أو ملفًا إلى رمز QR بضغطة.";default:return "اختصارات لأهم أدوات "+MadarWidgets.name(kind)+": تختار الملف ثم تفتح الأداة مباشرة.";}
 }
 @Override protected void onCreate(Bundle saved){
  super.onCreate(saved);
  int ink=Color.parseColor("#283C3B"),muted=Color.parseColor("#7D8580"),orange=Color.parseColor("#CE6B43");
  ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Color.parseColor("#F8F4ED"));scroll.setFillViewport(true);
  LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);page.setPadding(dp(18),dp(22),dp(18),dp(28));scroll.addView(page,new ScrollView.LayoutParams(-1,-2));
  LinearLayout head=new LinearLayout(this);head.setOrientation(LinearLayout.HORIZONTAL);head.setGravity(Gravity.CENTER_VERTICAL);
  TextView heading=text("التطبيقات المصغّرة",24,ink,true);head.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
  TextView close=text("×",26,ink,false);close.setGravity(Gravity.CENTER);close.setContentDescription("إغلاق");close.setOnClickListener(v->finish());head.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));page.addView(head);
  TextView lead=text("أضف أدوات مدار إلى شاشتك الرئيسية. بعد الإضافة اضغط مطولًا على أي واحد منها لتغيير حجمه.",14,muted,false);lead.setLineSpacing(dp(3),1);page.addView(lead);
  boolean canPin=false;AppWidgetManager manager=AppWidgetManager.getInstance(this);try{canPin=manager!=null&&manager.isRequestPinAppWidgetSupported();}catch(RuntimeException ignored){}
  int width=Math.round(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density)-36;
  for(String kind:ORDER){
   LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(-1,-2);gap.topMargin=dp(22);
   page.addView(text(title(kind),17,ink,true),gap);
   TextView note=text(about(kind),13,muted,false);note.setLineSpacing(dp(2),1);page.addView(note);
   int height=kind.equals("hub")?132:kind.equals("links")?64:60;
   FrameLayout stage=new FrameLayout(this);LinearLayout.LayoutParams box=new LinearLayout.LayoutParams(-1,dp(height));box.topMargin=dp(10);page.addView(stage,box);
   try{stage.addView(MadarWidgets.views(this,kind,width,height).apply(getApplicationContext(),stage),new FrameLayout.LayoutParams(-1,-1));}
   catch(RuntimeException e){stage.addView(text("تعذر عرض المعاينة على هذا الجهاز",13,muted,false));}
   TextView add=text(canPin?"+ أضف إلى الشاشة الرئيسية":"طريقة الإضافة",14,Color.WHITE,true);add.setGravity(Gravity.CENTER);
   GradientDrawable pill=new GradientDrawable();pill.setColor(orange);pill.setCornerRadius(dp(22));add.setBackground(pill);add.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
   LinearLayout.LayoutParams button=new LinearLayout.LayoutParams(-1,dp(46));button.topMargin=dp(10);page.addView(add,button);
   final boolean pin=canPin;
   add.setOnClickListener(v->{
    boolean asked=false;
    if(pin)try{asked=manager.requestPinAppWidget(new ComponentName(this,MadarWidgets.provider(kind)),null,null);}catch(RuntimeException ignored){}
    if(!asked)Toast.makeText(this,"اضغط مطولًا على مكان فارغ في الشاشة الرئيسية، ثم «التطبيقات المصغّرة»، واختر مدار.",Toast.LENGTH_LONG).show();
   });
  }
  setContentView(scroll);
 }
}
