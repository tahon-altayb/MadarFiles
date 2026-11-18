package com.madar.files;
import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import java.nio.ByteBuffer;
/** Gain/fades use the final composition clock, including reordered clips. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class EditorAudioProcessor extends BaseAudioProcessor {
 private final double gain,fade,duration;private long frames;
 EditorAudioProcessor(double gain,double fade,double duration){this(gain,fade,duration,1);}
 EditorAudioProcessor(double gain,double fade,double duration,double normalization){this.gain=FilesUtil.bounded(gain,0,200)/100*FilesUtil.bounded(normalization,.1,10);this.fade=FilesUtil.bounded(fade,0,10);this.duration=duration;}
 @Override protected AudioFormat onConfigure(AudioFormat format)throws UnhandledAudioFormatException{if(format.encoding!=C.ENCODING_PCM_16BIT&&format.encoding!=C.ENCODING_PCM_FLOAT)throw new UnhandledAudioFormatException(format);return format;}
 @Override protected void onFlush(){frames=0;}
 @Override public void queueInput(ByteBuffer input){ByteBuffer output=replaceOutputBuffer(input.remaining());int bytes=inputAudioFormat.encoding==C.ENCODING_PCM_FLOAT?4:2;int channels=inputAudioFormat.channelCount;while(input.remaining()>=bytes*channels){double at=frames/(double)inputAudioFormat.sampleRate,edge=fade<=0?1:Math.max(0,Math.min(1,Math.min(at/fade,(duration-at)/fade)));for(int channel=0;channel<channels;channel++){if(bytes==4)output.putFloat((float)Math.max(-1,Math.min(1,input.getFloat()*gain*edge)));else output.putShort((short)Math.round(Math.max(-32768,Math.min(32767,input.getShort()*gain*edge))));}frames++;}output.flip();}
}
