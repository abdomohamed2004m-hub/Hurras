package com.hurras.island;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Transaction;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

public class MainActivity extends Activity {

    private static final int MAX_ID_ATTEMPTS = 30;

    private WebView web;
    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private CredentialManager credMgr;
    private final SecureRandom rnd = new SecureRandom();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = new Executor() {
        @Override
        public void execute(Runnable r) {
            main.post(r);
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.preferredRefreshRate = 120f;
        getWindow().setAttributes(lp);

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        credMgr = CredentialManager.create(this);

        web = new WebView(this);
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setMediaPlaybackRequiresUserGesture(false);
        st.setAllowContentAccess(false);
        web.setBackgroundColor(0xff0b1020);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // keep the game local: never navigate away from the bundled page
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.addJavascriptInterface(new Bridge(), "HurrasNative");
        setContentView(web);
        hide();
        web.loadUrl("file:///android_asset/index.html");
    }

    private void hide() {
        getWindow().getDecorView().setSystemUiVisibility(4103);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) hide();
    }

    // ---------------------------------------------------------------- bridge

    /** Methods the game page can call as HurrasNative.xxx() */
    private class Bridge {
        @JavascriptInterface
        public void init() {
            runOnUiThread(new Runnable() {
                public void run() {
                    initAuth();
                }
            });
        }

        @JavascriptInterface
        public void signIn() {
            runOnUiThread(new Runnable() {
                public void run() {
                    startSignIn();
                }
            });
        }

        @JavascriptInterface
        public void createProfile(final String name) {
            runOnUiThread(new Runnable() {
                public void run() {
                    createProfileFlow(name);
                }
            });
        }

        @JavascriptInterface
        public void signOut() {
            runOnUiThread(new Runnable() {
                public void run() {
                    auth.signOut();
                    emit("out", null, null);
                }
            });
        }
    }

