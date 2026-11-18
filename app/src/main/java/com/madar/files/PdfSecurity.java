package com.madar.files;
import android.content.Context;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import java.io.*;
import org.json.JSONObject;
final class PdfSecurity {
 static File apply(Context c,File source,JSONObject options)throws Exception {
  String password=options.optString("pdfPassword","");
  if(password.isEmpty()||password.length()>127)throw new IOException("أدخل كلمة مرور من 1 إلى 127 حرفًا");
  PDFBoxResourceLoader.init(c);File out=FilesUtil.output(c,"pdf");
  try(PDDocument doc=PDDocument.load(source)){
   StandardProtectionPolicy policy=new StandardProtectionPolicy(password,password,new AccessPermission());policy.setEncryptionKeyLength(256);doc.protect(policy);
   doc.save(out);return out;
  }catch(Exception|OutOfMemoryError e){out.delete();throw e;}
 }
}
