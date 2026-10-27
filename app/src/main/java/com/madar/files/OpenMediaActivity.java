package com.madar.files;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/** Public Android "Open with" entry point. Never accepts internal file-path extras. */
public class OpenMediaActivity extends Activity {
 @Override public void onCreate(Bundle state){super.onCreate(state);try{
  Intent incoming=getIntent();Uri uri=incoming.getData();
  if(!Intent.ACTION_VIEW.equals(incoming.getAction())||uri==null||!("content".equals(uri.getScheme())||"file".equals(uri.getScheme())))throw new IllegalArgumentException("اختر مقطعًا من مدير الملفات أو المعرض");
  Intent player=new Intent(this,PlayerActivity.class).setDataAndType(uri,incoming.getType()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
  player.setClipData(ClipData.newRawUri("مقطع للمشاهدة",uri));startActivity(player);
 }catch(Exception error){Toast.makeText(this,"تعذر فتح المقطع: "+error.getMessage(),Toast.LENGTH_LONG).show();}finally{finish();}}
}
