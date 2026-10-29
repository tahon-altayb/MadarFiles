package com.madar.files;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.media3.ui.PlayerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class PlayerLayoutTest {
 @Test public void controlsOverlayVideoAndHideWithoutReducingFrame()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File f=File.createTempFile("player-layout-",".mp4",c.getCacheDir());try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("sample.mp4");OutputStream out=new FileOutputStream(f)){FilesUtil.copy(in,out);}PlayerActivity a=(PlayerActivity)InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(c,PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("path",f.getPath()).putExtra("title","اختبار الشاشة الكاملة"));
  try{java.lang.reflect.Field stageField=PlayerActivity.class.getDeclaredField("stage"),toolbarField=PlayerActivity.class.getDeclaredField("toolbar"),viewField=PlayerActivity.class.getDeclaredField("view");stageField.setAccessible(true);toolbarField.setAccessible(true);viewField.setAccessible(true);FrameLayout stage=(FrameLayout)stageField.get(a);LinearLayout toolbar=(LinearLayout)toolbarField.get(a);PlayerView player=(PlayerView)viewField.get(a);java.lang.reflect.Field engineField=PlayerActivity.class.getDeclaredField("player");engineField.setAccessible(true);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{androidx.media3.exoplayer.ExoPlayer engine=(androidx.media3.exoplayer.ExoPlayer)engineField.get(a);engine.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ONE);engine.seekTo(0);engine.play();}catch(Exception e){throw new RuntimeException(e);}});long hiddenDeadline=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<hiddenDeadline){final boolean[] hidden={false};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->hidden[0]=((View)toolbar.getParent()).getVisibility()==View.GONE);if(hidden[0])break;Thread.sleep(100);}InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{assertSame("Controls must overlay the stage",stage,((View)toolbar.getParent()).getParent());assertEquals("Tools belong at the top, independently of the seekbar",Gravity.TOP,((FrameLayout.LayoutParams)((View)toolbar.getParent()).getLayoutParams()).gravity);for(int n=0;n<toolbar.getChildCount();n++){View button=toolbar.getChildAt(n);assertTrue("Touch targets must remain readable",button.getHeight()>=44*c.getResources().getDisplayMetrics().density);assertTrue(((LinearLayout.LayoutParams)button.getLayoutParams()).leftMargin>0);}assertEquals(View.GONE,((View)toolbar.getParent()).getVisibility());assertTrue(stage.getHeight()>0);player.showController();Button mode=null;for(int i=0;i<toolbar.getChildCount();i++)if(((Button)toolbar.getChildAt(i)).getText().toString().equals("حجم العرض"))mode=(Button)toolbar.getChildAt(i);assertNotNull(mode);int first=player.getResizeMode();mode.performClick();assertNotEquals(first,player.getResizeMode());mode.performClick();assertEquals(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,player.getResizeMode());});}finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(a::finish);f.delete();}
 }
}
