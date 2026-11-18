package com.madar.files;
import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class RecentFilesTest {
 private Context c;private File root;
 @Before public void setup(){Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();root=new File(base.getCacheDir(),"recent-test-"+UUID.randomUUID());root.mkdirs();c=new ContextWrapper(base){@Override public File getFilesDir(){return root;}};}
 @After public void clean(){remove(root);}private void remove(File f){File[] files=f.listFiles();if(files!=null)for(File child:files)remove(child);f.delete();}
 private File file(String name)throws Exception{File f=new File(FilesUtil.directory(c),name);Files.write(f.toPath(),name.getBytes(java.nio.charset.StandardCharsets.UTF_8));return f;}
 @Test public void deleteMovesOnlySelectedExportsAndUndoRestoresExactBytes()throws Exception{
  File a=file("Madar-a.txt"),b=file("Madar-b.txt"),original=new File(root,"original.txt");Files.write(original.toPath(),new byte[]{5,9,2});byte[] expected=Files.readAllBytes(a.toPath());
  assertEquals(1,RecentFiles.remove(c,RecentFiles.select(c,a.getName(),"latest")));assertFalse(a.exists());assertTrue(b.exists());assertTrue(original.exists());assertEquals(1,RecentFiles.trashCount(c));assertEquals(1,RecentFiles.restore(c));assertArrayEquals(expected,Files.readAllBytes(a.toPath()));assertArrayEquals(new byte[]{5,9,2},Files.readAllBytes(original.toPath()));
 }
 @Test public void allUsesSnapshotAndNeverIncludesPartialOrImportedOriginal()throws Exception{file("a.txt");file("b.txt");File partial=file("pending.mp4.partial");JSONArray names=RecentFiles.select(c,"","all");assertEquals(2,names.length());RecentFiles.remove(c,names);assertTrue(partial.exists());assertTrue(RecentFiles.list(c).isEmpty());assertEquals(2,RecentFiles.restore(c));}
 @Test public void traversalDuplicateAndUnknownScopeAreRejectedBeforeMovingAnything()throws Exception{File a=file("a.txt");for(JSONArray names:new JSONArray[]{new JSONArray().put("../original.txt"),new JSONArray().put(a.getName()).put(a.getName())})try{RecentFiles.remove(c,names);fail();}catch(IOException expected){}assertTrue(a.exists());try{RecentFiles.select(c,"","device");fail();}catch(IOException expected){}assertEquals(0,RecentFiles.trashCount(c));}
 @Test public void restoreNeverOverwritesAReplacementExport()throws Exception{File a=file("a.txt");RecentFiles.remove(c,new JSONArray().put(a.getName()));Files.write(a.toPath(),new byte[]{1});assertEquals(0,RecentFiles.restore(c));assertArrayEquals(new byte[]{1},Files.readAllBytes(a.toPath()));assertEquals(1,RecentFiles.trashCount(c));}
}
