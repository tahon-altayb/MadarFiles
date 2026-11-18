package com.madar.files;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class MusicWireTest {
 @Test public void compressedStereoRoundTripIsByteExact()throws Exception{byte[] pcm=new byte[MusicWire.MAX_BYTES];for(int i=0;i<pcm.length/4;i++){short n=(short)(Math.sin(i*.017)*18000);pcm[i*4]=pcm[i*4+2]=(byte)n;pcm[i*4+1]=pcm[i*4+3]=(byte)(n>>8);}MusicWire.Packed wire=MusicWire.encode(pcm);assertTrue(wire.compressed);assertTrue(wire.data.length<pcm.length);assertArrayEquals(pcm,MusicWire.decode(wire.data,wire.metadata,pcm.length));}
 @Test public void incompressibleAudioRetainsTheRawProtocol()throws Exception{byte[] pcm=new byte[65536];new Random(715).nextBytes(pcm);MusicWire.Packed wire=MusicWire.encode(pcm);assertFalse(wire.compressed);assertSame(pcm,wire.data);assertArrayEquals(pcm,MusicWire.decode(wire.data,wire.metadata,pcm.length));}
 @Test public void truncatedTrailingAndExpandingStreamsCannotBeAccepted()throws Exception{byte[] pcm=new byte[1000000];MusicWire.Packed wire=MusicWire.encode(pcm);assertTrue(wire.compressed);byte[][] invalid={Arrays.copyOf(wire.data,wire.data.length-2),Arrays.copyOf(wire.data,wire.data.length+3),wire.data};for(int i=0;i<invalid.length;i++){int expected=i==2?8192:pcm.length;JSONObject metadata=new JSONObject(wire.metadata.toString()).put("bytes",expected).put("wire_bytes",invalid[i].length).put("wire_sha256",ColabMusic.sha(invalid[i]));try{MusicWire.decode(invalid[i],metadata,expected);fail();}catch(IOException error){assertTrue(error.getMessage().contains("MUSIC_WIRE_"));}}}
 @Test public void corruptedSamplesNeverPassTheirOriginalHash()throws Exception{byte[] pcm=new byte[4096];MusicWire.Packed wire=MusicWire.encode(pcm);JSONObject bad=new JSONObject(wire.metadata.toString()).put("sha256","0".repeat(64));try{MusicWire.decode(wire.data,bad,pcm.length);fail();}catch(IOException error){assertTrue(error.getMessage().contains("MUSIC_OUTPUT_HASH"));}}
}