    /** Sends a state update to the page: window.__onAuth({...}) */
    private void emitJson(JSONObject o) {
        final String js = "window.__onAuth&&window.__onAuth("
                + o.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")";
        runOnUiThread(new Runnable() {
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    private void emit(String s, String msg, String again) {
        try {
            JSONObject o = new JSONObject();
            o.put("s", s);
            if (msg != null) o.put("m", msg);
            if (again != null) o.put("again", again);
            emitJson(o);
        } catch (JSONException ignored) {
        }
    }

    private void emitOk(String uid, String name, String id) {
        try {
            JSONObject o = new JSONObject();
            o.put("s", "ok");
            o.put("uid", uid);
            o.put("name", name);
            o.put("id", id);
            emitJson(o);
        } catch (JSONException ignored) {
        }
    }

    private void emitNeedName(String suggested) {
        try {
            JSONObject o = new JSONObject();
            o.put("s", "need_name");
            o.put("name", suggested == null ? "" : suggested);
            emitJson(o);
        } catch (JSONException ignored) {
        }
    }

    // ------------------------------------------------------------ auth flow

    /** App start: restore the saved Firebase session (if any) and load the profile. */
    private void initAuth() {
        FirebaseUser u = auth.getCurrentUser();
        if (u == null) {
            emit("out", null, null);
            return;
        }
        emit("busy", "جاري تحميل حسابك...", null);
        loadProfile(u);
    }

    /** Google account picker via Credential Manager, then Firebase sign-in. */
    private void startSignIn() {
        emit("busy", "جاري فتح حساب جوجل...", null);
        GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(
                getString(R.string.default_web_client_id)).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build();
        credMgr.getCredentialAsync(this, request, new CancellationSignal(), mainExecutor,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        onGoogleCredential(result.getCredential());
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        if (e instanceof GetCredentialCancellationException) {
                            emit("out", "تم إلغاء تسجيل الدخول", null);
                        } else if (e instanceof NoCredentialException) {
                            emit("out", "لا يوجد حساب جوجل على هذا الجهاز. أضف حساب جوجل من إعدادات الهاتف ثم حاول مرة أخرى", null);
                        } else {
                            String d = e.getMessage();
                            emit("err", "تعذّر تسجيل الدخول" + (d == null ? "" : " (" + d + ")"), "signIn");
                        }
                    }
                });
    }

    private void onGoogleCredential(Credential credential) {
        if (credential instanceof CustomCredential
                && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
            try {
                GoogleIdTokenCredential g = GoogleIdTokenCredential.createFrom(((CustomCredential) credential).getData());
                firebaseSignIn(g.getIdToken());
                return;
            } catch (GoogleIdTokenParsingException ignored) {
            }
        }
        emit("err", "نوع بيانات الدخول غير مدعوم", "signIn");
    }

    private void firebaseSignIn(String idToken) {
        emit("busy", "جاري التحقق من الحساب...", null);
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                .addOnCompleteListener(new OnCompleteListener<AuthResult>() {
                    @Override
                    public void onComplete(Task<AuthResult> t) {
                        FirebaseUser u = auth.getCurrentUser();
                        if (t.isSuccessful() && u != null) {
                            loadProfile(u);
                        } else {
                            Exception e = t.getException();
                            String d = e == null ? null : e.getMessage();
                            emit("err", "فشل التحقق من الحساب" + (d == null ? "" : " (" + d + ")"), "signIn");
                        }
                    }
                });
    }

    /** Reads users/{uid}: if it exists the player goes straight in, otherwise asks for a name. */
    private void loadProfile(final FirebaseUser u) {
        db.collection("users").document(u.getUid()).get()
                .addOnCompleteListener(new OnCompleteListener<DocumentSnapshot>() {
                    @Override
                    public void onComplete(Task<DocumentSnapshot> t) {
                        if (!t.isSuccessful()) {
                            emit("err", "تعذّر تحميل حسابك. تأكد من اتصال الإنترنت وحاول مرة أخرى", "init");
                            return;
                        }
                        DocumentSnapshot d = t.getResult();
                        if (d != null && d.exists()) {
                            String nm = d.getString("name");
                            String id = d.getString("playerId");
                            if (nm != null && id != null) {
                                emitOk(u.getUid(), nm, id);
                                return;
                            }
                        }
                        emitNeedName(u.getDisplayName());
                    }
                });
    }

    // ------------------------------------------------------- profile + 6-digit ID

    private static String cleanName(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("\\p{C}", "").replaceAll("\\s+", " ").trim();
    }

    private void createProfileFlow(String rawName) {
        String name = cleanName(rawName);
        int len = name.codePointCount(0, name.length());
        if (len < 2 || len > 20) {
            emit("err", "الاسم لازم يكون من ٢ إلى ٢٠ حرفًا", "init");
            return;
        }
        emit("busy", "جاري إنشاء حسابك...", null);
        tryCreate(name, 0);
    }

    /**
     * Picks a random 6-digit ID and claims it inside a Firestore transaction:
     * playerIds/{id} and users/{uid} are written together, and only if the ID is free.
     * Two players can never end up with the same ID, even if they register at the same instant.
     */
    private void tryCreate(final String name, final int attempt) {
        final FirebaseUser u = auth.getCurrentUser();
        if (u == null) {
            emit("out", null, null);
            return;
        }
        if (attempt >= MAX_ID_ATTEMPTS) {
            emit("err", "تعذّر توليد رقم الحساب. حاول مرة أخرى", "init");
            return;
        }
        final String uid = u.getUid();
        final String id = String.valueOf(100000 + rnd.nextInt(900000));
        final DocumentReference userRef = db.collection("users").document(uid);
        final DocumentReference idRef = db.collection("playerIds").document(id);

        db.runTransaction(new Transaction.Function<String[]>() {
            @Override
            public String[] apply(Transaction tr) throws FirebaseFirestoreException {
                DocumentSnapshot existing = tr.get(userRef);
                if (existing.exists()) {
                    return new String[]{"have", existing.getString("name"), existing.getString("playerId")};
                }
                DocumentSnapshot taken = tr.get(idRef);
                if (taken.exists()) {
                    return new String[]{"taken", null, null};
                }
                Map<String, Object> idDoc = new HashMap<>();
                idDoc.put("uid", uid);
                Map<String, Object> userDoc = new HashMap<>();
                userDoc.put("name", name);
                userDoc.put("playerId", id);
                userDoc.put("createdAt", FieldValue.serverTimestamp());
                tr.set(idRef, idDoc);
                tr.set(userRef, userDoc);
                return new String[]{"new", name, id};
            }
        }).addOnSuccessListener(new OnSuccessListener<String[]>() {
            @Override
            public void onSuccess(String[] r) {
                if ("taken".equals(r[0])) {
                    tryCreate(name, attempt + 1);
                } else {
                    emitOk(uid, r[1], r[2]);
                }
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(Exception e) {
                String d = e.getMessage();
                emit("err", "تعذّر إنشاء الحساب. تأكد من الإنترنت وحاول مرة أخرى" + (d == null ? "" : " (" + d + ")"), "init");
            }
        });
    }
}
