package com.madar.files;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Madar's home-screen widgets: the download row, one widget for each section (video, images, PDF, audio, QR) with its
 * most used tools, and a hub with every section. A widget cannot hold a text field (Android's rule for every app), so
 * the download row takes the copied link from the clipboard when the app opens in front; every tap opens Madar on the
 * right screen with ACTION and two extras, nothing else.
 */
public final class MadarWidgets {
 private MadarWidgets(){}
 static final String ACTION="com.madar.files.WIDGET";
 /** Section -> its glyph, name, hint and up to four shortcuts {tool key, label}. The tool keys are the web catalog's. */
 static final String[] KINDS={"links","video","image","pdf","audio","qr"};
 static String glyph(String kind){switch(kind){case "video":return "▷";case "image":return "▧";case "pdf":return "▤";case "audio":return "♫";case "qr":return "▦";default:return "↓";}}
 static String name(String kind){switch(kind){case "video":return "الفيديو";case "image":return "الصور";case "pdf":return "PDF";case "audio":return "الصوت";case "qr":return "QR";default:return "التحميل";}}
 static String hint(String kind){switch(kind){case "video":return "اختر مقطعًا وابدأ";case "image":return "اختر صورة وابدأ";case "pdf":return "اختر ملف PDF وابدأ";case "audio":return "اختر ملفًا صوتيًا وابدأ";case "qr":return "حوّل رابطًا أو ملفًا إلى رمز";default:return "انسخ الرابط ثم اضغط لصق";}}
 static String[][] tools(String kind){
  switch(kind){
   case "video":return new String[][]{{"compress","ضغط"},{"gif","GIF"},{"editor","المحرر"},{"audio","استخراج صوت"}};
   case "image":return new String[][]{{"compress","ضغط"},{"crop","قصّ"},{"removebg","عزل شخص"},{"ocr","سحب النص"}};
   case "pdf":return new String[][]{{"word","إلى Word"},{"compressPdf","ضغط"},{"convert","إلى صورة"},{"text","سحب النص"}};
   case "audio":return new String[][]{{"subtitles","تفريغ"},{"trim","قص"},{"music","إزالة الموسيقى"},{"convert","تحويل"}};
   case "qr":return new String[][]{{"linkQr","رابط"},{"textQr","نص"},{"imageQr","صورة"},{"pdfQr","PDF"}};
   default:return new String[0][];
  }
 }
 static boolean known(String kind){return Arrays.asList(KINDS).contains(kind);}
 private static final int[] CHIPS={R.id.chip1,R.id.chip2,R.id.chip3,R.id.chip4};
 private static final int[] HUB_TOOLS={R.id.tool1,R.id.tool2,R.id.tool3,R.id.tool4,R.id.tool5,R.id.tool6},HUB_DOTS={R.id.dot1,R.id.dot2,R.id.dot3,R.id.dot4,R.id.dot5,R.id.dot6},HUB_NAMES={R.id.name1,R.id.name2,R.id.name3,R.id.name4,R.id.name5,R.id.name6};

 /** The tap: Madar in front (the screen he left stays under it), told which section and what to do there. */
 static PendingIntent tap(Context c,String kind,String act){
  Intent open=new Intent(c,MainActivity.class).setAction(ACTION).setData(Uri.parse("madar://widget/"+kind+"/"+act)).putExtra("tool",kind).putExtra("act",act)
   .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);
  return PendingIntent.getActivity(c,(kind+"/"+act).hashCode(),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
 }
 /** What a widget tap asks of the web app: {type:"widget",tool,act}; null when the intent is not a widget's or names something unknown. */
 static JSONObject request(Intent intent)throws JSONException{
  if(intent==null||!ACTION.equals(intent.getAction()))return null;
  String kind=intent.getStringExtra("tool"),act=intent.getStringExtra("act");
  if(kind==null||!known(kind)||act==null||!act.matches("[A-Za-z]{1,24}"))return null;
  return new JSONObject().put("type","widget").put("tool",kind).put("act",act);
 }
 private static final Pattern LINK=Pattern.compile("https?://[^\\s<>\"']{4,1990}",Pattern.CASE_INSENSITIVE);
 /** The first web link in a copied text, or "" (trailing punctuation that is not part of a link is dropped). */
 static String link(CharSequence copied){
  if(copied==null)return "";Matcher m=LINK.matcher(copied);if(!m.find())return "";
  String url=m.group();while(url.length()>12&&".,;:!؟،)]}»".indexOf(url.charAt(url.length()-1))>=0)url=url.substring(0,url.length()-1);return url;
 }
 /** The link on the clipboard; readable only while the app's window has focus (Android 10 and later). */
 static String clipboardLink(Context c){
  try{ClipboardManager board=(ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE);ClipData clip=board==null?null:board.getPrimaryClip();
   if(clip==null)return "";for(int i=0;i<clip.getItemCount();i++){String found=link(clip.getItemAt(i).coerceToText(c));if(!found.isEmpty())return found;}return "";
  }catch(RuntimeException e){return "";}
 }

 /** The widget of a section drawn for a size in dp: a narrow one keeps the orbit and the button, a tall one gains a line. */
 static RemoteViews views(Context c,String kind,int width,int height){
  if(kind.equals("hub"))return hub(c,width,height);
  if(kind.equals("links")){
   RemoteViews v=new RemoteViews(c.getPackageName(),R.layout.widget_download);
   v.setViewVisibility(R.id.texts,width>=230?View.VISIBLE:View.GONE);v.setViewVisibility(R.id.hint,width>=230&&height>=56?View.VISIBLE:View.GONE);v.setViewVisibility(R.id.platforms,width>=230&&height>=100?View.VISIBLE:View.GONE);
   v.setTextViewText(R.id.go,width>=300?"لصق وتحميل ↓":width>=170?"لصق ↓":"↓");
   v.setOnClickPendingIntent(R.id.root,tap(c,"links","open"));v.setOnClickPendingIntent(R.id.go,tap(c,"links","paste"));return v;
  }
  boolean card=height>=100;String[][] tools=tools(kind);
  RemoteViews v=new RemoteViews(c.getPackageName(),card?R.layout.widget_tool_card:R.layout.widget_tool_row);
  v.setTextViewText(R.id.glyph,glyph(kind));v.setTextViewText(R.id.title,name(kind));
  // As many shortcuts as the width reads comfortably: about 58dp each (beside the orbit and the name on the row).
  int room=card?width-20:width-120,show=Math.max(0,Math.min(tools.length,room/58));
  if(card){v.setTextViewText(R.id.hint,hint(kind));v.setOnClickPendingIntent(R.id.go,tap(c,kind,"pick"));v.setViewVisibility(R.id.go,width>=200&&!kind.equals("qr")?View.VISIBLE:View.GONE);}
  else v.setViewVisibility(R.id.title,width>=190||show==0?View.VISIBLE:View.GONE);
  for(int i=0;i<CHIPS.length;i++){
   if(i<show){v.setViewVisibility(CHIPS[i],View.VISIBLE);v.setTextViewText(CHIPS[i],tools[i][1]);v.setOnClickPendingIntent(CHIPS[i],tap(c,kind,tools[i][0]));}
   else v.setViewVisibility(CHIPS[i],View.GONE);
  }
  v.setViewVisibility(R.id.chips,show>0?View.VISIBLE:View.GONE);
  v.setOnClickPendingIntent(R.id.root,tap(c,kind,"open"));return v;
 }
 private static RemoteViews hub(Context c,int width,int height){
  RemoteViews v=new RemoteViews(c.getPackageName(),R.layout.widget_hub);
  v.setViewVisibility(R.id.head,height>=120?View.VISIBLE:View.GONE);v.setViewVisibility(R.id.go,width>=250?View.VISIBLE:View.GONE);
  int show=Math.max(3,Math.min(KINDS.length,width/54));
  for(int i=0;i<KINDS.length;i++){
   v.setViewVisibility(HUB_TOOLS[i],i<show?View.VISIBLE:View.GONE);v.setTextViewText(HUB_DOTS[i],glyph(KINDS[i]));v.setTextViewText(HUB_NAMES[i],name(KINDS[i]));
   v.setViewVisibility(HUB_NAMES[i],height>=84?View.VISIBLE:View.GONE);v.setOnClickPendingIntent(HUB_TOOLS[i],tap(c,KINDS[i],"open"));
  }
  v.setOnClickPendingIntent(R.id.go,tap(c,"links","paste"));v.setOnClickPendingIntent(R.id.root,tap(c,"links","open"));return v;
 }
 /** Every widget's provider class, by section ("hub" for the one with every section). */
 static Class<? extends Base> provider(String kind){
  switch(kind){case "video":return Video.class;case "image":return Images.class;case "pdf":return Pdf.class;case "audio":return Audio.class;case "qr":return Qr.class;case "hub":return Hub.class;default:return Download.class;}
 }
 /** Long-press shortcuts on Madar's icon: the same taps as the widgets. */
 static void shortcuts(Context c){
  try{ShortcutManager manager=c.getSystemService(ShortcutManager.class);if(manager==null)return;
   String[][] wanted={{"links","paste","تحميل من رابط"},{"video","open","أدوات الفيديو"},{"image","open","أدوات الصور"},{"qr","linkQr","رابط إلى QR"}};List<ShortcutInfo> list=new ArrayList<>();
   for(String[] w:wanted)list.add(new ShortcutInfo.Builder(c,"madar-"+w[0]).setShortLabel(w[2]).setIcon(Icon.createWithResource(c,R.mipmap.ic_launcher))
    .setIntent(new Intent(c,MainActivity.class).setAction(ACTION).setData(Uri.parse("madar://widget/"+w[0]+"/"+w[1])).putExtra("tool",w[0]).putExtra("act",w[1]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP)).build());
   manager.setDynamicShortcuts(list);
  }catch(RuntimeException ignored){}
 }

 /** One provider per widget; each redraws its instances at the size the launcher gives them. */
 public abstract static class Base extends AppWidgetProvider {
  abstract String kind();
  @Override public void onUpdate(Context c,AppWidgetManager manager,int[] ids){for(int id:ids)draw(c,manager,id,manager.getAppWidgetOptions(id));}
  @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager manager,int id,Bundle options){draw(c,manager,id,options);}
  private void draw(Context c,AppWidgetManager manager,int id,Bundle options){
   int width=options==null?0:options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),height=options==null?0:options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
   // A launcher that reports no size gets the default four-cell row.
   if(width<=0)width=280;if(height<=0)height=kind().equals("hub")?130:60;
   manager.updateAppWidget(id,views(c,kind(),width,height));
  }
 }
 public static final class Download extends Base{@Override String kind(){return "links";}}
 public static final class Video extends Base{@Override String kind(){return "video";}}
 public static final class Images extends Base{@Override String kind(){return "image";}}
 public static final class Pdf extends Base{@Override String kind(){return "pdf";}}
 public static final class Audio extends Base{@Override String kind(){return "audio";}}
 public static final class Qr extends Base{@Override String kind(){return "qr";}}
 public static final class Hub extends Base{@Override String kind(){return "hub";}}
 /** Redraws every placed widget (after an update of the app changes how they look). */
 static void refresh(Context c){
  try{AppWidgetManager manager=AppWidgetManager.getInstance(c);if(manager==null)return;
   for(String kind:new String[]{"links","video","image","pdf","audio","qr","hub"}){Class<? extends Base> type=provider(kind);int[] ids=manager.getAppWidgetIds(new ComponentName(c,type));
    for(int id:ids){Bundle o=manager.getAppWidgetOptions(id);int w=o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),h=o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);manager.updateAppWidget(id,views(c,kind,w<=0?280:w,h<=0?(kind.equals("hub")?130:60):h));}}
  }catch(RuntimeException ignored){}
 }
}
