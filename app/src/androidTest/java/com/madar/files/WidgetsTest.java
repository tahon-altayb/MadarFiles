package com.madar.files;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/**
 * The home-screen widgets on a real Android: every widget inflates the way a launcher inflates it (RemoteViews) at every
 * size it can be dragged to, shows what that size promises, and is saved as a picture for review; the taps carry the
 * right request; the gallery and the download screen open.
 */
public class WidgetsTest {
 private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
 private final Context context=instrumentation.getTargetContext();
 private static final int[][] SIZES={{110,40},{180,56},{250,60},{320,64},{320,120},{400,150}};
 private File out(String name){
  String dir=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir");
  File base=dir!=null?new File(dir):new File(context.getExternalFilesDir(null),"widget-shots");base.mkdirs();return new File(base,name);
 }
 private void save(Bitmap picture,String name)throws Exception{try(FileOutputStream stream=new FileOutputStream(out(name))){picture.compress(Bitmap.CompressFormat.PNG,100,stream);}}
 private static void texts(View view,List<String> found){
  if(view.getVisibility()!=View.VISIBLE)return;
  if(view instanceof TextView&&view.getWidth()>0&&view.getHeight()>0)found.add(((TextView)view).getText().toString());
  if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)texts(((ViewGroup)view).getChildAt(i),found);
 }
 @Test public void everyWidgetDrawsAtEverySize()throws Exception{
  float density=context.getResources().getDisplayMetrics().density;
  // The widgets are drawn inside a window, as a launcher holds them: a single-line Arabic TextView places its text on the
  // window's pre-draw pass, so drawn loose they came out with every Arabic word missing.
  Activity host=instrumentation.startActivitySync(new Intent(context,WidgetGalleryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  try{
   for(String kind:new String[]{"links","hub","video","image","pdf","audio","qr"}){
    final List<View> drawn=new ArrayList<>();
    instrumentation.runOnMainSync(()->{
     android.widget.ScrollView scroll=new android.widget.ScrollView(context);android.widget.LinearLayout column=new android.widget.LinearLayout(context);column.setOrientation(android.widget.LinearLayout.VERTICAL);scroll.addView(column);
     for(int[] size:SIZES){FrameLayout cell=new FrameLayout(context);View view=MadarWidgets.views(context,kind,size[0],size[1]).apply(context,cell);cell.addView(view,new FrameLayout.LayoutParams(-1,-1));column.addView(cell,new android.widget.LinearLayout.LayoutParams(Math.round(size[0]*density),Math.round(size[1]*density)));drawn.add(view);}
     host.setContentView(scroll);
    });
    instrumentation.waitForIdleSync();Thread.sleep(900);
    final Bitmap[] sheet=new Bitmap[1];final List<List<String>> shown=new ArrayList<>();
    instrumentation.runOnMainSync(()->{
     int gap=Math.round(12*density),total=gap;for(int[] size:SIZES)total+=Math.round(size[1]*density)+gap;
     sheet[0]=Bitmap.createBitmap(Math.round(424*density),total,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(sheet[0]);canvas.drawColor(Color.parseColor("#F8F4ED"));int y=gap;
     for(View view:drawn){canvas.save();canvas.translate(gap,y);view.draw(canvas);canvas.restore();y+=view.getHeight()+gap;List<String> found=new ArrayList<>();texts(view,found);shown.add(found);}
    });
    save(sheet[0],"widget-"+kind+".png");
    for(int i=0;i<SIZES.length;i++)assertEquals(kind+" is laid out at the size asked",Math.round(SIZES[i][0]*density),drawn.get(i).getWidth());
    // The widest size shows the section's name or call and every shortcut; the narrowest still shows something to press.
    List<String> wide=shown.get(SIZES.length-1),narrow=shown.get(0);
    assertFalse(kind+" shows nothing at its narrowest",narrow.isEmpty());
    if(kind.equals("links")){assertTrue(wide.toString(),wide.contains("لصق وتحميل ↓")&&wide.contains("حمّل من رابط"));assertTrue(narrow.toString(),narrow.contains("↓"));}
    else if(kind.equals("hub")){for(String section:MadarWidgets.KINDS)assertTrue(section+" missing from the hub: "+wide,wide.contains(MadarWidgets.name(section)));}
    else{assertTrue(wide.toString(),wide.contains(MadarWidgets.name(kind)));for(String[] tool:MadarWidgets.tools(kind))assertTrue(tool[1]+" missing: "+wide,wide.contains(tool[1]));}
   }
  }finally{instrumentation.runOnMainSync(host::finish);}
 }
 @Test public void tapsCarryTheSectionAndTheAction()throws Exception{
  for(String kind:MadarWidgets.KINDS){assertNotNull(MadarWidgets.tap(context,kind,"open"));for(String[] tool:MadarWidgets.tools(kind))assertNotNull(MadarWidgets.tap(context,kind,tool[0]));}
  JSONObject request=MadarWidgets.request(new Intent(MadarWidgets.ACTION).putExtra("tool","video").putExtra("act","gif"));
  assertEquals("widget",request.getString("type"));assertEquals("video",request.getString("tool"));assertEquals("gif",request.getString("act"));
  assertNull(MadarWidgets.request(new Intent(MadarWidgets.ACTION).putExtra("tool","elsewhere").putExtra("act","open")));
  assertNull(MadarWidgets.request(new Intent(MadarWidgets.ACTION).putExtra("tool","video").putExtra("act","rm -rf")));
  assertNull(MadarWidgets.request(new Intent(Intent.ACTION_MAIN).putExtra("tool","video").putExtra("act","gif")));
  MadarWidgets.shortcuts(context);MadarWidgets.refresh(context);
 }
 /** The screen as it is, after closing any dialog the emulator's own launcher put over it. */
 private void screenshot(String name){
  try{instrumentation.getUiAutomation().executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS").close();Thread.sleep(1200);
   Bitmap shot=instrumentation.getUiAutomation().takeScreenshot();if(shot!=null)save(shot,name);}catch(Exception ignored){}
 }
 @Test public void theGalleryOpensWithEveryWidget()throws Exception{
  Activity gallery=instrumentation.startActivitySync(new Intent(context,WidgetGalleryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  try{instrumentation.waitForIdleSync();Thread.sleep(1500);
   final List<String> found=new ArrayList<>();instrumentation.runOnMainSync(()->texts(gallery.getWindow().getDecorView(),found));
   for(String kind:WidgetGalleryActivity.ORDER)assertTrue(kind+" missing from the gallery",found.contains(WidgetGalleryActivity.title(kind)));
   assertTrue("the download row is drawn inside the gallery: "+found,found.contains("↓")||found.contains("لصق ↓")||found.contains("لصق وتحميل ↓"));
   final Bitmap[] page=new Bitmap[1];instrumentation.runOnMainSync(()->{View content=((ViewGroup)((ViewGroup)gallery.findViewById(android.R.id.content)).getChildAt(0)).getChildAt(0);page[0]=Bitmap.createBitmap(content.getWidth(),Math.min(content.getHeight(),6000),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(page[0]);canvas.drawColor(Color.parseColor("#F8F4ED"));content.draw(canvas);});
   save(page[0],"gallery.png");
  }finally{instrumentation.runOnMainSync(gallery::finish);}
 }
 @Test public void aWidgetTapOpensMadar()throws Exception{
  Activity main=instrumentation.startActivitySync(new Intent(context,MainActivity.class).setAction(MadarWidgets.ACTION).putExtra("tool","links").putExtra("act","open").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  try{assertNotNull(main);
   // The page takes the request once it is ready: the intent is then back to a plain launch, so a rotation does not repeat the tap.
   for(int i=0;i<90&&!Intent.ACTION_MAIN.equals(main.getIntent().getAction());i++)Thread.sleep(500);
   assertFalse("Madar closed after the tap",main.isFinishing());assertEquals(Intent.ACTION_MAIN,main.getIntent().getAction());Thread.sleep(2500);
   screenshot("tap-download.png");
  }finally{instrumentation.runOnMainSync(main::finish);}
 }
}
