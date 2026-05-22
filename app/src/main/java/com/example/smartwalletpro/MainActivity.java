package com.example.smartwalletpro;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import android.util.Base64;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import androidx.credentials.CredentialManager;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.client.http.ByteArrayContent;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.StringWriter;
import java.io.PrintWriter;
import android.accounts.Account;
import android.accounts.AccountManager;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;

public class MainActivity extends AppCompatActivity {

    private WebView myWebView;
    public ValueCallback<Uri[]> uploadMessage;
    public static final int REQUEST_SELECT_FILE = 100;
    public static final int REQUEST_CAMERA_RECEIPT = 101;
    public static final int REQUEST_AUTHORIZATION = 1001;
    private static final String PREFS_NAME = "SMS_BUFFER";
    private static final String PREFS_BUDGET = "BUDGET_ALERT";
    private static final String CLIENT_ID = "979964659677-jfu9lvaq01vo15r5lfmd8aqltcq9uup0.apps.googleusercontent.com";
    private Uri receiptPhotoUri;
    private CredentialManager credentialManager;
    private String lastBackupJson;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        myWebView = new WebView(this);
        setContentView(myWebView);

        WebSettings webSettings = myWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);

        myWebView.addJavascriptInterface(new WebAppInterface(this), "Android");

        myWebView.setWebChromeClient(new WebChromeClient() {
            public boolean onShowFileChooser(WebView mWebView, ValueCallback<Uri[]> filePathCallback,
                    WebChromeClient.FileChooserParams fileChooserParams) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                    uploadMessage = null;
                }
                uploadMessage = filePathCallback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json"); // For Restore
                startActivityForResult(Intent.createChooser(intent, "Select Backup File"), REQUEST_SELECT_FILE);
                return true;
            }
        });

        myWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.loadUrl("javascript:if(typeof onNativeWalletChanged==='function')onNativeWalletChanged()");
            }
        });

        myWebView.loadUrl("file:///android_asset/index.html");

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.RECEIVE_SMS }, 1);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.POST_NOTIFICATIONS }, 2);
        }

        credentialManager = CredentialManager.create(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (myWebView != null)
            myWebView.loadUrl("javascript:if(typeof onNativeWalletChanged==='function')onNativeWalletChanged()");
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == REQUEST_SELECT_FILE) {
            if (uploadMessage == null)
                return;
            uploadMessage.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, intent));
            uploadMessage = null;
        } else if (requestCode == REQUEST_CAMERA_RECEIPT && resultCode == RESULT_OK && receiptPhotoUri != null
                && myWebView != null) {
            runReceiptOcr(receiptPhotoUri);
            receiptPhotoUri = null;
        } else if (requestCode == REQUEST_AUTHORIZATION && resultCode == RESULT_OK) {
            if (lastBackupJson != null) {
                new WebAppInterface(this).driveBackup(lastBackupJson);
            } else {
                new WebAppInterface(this).driveRestore();
            }
        }
    }

    private void runReceiptOcr(Uri imageUri) {
        try {
            Bitmap bitmap = BitmapFactory.decodeStream(getContentResolver().openInputStream(imageUri));
            if (bitmap == null) {
                sendReceiptToJs("{}");
                return;
            }
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            recognizer.process(image).addOnSuccessListener(visionText -> {
                String fullText = visionText.getText();
                double amount = parseAmountFromReceiptText(fullText);
                String json = "{\"amount\":" + (amount > 0 ? (long) amount : "null") + ",\"text\":\""
                        + escapeJs(fullText) + "\"}";
                sendReceiptToJs(json);
            }).addOnFailureListener(e -> sendReceiptToJs("{\"error\":\"OCR failed\"}"));
        } catch (Exception e) {
            sendReceiptToJs("{\"error\":\"" + escapeJs(e.getMessage()) + "\"}");
        }
    }

    private static double parseAmountFromReceiptText(String text) {
        if (text == null)
            return 0;
        java.util.regex.Pattern p = java.util.regex.Pattern
                .compile("(\\d{1,3}(?:,\\d{3})*)\\s*(?:원|KRW|MMK)?|(?:원|KRW|MMK)\\s*(\\d{1,3}(?:,\\d{3})*)");
        java.util.regex.Matcher m = p.matcher(text);
        double maxAmount = 0;
        while (m.find()) {
            String g = m.group(1) != null ? m.group(1) : m.group(2);
            if (g != null) {
                double val = Double.parseDouble(g.replace(",", ""));
                if (val > maxAmount && val < 1e9)
                    maxAmount = val;
            }
        }
        return maxAmount;
    }

    private static String escapeJs(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private void sendReceiptToJs(String json) {
        if (myWebView != null) {
            String encoded = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            myWebView.post(
                    () -> myWebView.evaluateJavascript(buildUtf8CallbackScript("onReceiptScanned", encoded), null));
        }
    }

    private static String buildUtf8CallbackScript(String func, String encodedParams) {
        String safeEncoded = encodedParams.replace("'", "\\'");
        return "(function(){"
                + "var b=atob('" + safeEncoded + "');"
                + "var s='';"
                + "if(window.TextDecoder){"
                + "var a=new Uint8Array(b.length);"
                + "for(var i=0;i<b.length;i++)a[i]=b.charCodeAt(i);"
                + "s=new TextDecoder('utf-8').decode(a);"
                + "}else{"
                + "try{s=decodeURIComponent(escape(b));}catch(e){s=b;}"
                + "}"
                + "if(typeof " + func + "==='function')" + func + "(s);"
                + "})();";
    }

    public class WebAppInterface {
        Activity mContext;

        WebAppInterface(Activity c) {
            mContext = c;
        }

        @JavascriptInterface
        public String getPendingSMS() {
            return "[]";
        }

        @JavascriptInterface
        public void clearPendingSMS() {
            SharedPreferences prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove("PENDING_SMS").apply();
            WalletStorage.markImportedSmsSeen(mContext);
        }

        @JavascriptInterface
        public String getWalletData() {
            return WalletStorage.getWalletJson(mContext);
        }

        @JavascriptInterface
        public void saveWalletData(String jsonData) {
            WalletStorage.saveWalletJson(mContext, jsonData);
        }

        @JavascriptInterface
        public int getImportedSmsCount() {
            return WalletStorage.getUnseenImportedSmsCount(mContext);
        }

        @JavascriptInterface
        public void markImportedSmsSeen() {
            WalletStorage.markImportedSmsSeen(mContext);
        }

        // ဒီ Function က ဖိုင်အမျိုးအစားစုံ (Excel/JSON) ကို Save ပေးပါမယ်
        @JavascriptInterface
        public void saveFile(String fileName, String content, String mimeType) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                Uri uri = mContext.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream outputStream = mContext.getContentResolver().openOutputStream(uri);
                    if (outputStream != null) {
                        outputStream.write(content.getBytes(StandardCharsets.UTF_8)); // Write String content
                        outputStream.close();
                        Toast.makeText(mContext, "Saved to Downloads: " + fileName, Toast.LENGTH_LONG).show();
                    }
                }
            } catch (Exception e) {
                Toast.makeText(mContext, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        /**
         * Smart Notifications: store budget limit & spent, schedule daily alert check
         */
        @JavascriptInterface
        public void setBudgetAlert(double limit, double spent) {
            SharedPreferences prefs = mContext.getSharedPreferences(PREFS_BUDGET, Context.MODE_PRIVATE);
            prefs.edit()
                    .putLong("budget_limit", Double.doubleToLongBits(limit))
                    .putLong("budget_spent", Double.doubleToLongBits(spent))
                    .apply();
            PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(BudgetAlertWorker.class, 24, TimeUnit.HOURS)
                    .build();
            WorkManager.getInstance(mContext).enqueueUniquePeriodicWork("budget_alert", ExistingPeriodicWorkPolicy.KEEP,
                    work);
        }

        /**
         * Smart Notifications: store full financial context for background summaries
         */
        @JavascriptInterface
        public void updateSmartNotificationData(String jsonData) {
            SharedPreferences prefs = mContext.getSharedPreferences(PREFS_BUDGET, Context.MODE_PRIVATE);
            prefs.edit().putString("notification_data", jsonData).apply();

            // Ensure the worker is scheduled if not already
            PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(BudgetAlertWorker.class, 24, TimeUnit.HOURS)
                    .build();
            WorkManager.getInstance(mContext).enqueueUniquePeriodicWork("budget_alert", ExistingPeriodicWorkPolicy.KEEP,
                    work);
        }

        /**
         * Trigger an immediate notification (e.g. for budget or debt alerts when
         * saving)
         */
        @JavascriptInterface
        public void showImmediateNotification(String title, String body, int id) {
            String channelId = "smart_wallet_alerts";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(channelId, "Smart Alerts",
                        NotificationManager.IMPORTANCE_HIGH);
                mContext.getSystemService(NotificationManager.class).createNotificationChannel(ch);
            }

            NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);

            try {
                NotificationManagerCompat.from(mContext).notify(id, builder.build());
            } catch (SecurityException ignored) {
            }
        }

        /**
         * Google Drive Backup: save JSON and open share sheet so user can save to Drive
         */
        @JavascriptInterface
        public void shareBackupToDrive(String jsonContent) {
            try {
                String fileName = "SmartWalletPro_Backup_" + System.currentTimeMillis() + ".json";
                File cacheDir = mContext.getCacheDir();
                File file = new File(cacheDir, fileName);
                OutputStream os = new java.io.FileOutputStream(file);
                os.write(jsonContent.getBytes(StandardCharsets.UTF_8));
                os.close();
                Uri uri = FileProvider.getUriForFile(mContext, mContext.getPackageName() + ".fileprovider", file);
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("application/json");
                share.putExtra(Intent.EXTRA_STREAM, uri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                mContext.startActivity(Intent.createChooser(share, "Save to Google Drive or share"));
            } catch (Exception e) {
                Toast.makeText(mContext, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        /** Trigger Google Sign-In via native Credential Manager */
        @JavascriptInterface
        public void googleLogin() {
            mContext.runOnUiThread(() -> {
                GetGoogleIdOption googleIdOption = new GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(false)
                        .setServerClientId(CLIENT_ID)
                        .setAutoSelectEnabled(true)
                        .build();

                GetCredentialRequest request = new GetCredentialRequest.Builder()
                        .addCredentialOption(googleIdOption)
                        .build();

                credentialManager.getCredentialAsync(mContext, request, null, Runnable::run,
                        new androidx.credentials.CredentialManagerCallback<GetCredentialResponse, androidx.credentials.exceptions.GetCredentialException>() {
                            @Override
                            public void onResult(GetCredentialResponse result) {
                                try {
                                    androidx.credentials.Credential credential = result.getCredential();
                                    GoogleIdTokenCredential googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.getData());
                                    
                                    String json = String.format(
                                            "{\"id\":\"%s\",\"email\":\"%s\",\"name\":\"%s\",\"photo\":\"%s\"}",
                                            googleIdTokenCredential.getId(), 
                                            googleIdTokenCredential.getId(),
                                            googleIdTokenCredential.getDisplayName() != null ? googleIdTokenCredential.getDisplayName() : "",
                                            googleIdTokenCredential.getProfilePictureUri() != null ? googleIdTokenCredential.getProfilePictureUri().toString() : "");
                                    
                                    // Store email for Drive API use
                                    String emailAddress = googleIdTokenCredential.getId();
                                    if (emailAddress != null && !emailAddress.isEmpty()) {
                                        SharedPreferences prefs = mContext.getSharedPreferences(PREFS_BUDGET, Context.MODE_PRIVATE);
                                        prefs.edit().putString("user_email", emailAddress).apply();
                                    }
                                    
                                    sendToJs("onLoginSuccess", json);
                                } catch (Exception e) {
                                    sendToJs("onLoginError", "Auth Process Error: " + e.getMessage());
                                }
                            }

                            @Override
                            public void onError(androidx.credentials.exceptions.GetCredentialException e) {
                                String errorMsg = "Login Failed";
                                if (e instanceof androidx.credentials.exceptions.GetCredentialCancellationException) {
                                    errorMsg = "Login Cancelled";
                                } else if (e instanceof androidx.credentials.exceptions.NoCredentialException) {
                                    errorMsg = "No Google account found or configuration error";
                                } else {
                                    errorMsg = e.getMessage();
                                }
                                sendToJs("onLoginError", errorMsg);
                            }
                        });
            });
        }

        @JavascriptInterface
        public void googleLogout() {
            credentialManager.clearCredentialStateAsync(new androidx.credentials.ClearCredentialStateRequest(), null,
                    Runnable::run,
                    new androidx.credentials.CredentialManagerCallback<Void, androidx.credentials.exceptions.ClearCredentialException>() {
                        @Override
                        public void onResult(Void result) {
                            sendToJs("onLogoutSuccess", "{}");
                        }

                        @Override
                        public void onError(androidx.credentials.exceptions.ClearCredentialException e) {
                        }
                    });
        }

        @JavascriptInterface
        public void driveBackup(String jsonData) {
            new Thread(() -> {
                try {
                    SharedPreferences prefs = mContext.getSharedPreferences(PREFS_BUDGET, Context.MODE_PRIVATE);
                    String emailCheck = prefs.getString("user_email", "").trim();
                    if (emailCheck.isEmpty() || !emailCheck.contains("@") || emailCheck.equalsIgnoreCase("null")) {
                        sendToJs("onDriveStatus", "Auth required (Invalid Email)");
                        return;
                    }

                    Drive drive = getDriveService();
                    if (drive == null) {
                        sendToJs("onDriveStatus", "Sync System Initialization Failed");
                        return;
                    }

                    // Build Metadata as suggested by advisor
                    com.google.api.services.drive.model.File existing = findBackupFile(drive);
                    com.google.api.services.drive.model.File fileMetadata = new com.google.api.services.drive.model.File();
                    fileMetadata.setName("SmartWalletBackup.json");
                    fileMetadata.setMimeType("application/json");

                    ByteArrayContent content = new ByteArrayContent("application/json", jsonData.getBytes(StandardCharsets.UTF_8));
                    
                    if (existing == null) {
                        fileMetadata.setParents(Collections.singletonList("appDataFolder"));
                        drive.files().create(fileMetadata, content).execute();
                        sendToJs("onDriveStatus", "Cloud Backup Created!");
                    } else {
                        String fileId = existing.getId();
                        if (fileId == null || fileId.isEmpty()) throw new Exception("File ID missing");
                        // Also update metadata to ensure name is correct
                        drive.files().update(fileId, fileMetadata, content).execute();
                        sendToJs("onDriveStatus", "Cloud Backup Updated!");
                    }
                } catch (UserRecoverableAuthIOException e) {
                    lastBackupJson = jsonData;
                    mContext.startActivityForResult(e.getIntent(), REQUEST_AUTHORIZATION);
                } catch (Exception e) {
                    SharedPreferences p = mContext.getSharedPreferences(PREFS_BUDGET, Context.MODE_PRIVATE);
                    String em = p.getString("user_email", "unknown");
                    e.printStackTrace();
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String fullMsg = sw.toString();
                    String shortMsg = fullMsg.substring(0, Math.min(fullMsg.length(), 200)).replace("\n", " ");
                    sendToJs("onDriveStatus", "Drive Error (" + em + "): " + shortMsg);
                }
            }).start();
        }

        /** Restore JSON data from Google Drive */
        @JavascriptInterface
        public void driveRestore() {
            new Thread(() -> {
                try {
                    String emailCheck = mContext.getSharedPreferences(PREFS_BUDGET, Context.MODE_PRIVATE).getString("user_email", "");
                    if (emailCheck.isEmpty()) {
                        sendToJs("onDriveStatus", "Auth required (Email missing)");
                        return;
                    }

                    Drive drive = getDriveService();
                    if (drive == null) {
                        sendToJs("onDriveStatus", "Auth required (Service failed)");
                        return;
                    }

                    com.google.api.services.drive.model.File existing = findBackupFile(drive);
                    if (existing == null) {
                        sendToJs("onDriveStatus", "No backup found in cloud");
                        return;
                    }

                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    drive.files().get(existing.getId()).executeMediaAndDownloadTo(outputStream);
                    String json = new String(outputStream.toByteArray(), StandardCharsets.UTF_8);

                    sendToJs("onRestoreSuccess", json);
                    sendToJs("onDriveStatus", "Restore Success!");
                } catch (UserRecoverableAuthIOException e) {
                    lastBackupJson = null; // Mark as restore retry
                    mContext.startActivityForResult(e.getIntent(), REQUEST_AUTHORIZATION);
                } catch (Exception e) {
                    SharedPreferences p = mContext.getSharedPreferences(PREFS_BUDGET, Context.MODE_PRIVATE);
                    String em = p.getString("user_email", "unknown");
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String fullMsg = sw.toString();
                    String shortMsg = fullMsg.substring(0, Math.min(fullMsg.length(), 200)).replace("\n", " ");
                    sendToJs("onDriveStatus", "Restore Error (" + em + "): " + shortMsg);
                }
            }).start();
        }

        private Drive getDriveService() {
            try {
                SharedPreferences prefs = mContext.getSharedPreferences(PREFS_BUDGET, Context.MODE_PRIVATE);
                String email = prefs.getString("user_email", "").trim();
                if (email.isEmpty() || email.equalsIgnoreCase("null")) return null;

                GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(mContext,
                        Collections.singletonList(DriveScopes.DRIVE_APPDATA));
                
                // Use a more direct account setup
                try {
                    credential.setSelectedAccount(new Account(email, "com.google"));
                } catch (Exception e) {
                    credential.setSelectedAccountName(email);
                }

                return new Drive.Builder(new NetHttpTransport(), new GsonFactory(), credential)
                        .setApplicationName("SmartWallet")
                        .build();
            } catch (Exception e) {
                return null;
            }
        }

        private com.google.api.services.drive.model.File findBackupFile(Drive drive) throws java.io.IOException {
            com.google.api.services.drive.model.FileList result = drive.files().list()
                    .setSpaces("appDataFolder")
                    .setQ("name = 'SmartWalletBackup.json'")
                    .setFields("files(id, name)")
                    .execute();
            List<com.google.api.services.drive.model.File> files = result.getFiles();
            return (files == null || files.isEmpty()) ? null : files.get(0);
        }

        private void sendToJs(String func, String params) {
            String encoded = Base64.encodeToString(params.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            mContext.runOnUiThread(() -> myWebView.evaluateJavascript(buildUtf8CallbackScript(func, encoded), null));
        }

        @JavascriptInterface
        public void scanReceipt() {
            Activity act = (Activity) mContext;
            if (ContextCompat.checkSelfPermission(act,
                    Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(act, new String[] { Manifest.permission.CAMERA }, 3);
                Toast.makeText(mContext, "Please grant camera permission and try again.", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                File photoFile = new File(mContext.getCacheDir(), "receipt_" + System.currentTimeMillis() + ".jpg");
                Uri uri = FileProvider.getUriForFile(mContext, mContext.getPackageName() + ".fileprovider", photoFile);
                MainActivity.this.receiptPhotoUri = uri;
                Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
                act.startActivityForResult(Intent.createChooser(intent, "Take receipt photo"), REQUEST_CAMERA_RECEIPT);
            } catch (Exception e) {
                Toast.makeText(mContext, "Camera error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }
}
