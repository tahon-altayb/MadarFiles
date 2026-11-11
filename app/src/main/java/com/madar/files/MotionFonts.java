package com.madar.files;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.*;
import java.io.*;
import java.util.*;

/**
 * The owner's own font for motion text (for example a licensed Thmanyah file he downloaded). Kept in
 * the app's private storage only: never bundled in the app or the repository, and sent only to the
 * owner's own Colab session with his motion job. Headings and body text may use different files.
 */
final class MotionFonts {
 static final int MAX_BYTES=8*1024*1024;
 static File dir(Context c)throws IOException{File d=new File(c.getFilesDir(),"motion-fonts");if(!d.isDirectory()&&!d.mkdirs())throw new IOException("تعذر حفظ الخط");return d;}
 static String role(String value)throws IOException{if(!"heading".equals(value)&&!"body".equals(value))throw new IOException("نوع الخط غير صالح");return value;}

 /** OpenType with CFF outlines is «otf», TrueType outlines «ttf»; anything else is refused. */
 static String kind(byte[] head){
  if(head==null||head.length<4)return null;
  if(head[0]=='O'&&head[1]=='T'&&head[2]=='T'&&head[3]=='O')return "otf";
  if(head[0]==0&&head[1]==1&&head[2]==0&&head[3]==0)return "ttf";
  if(head[0]=='t'&&head[1]=='r'&&head[2]=='u'&&head[3]=='e')return "ttf";
  return null;
 }

 static File file(Context c,String role)throws IOException{role(role);for(String ext:new String[]{"otf","ttf"}){File f=new File(dir(c),role+"."+ext);if(f.isFile())return f;}return null;}
 static boolean installed(Context c){try{return file(c,"heading")!=null;}catch(IOException e){return false;}}

 static void save(Context c,String role,Uri uri)throws Exception{
  role(role);File temp=File.createTempFile("madar_font_",".part",c.getCacheDir());
  try{
   long total=0;byte[] head=new byte[4];int headRead=0;
   try(InputStream in=c.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temp)){
    if(in==null)throw new IOException("تعذر قراءة ملف الخط");byte[] b=new byte[65536];int n;
    while((n=in.read(b))!=-1){if(headRead<4){int k=Math.min(4-headRead,n);System.arraycopy(b,0,head,headRead,k);headRead+=k;}total+=n;if(total>MAX_BYTES)throw new IOException("ملف الخط أكبر من 8 ميغابايت");out.write(b,0,n);}
   }
   String ext=kind(head);if(ext==null)throw new IOException("اختر ملف خط بصيغة OTF أو TTF (مو WOFF2 ولا ZIP)");
   for(String old:new String[]{"otf","ttf"})new File(dir(c),role+"."+old).delete();
   File target=new File(dir(c),role+"."+ext);if(!temp.renameTo(target)){try(InputStream in=new FileInputStream(temp);OutputStream out=new FileOutputStream(target)){FilesUtil.copy(in,out);}}
   c.getSharedPreferences("motion-fonts",0).edit().putString(role+"Name",displayName(c,uri)).apply();
  }finally{temp.delete();}
 }
 static String displayName(Context c,Uri uri){try(Cursor cur=c.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cur!=null&&cur.moveToFirst()){String n=cur.getString(0);if(n!=null)return n.length()>80?n.substring(0,80):n;}}catch(Exception ignored){}return "خط";}

 static void clear(Context c)throws IOException{File[] all=dir(c).listFiles();if(all!=null)for(File f:all)f.delete();c.getSharedPreferences("motion-fonts",0).edit().clear().apply();}

 static JSONObject status(Context c)throws JSONException{
  android.content.SharedPreferences p=c.getSharedPreferences("motion-fonts",0);boolean heading=installed(c),body=false;try{body=file(c,"body")!=null;}catch(IOException ignored){}
  return new JSONObject().put("type","motionFonts").put("installed",heading).put("heading",heading?p.getString("headingName","خط"):"").put("body",body?p.getString("bodyName","خط"):"");
 }

 /** Upload names on Colab for this project's fonts; the body falls back to the heading file. */
 static Map<String,File> uploads(Context c,JSONObject project)throws IOException{
  Map<String,File> out=new LinkedHashMap<>();JSONObject fonts=project.optJSONObject("fonts");if(fonts==null)return out;
  File heading=file(c,"heading"),body=file(c,"body");if(heading==null)return out;if(body==null)body=heading;
  out.put(fonts.optString("headingFile"),heading);out.put(fonts.optString("bodyFile"),body);return out;
 }

 /** The setup choice: the owner's font when he chose it and it is installed, else the default. */
 static void apply(Context c,JSONObject project,boolean wanted)throws Exception{
  project.remove("fonts");if(!wanted)return;File heading=file(c,"heading");if(heading==null)return;File body=file(c,"body");if(body==null)body=heading;
  String h=heading.getName().endsWith(".otf")?"otf":"ttf",b=body.getName().endsWith(".otf")?"otf":"ttf";
  project.put("fonts",new JSONObject().put("heading","MadarHeading").put("body","MadarBody").put("headingFile","brand-heading."+h).put("bodyFile","brand-body."+b));
 }
 /** A project whose font was deleted later renders with the default font instead of failing. */
 static void check(Context c,JSONObject project){try{if(project.optJSONObject("fonts")!=null&&!installed(c))project.remove("fonts");}catch(Exception ignored){}}
 private MotionFonts(){}
}
