package com.madar.files;
import android.content.Context;
import android.graphics.Bitmap;
import android.text.Html;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
/** Commons search is opt-in, constrained to publicly reusable PD/CC0 candidates. */
final class MotionImages {
 static String clean(String s){return Html.fromHtml(s,Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+"," ").trim();}
 static String field(JSONObject o,String key){JSONObject v=o.optJSONObject(key);return v==null?"":clean(v.optString("value"));}
 static byte[] get(String address,int max,String expectedHost,AtomicBoolean cancel)throws Exception{
  URL url=new URL(address);if(!url.getProtocol().equals("https")||!url.getHost().equals(expectedHost)||url.getUserInfo()!=null||(url.getPort()!=-1&&url.getPort()!=443))throw new IOException("رابط صورة غير صالح");HttpURLConnection con=(HttpURLConnection)url.openConnection();con.setInstanceFollowRedirects(false);con.setConnectTimeout(15000);con.setReadTimeout(20000);con.setRequestProperty("User-Agent","MadarFiles/1.9.18 (motion storyboard; https://github.com/tahon-altayb/MadarFiles)");
  try{MusicSeparator.check(cancel);if(con.getResponseCode()!=200)throw new IOException("تعذر جلب الصور من ويكيميديا");try(InputStream in=con.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){MusicSeparator.check(cancel);if(out.size()+n>max)throw new IOException("الصورة أكبر من الحد");out.write(b,0,n);}return out.toByteArray();}}finally{con.disconnect();}
 }
 static JSONArray search(String query,AtomicBoolean cancel)throws Exception{
  if(query.trim().isEmpty()||query.length()>180)throw new IOException("اكتب اسم المكان أو الحدث أو نوع السفينة والحقبة");String q=URLEncoder.encode(query+" filetype:bitmap",StandardCharsets.UTF_8.name());String url="https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=16&gsrsearch="+q+"&prop=imageinfo&iiprop=url%7Cextmetadata%7Cmime&iiurlwidth=1280";JSONObject data=new JSONObject(new String(get(url,1024*1024,"commons.wikimedia.org",cancel),StandardCharsets.UTF_8));JSONObject pages=data.optJSONObject("query")==null?null:data.getJSONObject("query").optJSONObject("pages");JSONArray out=new JSONArray();if(pages==null)return out;
  for(java.util.Iterator<String> it=pages.keys();it.hasNext();){JSONObject page=pages.getJSONObject(it.next());JSONArray infos=page.optJSONArray("imageinfo");if(infos==null||infos.length()==0)continue;JSONObject info=infos.getJSONObject(0),meta=info.optJSONObject("extmetadata");if(meta==null)continue;String license=field(meta,"LicenseShortName"),type=info.optString("mime");if(!(license.toLowerCase(java.util.Locale.ROOT).contains("public domain")||license.equalsIgnoreCase("CC0"))||!(type.equals("image/jpeg")||type.equals("image/png")))continue;String image=info.optString("thumburl",info.optString("url")),source=info.optString("descriptionurl");URL parsed=new URL(image);if(!parsed.getProtocol().equals("https")||!parsed.getHost().equals("upload.wikimedia.org")||!source.startsWith("https://commons.wikimedia.org/wiki/"))continue;String artist=field(meta,"Artist");if(artist.length()>250)artist=artist.substring(0,250);String credit=artist+" · "+license+" · "+source;if(credit.length()>700)continue;out.put(new JSONObject().put("title",page.optString("title")).put("url",image).put("source",source).put("credit",credit).put("license",license));if(out.length()==8)break;
  }return out;
 }
 static String store(Context c,String id,File input)throws Exception{Bitmap bitmap=FilesUtil.image(input,1600);String name="asset_"+MotionProject.id()+".jpg";File output=MotionProject.asset(c,id,name);try(OutputStream out=new FileOutputStream(output)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,90,out))throw new IOException("تعذر حفظ الصورة");}catch(Exception e){output.delete();throw e;}finally{bitmap.recycle();}return name;}
 static String download(Context c,String id,JSONObject image,AtomicBoolean cancel)throws Exception{byte[] data=get(image.getString("url"),8*1024*1024,"upload.wikimedia.org",cancel);File temp=File.createTempFile("motion-image-",".data",c.getCacheDir());try{try(OutputStream out=new FileOutputStream(temp)){out.write(data);}return store(c,id,temp);}finally{temp.delete();}}
}
