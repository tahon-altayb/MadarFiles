package com.madar.files;
import android.graphics.*;
import org.json.*;
import java.io.IOException;

/** Normalized mask strokes are replayed at export resolution using original pixels. */
final class CutoutBrush {
 static Bitmap apply(Bitmap source,Bitmap cut,JSONArray strokes,boolean transparent,int background)throws Exception{
  if(strokes==null||strokes.length()==0){if(transparent)return cut;Bitmap out=Bitmap.createBitmap(cut.getWidth(),cut.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(out);canvas.drawColor(background);canvas.drawBitmap(cut,0,0,null);cut.recycle();return out;}
  if(strokes.length()>200)throw new IOException("الحد 200 ضربة للفرشاة؛ صدّر ثم تابع");int w=cut.getWidth(),h=cut.getHeight(),count=0;Bitmap mask=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas mc=new Canvas(mask);mc.drawBitmap(cut,0,0,null);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setColor(Color.WHITE);
  try{for(int i=0;i<strokes.length();i++){JSONObject stroke=strokes.getJSONObject(i);String mode=stroke.getString("mode");if(!mode.equals("restore")&&!mode.equals("erase"))throw new IOException("فرشاة غير صالحة");double radius=stroke.getDouble("radius");if(!Double.isFinite(radius)||radius<.002||radius>.15)throw new IOException("حجم فرشاة غير صالح");JSONArray points=stroke.getJSONArray("points");if(points.length()<1||(count+=points.length())>15000)throw new IOException("نقاط الفرشاة كثيرة");p.setXfermode(new PorterDuffXfermode(mode.equals("erase")?PorterDuff.Mode.CLEAR:PorterDuff.Mode.SRC));p.setStrokeWidth((float)(2*radius*Math.min(w,h)));Path path=new Path();float firstX=0,firstY=0;for(int j=0;j<points.length();j++){JSONArray point=points.getJSONArray(j);double x=point.getDouble(0),y=point.getDouble(1);if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||x>1||y<0||y>1)throw new IOException("موضع فرشاة غير صالح");if(j==0){firstX=(float)x*w;firstY=(float)y*h;path.moveTo(firstX,firstY);}else path.lineTo((float)x*w,(float)y*h);}p.setStyle(Paint.Style.STROKE);mc.drawPath(path,p);p.setStyle(Paint.Style.FILL);mc.drawCircle(firstX,firstY,p.getStrokeWidth()/2,p);}
   Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas oc=new Canvas(out);oc.drawBitmap(source,0,0,null);Paint blend=new Paint(Paint.ANTI_ALIAS_FLAG);blend.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));oc.drawBitmap(mask,0,0,blend);cut.recycle();if(transparent)return out;Bitmap colored=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas cc=new Canvas(colored);cc.drawColor(background);cc.drawBitmap(out,0,0,null);out.recycle();return colored;
  }finally{mask.recycle();}
 }
}
