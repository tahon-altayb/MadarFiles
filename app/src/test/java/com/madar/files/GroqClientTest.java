package com.madar.files;

import static org.junit.Assert.*;
import org.junit.Test;

public class GroqClientTest {
 @Test public void acceptsOnlyGroqKeys(){
  assertTrue(GroqClient.validKey("gsk_"+"A1b2".repeat(10)));
  assertFalse(GroqClient.validKey("AIzaSy"+"x".repeat(33)));
  assertFalse(GroqClient.validKey("gsk_ with spaces 123456789012345"));
  assertFalse(GroqClient.validKey(null));
 }
 @Test public void usesAProductionTextModel(){
  assertEquals("openai/gpt-oss-120b",GroqClient.MODEL);
  assertTrue(GroqClient.ENDPOINT.startsWith("https://api.groq.com/"));
 }
}
