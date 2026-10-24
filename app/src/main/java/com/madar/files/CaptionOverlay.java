package com.madar.files;
import android.graphics.*;
import android.text.*;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlaySettings;
import org.json.*;
import java.util.*;
/** Timed captions rendered by Android text shaping, including connected Arabic. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class CaptionOverlay extends BitmapOverlay {
 private static final class Cue{double start,end;String text;Cue(JSONObject o)throws JSONException{start=o.getDouble("start");end=o.getDouble("end");text=o.getString("text");}}
 private final List<Cue> cues=new ArrayList<>();private float fontPercent=3.5f;private String position="bottom";private boolean backdrop=true;private int color=Color.rgb(250,245,232);private int maxHeight=400;private int width=972;private float font=38;private Bitmap bitmap;private String last;
 CaptionOverlay(JSONArray input)throws Exception{this(input,new JSONObject());}
 CaptionOverlay(JSONArray input,JSONObject style)throws Exception{
  fontPercent=(float)FilesUtil.bounded(style.optDouble("captionSize",3.5),2,6);position=style.optString("captionPosition","bottom");if(!Arrays.asList("top","center","bottom").contains(position))throw new IllegalArgumentException("موضع ترجمة غير صالح");backdrop=style.optBoolean("captionBackground",true);String ink=style.optString("captionColor","ivory");if(ink.equals("white"))color=Color.WHITE;else if(ink.equals("gold"))color=Color.rgb(230,210,160);
  if(input.length()<1||input.length()>10000)throw new IllegalArgumentException("عدد أسطر الترجمة غير صالح");
  for(int i=0;i<input.length();i++){Cue cue=new Cue(input.getJSONObject(i));if(!Double.isFinite(cue.start)||!Double.isFinite(cue.end)||cue.start<0||cue.end<=cue.start||cue.text.length()>1000)throw new IllegalArgumentException("تحقق من النص وتوقيت الترجمة");cues.add(cue);}cues.sort(Comparator.comparingDouble(c->c.start));
 }
 @Override public void configure(Size size){maxHeight=Math.max(32,(int)(size.getHeight()*.5));width=Math.max(64,(int)(size.getWidth()*.9));font=Math.max(14,size.getHeight()*fontPercent/100);last=null;}
 @Override public OverlaySettings getOverlaySettings(long time){return new OverlaySettings.Builder().setBackgroundFrameAnchor(0,position.equals("top")?.86f:position.equals("center")?0:-.86f).setOverlayFrameAnchor(0,position.equals("top")?1:position.equals("center")?0:-1).build();}
 @Override public Bitmap getBitmap(long time){
  double seconds=time/1000000.0;StringBuilder text=new StringBuilder();for(Cue cue:cues){if(cue.start>seconds)break;if(seconds<cue.end){if(text.length()>0)text.append('\n');text.append(cue.text);}}
  String current=text.toString();if(current.equals(last)&&bitmap!=null)return bitmap;
  if(bitmap!=null)bitmap.recycle();last=current;
  if(current.isEmpty()){bitmap=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);return bitmap;}
  TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG);paint.setColor(color);paint.setShadowLayer(font*.06f,0,1,Color.BLACK);paint.setTextSize(font);paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));int padding=Math.max(4,(int)(font*.35));
  StaticLayout layout=fullLayout(current,paint,width-padding*2,maxHeight-padding*2);
  bitmap=Bitmap.createBitmap(width,layout.getHeight()+padding*2,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);Paint background=new Paint(Paint.ANTI_ALIAS_FLAG);background.setColor(0xb0000000);if(backdrop)canvas.drawRoundRect(0,0,bitmap.getWidth(),bitmap.getHeight(),padding,padding,background);canvas.translate(padding,padding);layout.draw(canvas);return bitmap;
 }
 static StaticLayout fullLayout(String text,TextPaint paint,int width,int maxHeight){
  StaticLayout layout;
  do{layout=StaticLayout.Builder.obtain(text,0,text.length(),paint,width).setAlignment(Layout.Alignment.ALIGN_CENTER).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).setIncludePad(true).build();
   if(layout.getHeight()<=maxHeight)return layout;
   if(paint.getTextSize()<=10)throw new IllegalArgumentException("نص الترجمة أطول من مساحة العرض؛ قسّم السطر في محرر الترجمة قبل التصدير");
   paint.setTextSize(Math.max(10,paint.getTextSize()*.9f));
  }while(true);
 }
 @Override public void release()throws androidx.media3.common.VideoFrameProcessingException{super.release();if(bitmap!=null){bitmap.recycle();bitmap=null;}}
}
