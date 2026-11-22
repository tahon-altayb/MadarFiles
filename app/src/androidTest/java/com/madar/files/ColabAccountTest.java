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
 @Test public void avcassRequestsT4ForFreeAndPrefersEligibleL4Otherwise()throws Exception{
  JSONArray list=new JSONArray();
  for(String gpu:new String[]{"A100","T4","L4"})list.put(new JSONObject().put("eligible",true)
    .put("key",new JSONObject().put("variant","VARIANT_GPU").put("shape","SHAPE_STANDARD").put("accelerator",gpu)));
  JSONObject response=new JSONObject().put("runtimeSpecs",list);
  java.util.List<JSONObject> free=ColabRuntime.avcassCandidates(response,true),other=ColabRuntime.avcassCandidates(response,false);
  assertEquals(1,free.size());
  assertEquals("T4",free.get(0).getString("accelerator"));
  assertEquals(2,other.size());
  assertEquals("L4",other.get(0).getString("accelerator"));
  assertEquals("T4",other.get(1).getString("accelerator"));
  list.getJSONObject(2).put("eligible",false);
  assertEquals("T4",ColabRuntime.avcassCandidates(response,false).get(0).getString("accelerator"));
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
