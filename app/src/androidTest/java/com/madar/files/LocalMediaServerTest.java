package com.madar.files;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class LocalMediaServerTest {
 @Test public void privateLoopbackSupportsRangesHeadAndRejectsWrongToken()throws Exception{
  File file=File.createTempFile("http-media-test-",".mp4",InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());byte[] data=new byte[256];for(int i=0;i<256;i++)data[i]=(byte)i;try(OutputStream out=new FileOutputStream(file)){out.write(data);}
  try(LocalMediaServer server=new LocalMediaServer((route,name)->route.equals("source")?new LocalMediaServer.Media(file,"video/mp4"):null)){
   assertEquals("127.0.0.1",new URL(server.base()).getHost());String source=server.base()+"/source";HttpURLConnection c=connection(source,null,"GET");try{assertEquals(200,c.getResponseCode());assertArrayEquals(data,c.getInputStream().readAllBytes());assertEquals("no-store",c.getHeaderField("Cache-Control"));}finally{c.disconnect();}
   c=connection(source,"bytes=5-9","GET");try{assertEquals(206,c.getResponseCode());assertEquals("bytes 5-9/256",c.getHeaderField("Content-Range"));assertArrayEquals(new byte[]{5,6,7,8,9},c.getInputStream().readAllBytes());}finally{c.disconnect();}
   c=connection(source,"bytes=-3","GET");try{assertEquals(206,c.getResponseCode());assertArrayEquals(new byte[]{(byte)253,(byte)254,(byte)255},c.getInputStream().readAllBytes());}finally{c.disconnect();}
   c=connection(source,"bytes=300-","GET");try{assertEquals(416,c.getResponseCode());assertEquals("bytes */256",c.getHeaderField("Content-Range"));}finally{c.disconnect();}
   c=connection(source,null,"HEAD");try{assertEquals(200,c.getResponseCode());assertEquals(256,c.getContentLength());assertEquals(0,c.getInputStream().readAllBytes().length);}finally{c.disconnect();}
   URL url=new URL(source);c=connection("http://127.0.0.1:"+url.getPort()+"/wrong/source",null,"GET");try{assertEquals(403,c.getResponseCode());}finally{c.disconnect();}
  }finally{file.delete();}
 }
 private HttpURLConnection connection(String url,String range,String method)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(5000);c.setReadTimeout(5000);c.setRequestMethod(method);if(range!=null)c.setRequestProperty("rAnGe",range);return c;}
}
