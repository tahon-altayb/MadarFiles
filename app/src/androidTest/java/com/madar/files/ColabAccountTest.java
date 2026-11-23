package com.madar.files;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.common.api.Scope;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONObject;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ColabAccountTest {
 @Test public void avcassRequestsOnlyL4AndNeverFallsBackToT4()throws Exception{
  JSONArray list=new JSONArray();
  for(String gpu:new String[]{"A100","T4","L4"})list.put(new JSONObject().put("eligible",true)
    .put("key",new JSONObject().put("variant","VARIANT_GPU").put("shape","SHAPE_STANDARD").put("accelerator",gpu)));
  JSONObject response=new JSONObject().put("runtimeSpecs",list);
  assertTrue(ColabRuntime.avcassCandidates(response,true).isEmpty());
  java.util.List<JSONObject> pro=ColabRuntime.avcassCandidates(response,false);
  assertEquals(1,pro.size());
  assertEquals("L4",pro.get(0).getString("accelerator"));
  list.getJSONObject(2).put("eligible",false);
  assertTrue(ColabRuntime.avcassCandidates(response,false).isEmpty());
 }
 @Test public void highRamL4IsRequestedWhenItIsTheOnlyEligibleL4()throws Exception{
  JSONArray list=new JSONArray()
    .put(new JSONObject().put("eligible",true).put("key",new JSONObject().put("variant","VARIANT_GPU").put("shape","SHAPE_STANDARD").put("accelerator","T4")))
    .put(new JSONObject().put("eligible",false).put("key",new JSONObject().put("variant","VARIANT_GPU").put("shape","SHAPE_STANDARD").put("accelerator","L4")))
    .put(new JSONObject().put("eligible",true).put("key",new JSONObject().put("variant","VARIANT_GPU").put("shape","SHAPE_HIGHMEM").put("accelerator","L4")));
  JSONObject response=new JSONObject().put("runtimeSpecs",list);
  java.util.List<JSONObject> pro=ColabRuntime.avcassCandidates(response,false);
  assertEquals(1,pro.size());
  assertEquals("SHAPE_HIGHMEM",pro.get(0).getString("shape"));
  java.util.List<JSONObject> all=ColabRuntime.candidates(response);
  assertEquals(2,all.size());
  assertEquals("L4",all.get(0).getString("accelerator"));
  assertEquals("SHAPE_STANDARD",all.get(1).getString("shape"));
 }
    @Test public void separationReportKeepsCommittedWindowsAcrossRuntimes() {
        android.content.Context context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
        ColabDiagnostics report = new ColabDiagnostics(context);
        report.musicSession();
        report.musicGpu("L4", 24);
        report.musicWindow(44100, 88200);
        report.record("MUSIC_WINDOW_RECOVERY");
        report.musicSession();
        report.musicGpu("T4", 16);
        report.musicWindow(44100, 88200);
        String saved = ColabDiagnostics.last(context);
        assertTrue(saved.contains("MUSIC_SESSION_1"));
        assertTrue(saved.contains("MUSIC_SESSION_2"));
        assertTrue(saved.contains("AV-CASS · L4 · 24 steps"));
        assertTrue(saved.contains("AV-CASS · T4 · 16 steps"));
        assertTrue(saved.contains("MUSIC_WINDOW_COMMITTED · 2 · 88200 / 88200 frames"));
        assertTrue(MusicRecovery.status("انتظار GPU · مضى 2 ثانية", 44100, 88200, 63000)
                .endsWith("انتظار GPU · المفصول 0:01 / 0:02 · مضى إجمالًا 1:03"));

        ColabDiagnostics next = new ColabDiagnostics(context);
        next.record("MUSIC_PROFILE_AVCASS");
        report.record("CLEANUP_OK");
        assertTrue(ColabDiagnostics.last(context).contains("MUSIC_PROFILE_AVCASS"));
        assertFalse(ColabDiagnostics.last(context).contains("CLEANUP_OK"));
    }

    @Test public void accountPickerBindsBothPermissionsWithoutIdentityInOAuthResponse() {
        Intent result = new Intent()
                .putExtra(AccountManager.KEY_ACCOUNT_NAME, "madar-test@example.com")
                .putExtra(AccountManager.KEY_ACCOUNT_TYPE, "com.google");
        Account account = ColabActivity.selectedGoogleAccount(result);
        assertNotNull(account);
        for (String scope : new String[]{ColabProbe.SCOPE, ColabCredentials.MODEL_STORAGE_SCOPE}) {
            AuthorizationRequest request = ColabCredentials.authorizationRequest(scope, account);
            assertEquals(account, request.getAccount());
            assertEquals(Collections.singletonList(new Scope(scope)), request.getRequestedScopes());
            assertTrue(request.getOptOutIncludingGrantedScopes());
        }
    }

    @Test public void keepsBroaderGoogleTokensOutOfTheWorker() {
        String scope = ColabCredentials.MODEL_STORAGE_SCOPE;
        assertTrue(ColabCredentials.storageScopes(Collections.singletonList(scope)));
        assertFalse(ColabCredentials.storageScopes(java.util.Arrays.asList(scope, ColabProbe.SCOPE)));
        assertFalse(ColabCredentials.storageScopes(Collections.emptyList()));
        assertTrue(ColabCredentials.validStorageToken("ya29.valid-token+/="));
        assertFalse(ColabCredentials.validStorageToken("ya29.token with spaces"));
    }

    @Test public void rejectsIncompleteOrNonGoogleAccountResults() {
        assertNull(ColabActivity.selectedGoogleAccount(new Intent()));
        assertNull(ColabActivity.selectedGoogleAccount(new Intent()
                .putExtra(AccountManager.KEY_ACCOUNT_NAME, "madar-test@example.com")
                .putExtra(AccountManager.KEY_ACCOUNT_TYPE, "com.example")));
    }
}
