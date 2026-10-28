package com.madar.files;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/** Public Android "Open with" entry point. Never accepts internal file-path extras. */
public class OpenMediaActivity extends Activity {
 @Override public void onCreate(Bundle state){super.onCreate(state);try{
  Intent incoming=getIntent();Uri uri=ExternalMedia.uri(incoming);
  if(!Intent.ACTION_VIEW.equals(incoming.getAction())||uri==null||!ExternalMedia.supported(uri))throw new IllegalArgumentException("اختر ملفًا أو رابط فيديو صالحًا");
  uri=MediaResolver.secureProvider(uri);
  if("http".equalsIgnoreCase(uri.getScheme())&&!android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(uri.getHost()))throw new IllegalArgumentException("هذا المصدر يستخدم HTTP غير مشفّر؛ استخدم رابط HTTPS أو ملفًا محفوظًا");
  Intent player=new Intent(this,PlayerActivity.class).setDataAndType(uri,ExternalMedia.mime(incoming)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
  player.putExtra("android.media.intent.extra.HTTP_HEADERS",ExternalMedia.bundle(ExternalMedia.headers(incoming)));
  player.setClipData(ClipData.newRawUri("مقطع للمشاهدة",uri));startActivity(player);
 }catch(Exception error){Toast.makeText(this,"تعذر فتح المقطع: "+error.getMessage(),Toast.LENGTH_LONG).show();}finally{finish();}}
}
