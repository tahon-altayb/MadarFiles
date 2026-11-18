package com.madar.files;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class DriveQrRecoveryTest {
 private Context context;private File diagnostic;private byte[] previous;
 @Before public void prepare()throws Exception{context=InstrumentationRegistry.getInstrumentation().getTargetContext();diagnostic=new File(context.getFilesDir(),"drive-diagnostic.json");previous=diagnostic.exists()?Files.readAllBytes(diagnostic.toPath()):null;}
 @After public void restore()throws Exception{if(previous==null)diagnostic.delete();else Files.write(diagnostic.toPath(),previous);}
 @Test public void nativeDiagnosticPersistsOnlySafeCodesAndPinsCorrectProject()throws Exception{
  String body="{\"error\":{\"message\":\"Bearer PRIVATE_TOKEN private@example.com\",\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\"SERVICE_DISABLED\",\"metadata\":{\"service\":\"drive.googleapis.com\",\"consumer\":\"projects/123456789012\",\"activationUrl\":\"https://evil.invalid/PRIVATE_TOKEN\"}}]}}";
  DriveFailure.remember(context,DriveFailure.parse(403,body,"start"));JSONObject saved=DriveFailure.last(context);assertEquals("SERVICE_DISABLED",saved.getString("reason"));assertTrue(saved.getBoolean("setup"));String bytes=new String(Files.readAllBytes(diagnostic.toPath()),java.nio.charset.StandardCharsets.UTF_8);assertFalse(bytes.contains("PRIVATE_TOKEN"));assertFalse(bytes.contains("private@example.com"));assertFalse(bytes.contains("evil.invalid"));assertEquals("https://console.cloud.google.com/apis/library/drive.googleapis.com?project=123456789012",DriveFailure.setupUrl(context));
 }
 @Test public void nativeDiagnosticCanBeClearedAfterRecovery()throws Exception{DriveFailure.remember(context,new DriveFailure(403,"storageQuotaExceeded","upload",""));assertFalse(DriveFailure.last(context).getBoolean("setup"));DriveFailure.clear(context);assertEquals(0,DriveFailure.last(context).length());assertFalse(diagnostic.exists());}
}
