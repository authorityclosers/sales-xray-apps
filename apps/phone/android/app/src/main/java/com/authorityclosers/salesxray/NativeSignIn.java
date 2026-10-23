package com.authorityclosers.salesxray;

import android.os.CancellationSignal;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;
import com.facebook.CallbackManager;
import com.facebook.FacebookCallback;
import com.facebook.FacebookException;
import com.facebook.FacebookSdk;
import com.facebook.login.LoginManager;
import com.facebook.login.LoginResult;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;

/**
 * Sign-in inside the app (owner, 23 Oct 2026: never a jump to Chrome). Google is
 * Android's own account sheet (Credential Manager, "Sign in with Google"); Facebook
 * is the Facebook SDK, which switches to the Facebook app, or shows Facebook's own
 * sign-in sheet over the app when it isn't installed.
 *
 * The page gets the values from Sales X-Ray's server (POST /v1/auth/{provider}/native/start):
 * our web client ID and the sign-in's nonce for Google, the environment's Facebook app
 * ID and client token for Facebook. The token goes straight back to the page, which
 * posts it to the server once. Nothing is kept on the phone: the Facebook SDK is signed
 * out as soon as the token is handed over, and Facebook's app events and advertising
 * ID stay off (AndroidManifest.xml).
 */
final class NativeSignIn {

    interface Done {
        void token(String token);

        void failed(String code);
    }

    private static final String TAG = "SalesXraySignIn";
    private static final List<String> FACEBOOK_PERMISSIONS = Arrays.asList("public_profile", "email");

    private final AppCompatActivity activity;

    NativeSignIn(AppCompatActivity activity) {
        this.activity = activity;
    }

    /** What this build signs in with by itself; the page reads it from the bridge status. */
    static List<String> providers() {
        return Arrays.asList("google", "facebook");
    }

    void start(JSONObject args, Done done) {
        String provider = args.optString("provider");
        if ("google".equals(provider)) {
            String clientId = args.optString("clientId");
            String nonce = args.optString("nonce");
            if (!NativeSignInRules.googleRequest(clientId, nonce)) {
                done.failed("unavailable");
                return;
            }
            google(clientId, nonce, done);
        } else if ("facebook".equals(provider)) {
            String appId = args.optString("appId");
            String clientToken = args.optString("clientToken");
            if (!NativeSignInRules.facebookRequest(appId, clientToken)) {
                done.failed("unavailable");
                return;
            }
            facebook(appId, clientToken, done);
        } else {
            done.failed("unavailable");
        }
    }

    private void google(String clientId, String nonce, Done done) {
        GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(clientId).setNonce(nonce).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder().addCredentialOption(option).build();
        CredentialManager.create(activity).getCredentialAsync(
            activity,
            request,
            new CancellationSignal(),
            ContextCompat.getMainExecutor(activity),
            new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                @Override
                public void onResult(GetCredentialResponse response) {
                    Credential credential = response.getCredential();
                    if (
                        !(credential instanceof CustomCredential) ||
                        !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())
                    ) {
                        done.failed("failed");
                        return;
                    }
                    try {
                        done.token(GoogleIdTokenCredential.createFrom(credential.getData()).getIdToken());
                    } catch (Exception error) {
                        Log.w(TAG, "Google sign-in: unreadable credential");
                        done.failed("failed");
                    }
                }

                @Override
                public void onError(GetCredentialException error) {
                    // The type names what went wrong (a console setup error reads 28444); never the account.
                    Log.w(TAG, "Google sign-in: " + error.getClass().getSimpleName() + " " + error.getType());
                    done.failed(NativeSignInRules.googleError(error.getClass().getSimpleName()));
                }
            }
        );
    }

    private void facebook(String appId, String clientToken, Done done) {
        try {
            FacebookSdk.setApplicationId(appId);
            FacebookSdk.setClientToken(clientToken);
            FacebookSdk.setAutoLogAppEventsEnabled(false);
            FacebookSdk.setAdvertiserIDCollectionEnabled(false);
            if (!FacebookSdk.isInitialized()) {
                // The app ID differs per environment, so the SDK starts here, not from the manifest.
                FacebookSdk.sdkInitialize(activity.getApplicationContext());
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Facebook sign-in: the SDK did not start");
            done.failed("unavailable");
            return;
        }
        LoginManager login = LoginManager.getInstance();
        CallbackManager callbacks = CallbackManager.Factory.create();
        login.registerCallback(
            callbacks,
            new FacebookCallback<LoginResult>() {
                @Override
                public void onSuccess(LoginResult result) {
                    login.unregisterCallback(callbacks);
                    String token = result.getAccessToken().getToken();
                    // Sales X-Ray keeps no Facebook token on the phone.
                    login.logOut();
                    done.token(token);
                }

                @Override
                public void onCancel() {
                    login.unregisterCallback(callbacks);
                    done.failed("cancelled");
                }

                @Override
                public void onError(FacebookException error) {
                    login.unregisterCallback(callbacks);
                    Log.w(TAG, "Facebook sign-in: " + error.getClass().getSimpleName());
                    done.failed("failed");
                }
            }
        );
        try {
            login.logIn(activity, callbacks, FACEBOOK_PERMISSIONS);
        } catch (RuntimeException error) {
            login.unregisterCallback(callbacks);
            Log.w(TAG, "Facebook sign-in: could not open");
            done.failed("unavailable");
        }
    }
}
