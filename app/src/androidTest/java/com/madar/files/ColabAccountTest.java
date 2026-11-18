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

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ColabAccountTest {
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

    @Test public void rejectsIncompleteOrNonGoogleAccountResults() {
        assertNull(ColabActivity.selectedGoogleAccount(new Intent()));
        assertNull(ColabActivity.selectedGoogleAccount(new Intent()
                .putExtra(AccountManager.KEY_ACCOUNT_NAME, "madar-test@example.com")
                .putExtra(AccountManager.KEY_ACCOUNT_TYPE, "com.example")));
    }
}
