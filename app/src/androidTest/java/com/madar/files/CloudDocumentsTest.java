package com.madar.files;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.*;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class CloudDocumentsTest {
 private Context c;private File root;
 @Before public void setup(){Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();root=new File(base.getCacheDir(),"cloud-doc-test-"+UUID.randomUUID());root.mkdirs();c=new ContextWrapper(base){@Override public File getFilesDir(){return root;}@Override public File getCacheDir(){return root;}};}
 @After public void clean(){remove(root);}private void remove(File f){File[] files=f.listFiles();if(files!=null)for(File child:files)remove(child);f.delete();}
 private JSONObject block(String type,String text,double y)throws Exception{return new JSONObject().put("type",type).put("text",text).put("x",.1).put("y",y).put("w",.8).put("h",.1).put("fontSize",12).put("rows",0).put("columns",0).put("columnWidths",new JSONArray()).put("cells",new JSONArray());}
 private JSONObject cell(int row,int col,int rs,int cs,String text)throws Exception{return new JSONObject().put("row",row).put("column",col).put("rowSpan",rs).put("columnSpan",cs).put("text",text);}
 private JSONObject fixture()throws Exception{return new JSONObject().put("blocks",new JSONArray().put(block("text","فاتورة مدار AB-123",.05)).put(block("table","",.3).put("h",.2).put("rows",2).put("columns",2).put("columnWidths",new JSONArray().put(.4).put(.6)).put("cells",new JSONArray().put(cell(0,0,1,2,"سعر المنتج")).put(cell(1,0,1,1,"125.00")).put(cell(1,1,1,1,"مدار")))));}
 @Test public void schemaUsesRealImageBytesAndGeminiAdapterSupportsTheSameRequest()throws Exception{Bitmap b=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);try{JSONObject request=CloudDocuments.request(b);assertNotNull(GeminiClient.content(request.getJSONArray("contents").getJSONObject(0)));assertNotNull(GeminiClient.schema(request.getJSONObject("generationConfig").getJSONObject("responseSchema")));assertEquals("image/jpeg",request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getJSONObject("inlineData").getString("mimeType"));}finally{b.recycle();}}
 @Test public void malformedBoundsAndOverlappingOrMissingCellsAreRejected()throws Exception{CloudDocuments.validate(fixture());JSONObject overlap=fixture();overlap.getJSONArray("blocks").getJSONObject(1).getJSONArray("cells").put(cell(0,1,1,1,"duplicate"));JSONObject missing=fixture();missing.getJSONArray("blocks").getJSONObject(1).getJSONArray("cells").remove(2);JSONObject bounds=fixture();bounds.getJSONArray("blocks").getJSONObject(0).put("x",.9);JSONObject screenshot=fixture();screenshot.getJSONArray("blocks").put(block("image","",0).put("x",0).put("w",1).put("h",1));for(JSONObject invalid:new JSONObject[]{overlap,missing,bounds,screenshot})try{CloudDocuments.validate(invalid);fail();}catch(IOException expected){}}
 @Test public void mergedTablesKeepPhysicalCellsAndPlainTextKeepsArabicReadingOrder()throws Exception{JSONObject page=CloudDocuments.validate(fixture());String text=CloudDocuments.plain(page);assertTrue(text.contains("مدار\t125.00"));String table=CloudDocuments.table(page.getJSONArray("blocks").getJSONObject(1),600,800);assertTrue(table.contains("w:gridSpan w:val=\"2\""));assertTrue(table.indexOf("125.00")<table.lastIndexOf("مدار"));assertFalse(table.contains("w:bidiVisual"));assertTrue(table.contains("w:trHeight w:val=\"1600\""));assertTrue(table.contains("<w:bidi/><w:jc w:val=\"left\"/>"));}
 @Test public void cachedVisionResultBuildsActualEditableDocxWithoutAnotherCloudRequest()throws Exception{
  File source=new File(root,"fixture.pdf");PdfDocument document=new PdfDocument();try{PdfDocument.Page page=document.startPage(new PdfDocument.PageInfo.Builder(600,800,1).create());page.getCanvas().drawColor(Color.WHITE);Paint p=new Paint();p.setColor(Color.BLACK);p.setTextSize(18);page.getCanvas().drawText("Madar Invoice AB-123",60,80,p);document.finishPage(page);try(OutputStream out=new FileOutputStream(source)){document.writeTo(out);}}finally{document.close();}
  try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd)){Bitmap bitmap=PdfProcessor.render(renderer,0,2600);try{GeminiCheckpoint.save(GeminiCheckpoint.file(c,CloudDocuments.request(bitmap,600,800)),CloudDocuments.validate(fixture()));}finally{bitmap.recycle();}}
  CloudDocuments.Result result=CloudDocuments.process(c,source,true,1,1,true,new AtomicBoolean(),message->{});assertNotNull(result.word);assertTrue(result.text.contains("125.00"));try(ZipFile zip=new ZipFile(result.word)){String xml;try(InputStream in=zip.getInputStream(zip.getEntry("word/document.xml"));ByteArrayOutputStream out=new ByteArrayOutputStream()){FilesUtil.copy(in,out);xml=out.toString("UTF-8");}assertTrue(xml.contains("<w:tbl>"));assertTrue(xml.contains("سعر المنتج"));assertTrue(xml.contains("125.00"));assertNull(zip.getEntry("word/media/page1_0.png"));}
  try(InputStream in=new FileInputStream(result.word);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){FilesUtil.copy(in,bytes);String encoded=android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP);android.util.Log.i("MADAR_CLOUD_DOCX","BEGIN "+encoded.length());for(int i=0;i<encoded.length();i+=2000)android.util.Log.i("MADAR_CLOUD_DOCX","PART "+i+" "+encoded.substring(i,Math.min(encoded.length(),i+2000)));android.util.Log.i("MADAR_CLOUD_DOCX","END");}
 }
 @Test public void cancelledReadNeverLeavesAPublishedWordFile()throws Exception{try{CloudDocuments.process(c,new File(root,"unused"),false,1,1,true,new AtomicBoolean(true),m->{});fail();}catch(java.util.concurrent.CancellationException expected){}assertTrue(RecentFiles.list(c).isEmpty());}
}
