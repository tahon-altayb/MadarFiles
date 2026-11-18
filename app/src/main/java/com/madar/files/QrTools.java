package com.madar.files;
import android.content.Context;
import android.graphics.Bitmap;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.json.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
final class QrTools {
 static String payload(JSONObject options)throws Exception{
  String type=options.optString("qrType","link"),value=options.optString("qrValue").trim();
  if(!Arrays.asList("link","text","image","video","pdf").contains(type))throw new IOException("نوع QR غير مدعوم");
  if(value.isEmpty())throw new IOException(type.equals("text")?"اكتب النص أولًا":"أضف رابط المشاركة أولًا");
  if(value.getBytes(StandardCharsets.UTF_8).length>1800)throw new IOException("المحتوى طويل؛ استخدم رابطًا أقصر أو نصًا أقصر");
  if(!type.equals("text")){URI uri;try{uri=new URI(value);}catch(Exception e){throw new IOException("الرابط غير صالح");}if(!Arrays.asList("https","http").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getHost().equalsIgnoreCase("localhost")||uri.getHost().equals("127.0.0.1")||uri.getHost().equals("0.0.0.0"))throw new IOException("أدخل رابط مشاركة HTTP أو HTTPS يستطيع المستلم فتحه؛ المسار المحلي لا يصلح");}
  return value;
 }
 static File create(Context c,JSONObject options)throws Exception{
  String value=payload(options);int size=options.optInt("qrSize",1024);if(size!=512&&size!=1024&&size!=2048)throw new IOException("دقة QR غير صالحة");
  Map<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);hints.put(EncodeHintType.CHARACTER_SET,"UTF-8");hints.put(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.M);hints.put(EncodeHintType.MARGIN,4);
  BitMatrix matrix=new MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,size,size,hints);Bitmap image=Bitmap.createBitmap(matrix.getWidth(),matrix.getHeight(),Bitmap.Config.ARGB_8888);int[] pixels=new int[matrix.getWidth()*matrix.getHeight()];for(int y=0;y<matrix.getHeight();y++)for(int x=0;x<matrix.getWidth();x++)pixels[y*matrix.getWidth()+x]=matrix.get(x,y)?0xff102d2b:0xffffffff;image.setPixels(pixels,0,matrix.getWidth(),0,0,matrix.getWidth(),matrix.getHeight());File result=FilesUtil.output(c,"png");boolean saved=false;try(OutputStream out=new FileOutputStream(result)){if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("تعذر حفظ QR");saved=true;return result;}finally{image.recycle();if(!saved)result.delete();}
 }
}
