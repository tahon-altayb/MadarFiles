package com.madar.files;
import android.graphics.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CutoutBrushTest {
 @Test public void restoreUsesOriginalPixelsAndEraseClearsAlpha()throws Exception{
  Bitmap source=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);source.eraseColor(Color.rgb(182,73,41));Bitmap cut=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);
  JSONObject restore=new JSONObject().put("mode","restore").put("radius",.1).put("points",new JSONArray().put(new JSONArray().put(.5).put(.5)));Bitmap out=CutoutBrush.apply(source,cut,new JSONArray().put(restore),true,Color.WHITE);assertEquals(source.getPixel(50,50),out.getPixel(50,50));assertEquals(0,Color.alpha(out.getPixel(0,0)));
  JSONObject erase=new JSONObject(restore.toString()).put("mode","erase");Bitmap finalCut=CutoutBrush.apply(source,out,new JSONArray().put(erase),true,Color.WHITE);assertEquals(0,Color.alpha(finalCut.getPixel(50,50)));finalCut.recycle();source.recycle();
 }
}
