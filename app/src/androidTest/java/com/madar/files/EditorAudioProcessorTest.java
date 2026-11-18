package com.madar.files;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import org.junit.Test;
import java.nio.*;
import static org.junit.Assert.*;
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class EditorAudioProcessorTest {
 @Test public void stereoGainAndFadesKeepCompositionClockAcrossBuffers()throws Exception{EditorAudioProcessor processor=new EditorAudioProcessor(50,1,3);processor.configure(new AudioFormat(100,2,C.ENCODING_PCM_16BIT));processor.flush();for(int block=0;block<6;block++){ByteBuffer in=ByteBuffer.allocateDirect(200).order(ByteOrder.nativeOrder());for(int i=0;i<50;i++){in.putShort((short)10000);in.putShort((short)-10000);}in.flip();processor.queueInput(in);ByteBuffer out=processor.getOutput().order(ByteOrder.nativeOrder());for(int i=0;i<50;i++){double at=(block*50+i)/100.0;int expected=(int)Math.round(5000*Math.max(0,Math.min(1,Math.min(at,3-at))));assertEquals(expected,out.getShort(),1);assertEquals(-expected,out.getShort(),1);}}processor.reset();}
}
