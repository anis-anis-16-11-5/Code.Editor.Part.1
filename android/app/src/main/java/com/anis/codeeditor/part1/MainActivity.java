package com.anis.codeeditor.part1;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.MediaScannerConnection;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String CHANNEL_ID = "code_editor_downloads";
    private static final int FILE_CHOOSER_REQUEST = 4201;
    private static final int PERMISSION_REQUEST_NOTIFICATIONS = 4202;
    private static final int PERMISSION_REQUEST_STORAGE = 4203;

    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF07133F);
        getWindow().setNavigationBarColor(0xFF000000);
        createNotificationChannel();
        requestRuntimePermissions();

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                if ("file".equalsIgnoreCase(scheme)) return false;
                if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "text/*", "application/zip", "application/x-zip-compressed", "application/json"
                });
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception error) {
                    fileChooserCallback = null;
                    filePathCallback.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_STORAGE);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        Uri soundUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.download_notification);
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "تنزيلات Code.Editor.Part.1",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("إشعارات اكتمال تنزيل ملفات المشروع");
        channel.setSound(soundUri, audioAttributes);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private String cleanFileName(String value) {
        String name = value == null ? "project.zip" : value.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (name.isEmpty()) name = "project.zip";
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) name += ".zip";
        return name;
    }

    private Uri saveToDownloads(String fileName, byte[] bytes) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Code.Editor.Part.1");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri item = getContentResolver().insert(collection, values);
            if (item == null) throw new IOException("تعذر إنشاء الملف في التنزيلات");
            try (OutputStream output = getContentResolver().openOutputStream(item)) {
                if (output == null) throw new IOException("تعذر فتح الملف للكتابة");
                output.write(bytes);
                output.flush();
            } catch (IOException error) {
                getContentResolver().delete(item, null, null);
                throw error;
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(item, ready, null, null);
            return item;
        }

        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            throw new IOException("يجب السماح للتطبيق بحفظ الملفات في التنزيلات");
        }
        File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Code.Editor.Part.1");
        if (!folder.exists() && !folder.mkdirs()) throw new IOException("تعذر إنشاء مجلد التنزيلات");
        File file = new File(folder, fileName);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
            output.flush();
        }
        MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()},
                new String[]{"application/zip"}, null);
        return Uri.fromFile(file);
    }

    private void notifyDownloadComplete(String fileName, Uri fileUri) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            playDownloadSoundFallback();
            return;
        }
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                (int) System.currentTimeMillis(),
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        NotificationCompat.Builder notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_code)
                .setContentTitle("Code.Editor.Part.1")
                .setContentText("تم تنزيل " + fileName + " إلى مجلد التنزيلات")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("تم حفظ الملف " + fileName + " في Downloads/Code.Editor.Part.1"))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        NotificationManagerCompat.from(this).notify((int) (System.currentTimeMillis() & 0x7fffffff), notification.build());
    }

    private void playDownloadSoundFallback() {
        try {
            MediaPlayer player = MediaPlayer.create(this, R.raw.download_notification);
            if (player != null) {
                player.setOnCompletionListener(MediaPlayer::release);
                player.start();
            }
        } catch (Exception ignored) {
        }
    }

    private void sendJavaScript(String method, String fileName) {
        if (webView == null) return;
        String argument = JSONObject.quote(fileName == null ? "" : fileName);
        webView.post(() -> webView.evaluateJavascript("window." + method + "(" + argument + ");", null));
    }

    private final class AndroidBridge {
        @JavascriptInterface
        public void saveZip(String base64, String requestedName) {
            final String fileName = cleanFileName(requestedName);
            ioExecutor.execute(() -> {
                try {
                    byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                    Uri saved = saveToDownloads(fileName, bytes);
                    notifyDownloadComplete(fileName, saved);
                    sendJavaScript("nativeDownloadComplete", fileName);
                } catch (Exception error) {
                    sendJavaScript("nativeDownloadError", "");
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileChooserCallback != null) {
                Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                fileChooserCallback.onReceiveValue(result);
                fileChooserCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidBridge");
            webView.destroy();
            webView = null;
        }
        ioExecutor.shutdown();
        super.onDestroy();
    }
}
