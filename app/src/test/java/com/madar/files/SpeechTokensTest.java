package com.madar.files;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;
import static org.junit.Assert.*;
public class SpeechTokensTest {
 @Test public void splitUtf8PiecesSurvivePerPieceTextNormalization()throws Exception{
  String expected="戦う者とは珍しい男だ。 العربية 📝";byte[] bytes=expected.getBytes(StandardCharsets.UTF_8);StringBuilder joined=new StringBuilder();
  for(byte value:bytes){String encoded=Base64.getEncoder().encodeToString(new byte[]{value});String transport=new String(Base64.getDecoder().decode(SpeechTokens.safePiece(encoded)),StandardCharsets.US_ASCII);assertTrue(transport.matches("[0-9a-f]{2}"));joined.append(transport);}
  ByteArrayOutputStream decoded=new ByteArrayOutputStream();for(int i=0;i<joined.length();i+=2)decoded.write(Integer.parseInt(joined.substring(i,i+2),16));
  assertEquals(expected,decoded.toString("UTF-8"));
 }
 @Test public void emptyEndTokenRemainsEmpty(){assertEquals("=",SpeechTokens.safePiece("="));}
}
