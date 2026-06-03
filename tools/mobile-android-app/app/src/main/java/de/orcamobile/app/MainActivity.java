package de.orcamobile.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private WebView webView;
    private EditText serverInput;
    private TextView statusText;
    private LinearLayout importList;
    private String lastDownloadId;
    private final Map<String, PendingBlob> pendingBlobs = new HashMap<>();

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 12, 12, 12);

        serverInput = new EditText(this);
        serverInput.setSingleLine(true);
        serverInput.setText("http://192.168.1.79:8787");
        root.addView(serverInput, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout quickLinks = new LinearLayout(this);
        quickLinks.setOrientation(LinearLayout.HORIZONTAL);
        addQuickLink(quickLinks, "Printables", "https://www.printables.com/");
        addQuickLink(quickLinks, "MakerWorld", "https://makerworld.com/");
        addQuickLink(quickLinks, "Thingiverse", "https://www.thingiverse.com/");
        addQuickLink(quickLinks, "Cults3D", "https://cults3d.com/");
        root.addView(quickLinks, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout browserTools = new LinearLayout(this);
        browserTools.setOrientation(LinearLayout.HORIZONTAL);
        Button backButton = new Button(this);
        backButton.setText("Zurück");
        backButton.setOnClickListener(v -> {
            if (webView.canGoBack()) {
                webView.goBack();
            }
        });
        browserTools.addView(backButton, new LinearLayout.LayoutParams(0, -2, 1));
        Button externalButton = new Button(this);
        externalButton.setText("Extern öffnen");
        externalButton.setOnClickListener(v -> openCurrentPageExternally());
        browserTools.addView(externalButton, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(browserTools, new LinearLayout.LayoutParams(-1, -2));

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        webView.addJavascriptInterface(new BlobBridge(), "OrcaBlobBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectBlobCaptureScript();
            }
        });
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            if (url.startsWith("blob:")) {
                startBlobRemoteDownload(url, fileName, mimeType);
                return;
            }
            String cookies = CookieManager.getInstance().getCookie(url);
            confirmRemoteDownload(url, fileName, mimeType, userAgent, cookies);
        });
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));

        statusText = new TextView(this);
        statusText.setText("Bereit");
        root.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        ScrollView importsScroll = new ScrollView(this);
        importList = new LinearLayout(this);
        importList.setOrientation(LinearLayout.VERTICAL);
        importsScroll.addView(importList);
        root.addView(importsScroll, new LinearLayout.LayoutParams(-1, 220));

        setContentView(root);
        webView.loadUrl("https://www.printables.com/");
    }

    private void injectBlobCaptureScript() {
        String script = "(() => {"
                + "if (window.__orcaBlobCaptureInstalled) return;"
                + "window.__orcaBlobCaptureInstalled = true;"
                + "window.__orcaBlobs = window.__orcaBlobs || {};"
                + "const oldCreate = URL.createObjectURL.bind(URL);"
                + "URL.createObjectURL = function(value) {"
                + "const objectUrl = oldCreate(value);"
                + "try { if (value instanceof Blob) window.__orcaBlobs[objectUrl] = value; } catch (error) {}"
                + "return objectUrl;"
                + "};"
                + "const oldRevoke = URL.revokeObjectURL.bind(URL);"
                + "URL.revokeObjectURL = function(objectUrl) {"
                + "try { oldRevoke(objectUrl); } catch (error) {}"
                + "};"
                + "})();";
        webView.evaluateJavascript(script, null);
    }

    private void addQuickLink(LinearLayout parent, String label, String url) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(v -> webView.loadUrl(url));
        parent.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void confirmRemoteDownload(String url, String filename, String mimeType, String userAgent, String cookies) {
        new AlertDialog.Builder(this)
                .setTitle("Auf VM herunterladen?")
                .setMessage(filename + "\n\nDie Datei wird nicht auf dem Handy gespeichert.")
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("VM Download", (dialog, which) -> {
                    if (url.startsWith("blob:")) {
                        startBlobRemoteDownload(url, filename, mimeType);
                    } else {
                        startRemoteDownload(url, filename, mimeType, userAgent, cookies);
                    }
                })
                .show();
    }

    private String serverBase() {
        String value = serverInput.getText().toString().trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private void setStatus(String text) {
        mainHandler.post(() -> statusText.setText(text));
    }

    private void startRemoteDownload(String url, String filename, String mimeType, String userAgent, String cookies) {
        setStatus("Download wird auf der VM gestartet...");
        importList.removeAllViews();
        String referer = webView.getUrl() == null ? "" : webView.getUrl();
        executor.submit(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("url", url);
                body.put("filename", filename);
                body.put("mime_type", mimeType);
                body.put("user_agent", userAgent);
                body.put("cookie_header", cookies == null ? "" : cookies);
                body.put("referer", referer);
                body.put("debug_log_cookies", true);
                JSONObject response = postJson("/api/v1/remote-downloads", body);
                lastDownloadId = response.getJSONObject("download").getString("id");
                pollDownload();
            } catch (Exception e) {
                setStatus("Download konnte nicht gestartet werden: " + e.getMessage());
            }
        });
    }

    private void startBlobRemoteDownload(String url, String filename, String mimeType) {
        setStatus("Browser-Download wird zur VM gesendet...");
        importList.removeAllViews();
        String transferId = "blob-" + System.currentTimeMillis();
        String script = "(async () => {"
                + "try {"
                + "const transferId = " + JSONObject.quote(transferId) + ";"
                + "OrcaBlobBridge.startBlob(transferId, " + JSONObject.quote(filename) + ", " + JSONObject.quote(mimeType == null ? "" : mimeType) + ", " + JSONObject.quote(url) + ", location.href);"
                + "async function readBlob(blobUrl) {"
                + "const captured = window.__orcaBlobs && window.__orcaBlobs[blobUrl];"
                + "if (captured) return captured;"
                + "try {"
                + "const blobResponse = await fetch(blobUrl);"
                + "if (!blobResponse.ok) throw new Error('fetch status ' + blobResponse.status);"
                + "return await blobResponse.blob();"
                + "} catch (fetchError) {"
                + "return await new Promise((resolve, reject) => {"
                + "const xhr = new XMLHttpRequest();"
                + "xhr.open('GET', blobUrl);"
                + "xhr.responseType = 'blob';"
                + "xhr.onload = () => xhr.status === 200 || xhr.status === 0 ? resolve(xhr.response) : reject(new Error('xhr status ' + xhr.status));"
                + "xhr.onerror = () => reject(new Error('fetch failed: ' + fetchError + '; xhr failed'));"
                + "xhr.send();"
                + "});"
                + "}"
                + "}"
                + "const blob = await readBlob(" + JSONObject.quote(url) + ");"
                + "const bytes = new Uint8Array(await blob.arrayBuffer());"
                + "if (bytes.length === 0) throw new Error('blob is empty');"
                + "const chunkSize = 32768;"
                + "for (let offset = 0; offset < bytes.length; offset += chunkSize) {"
                + "let binary = '';"
                + "const chunk = bytes.subarray(offset, Math.min(offset + chunkSize, bytes.length));"
                + "for (let i = 0; i < chunk.length; i++) binary += String.fromCharCode(chunk[i]);"
                + "OrcaBlobBridge.appendBlobChunk(transferId, btoa(binary));"
                + "}"
                + "OrcaBlobBridge.finishBlob(transferId);"
                + "return JSON.stringify({ ok: true });"
                + "} catch (error) {"
                + "const capturedKeys = window.__orcaBlobs ? Object.keys(window.__orcaBlobs).length : 0;"
                + "OrcaBlobBridge.failBlob(" + JSONObject.quote(transferId) + ", String(error) + '; captured blobs=' + capturedKeys);"
                + "return JSON.stringify({ ok: false, error: String(error) });"
                + "}"
                + "})()";

        webView.evaluateJavascript(script, value -> {
            try {
                JSONObject result = new JSONObject(unquoteJavascriptString(value));
                if (!result.optBoolean("ok")) {
                    setStatus("Blob konnte nicht gelesen werden: " + result.optString("error"));
                }
            } catch (Exception e) {
                setStatus("Blob-Lesen konnte nicht ausgewertet werden: " + e.getMessage());
            }
        });
    }

    private void uploadBlobToVm(PendingBlob pending) {
        executor.submit(() -> {
            try {
                setStatus("Blob wird nativ zur VM hochgeladen...");
                JSONObject response = postMultipartUpload(
                        "/api/v1/remote-download-uploads",
                        pending.filename,
                        pending.mimeType,
                        pending.sourceUrl,
                        pending.referer,
                        pending.data.toByteArray()
                );
                lastDownloadId = response.getJSONObject("download").getString("id");
                inspectDownload();
            } catch (Exception e) {
                setStatus("Blob-Upload fehlgeschlagen: " + e.getMessage());
            }
        });
    }

    private String unquoteJavascriptString(String value) throws Exception {
        if (value == null || "null".equals(value)) {
            return "";
        }
        return new JSONArray("[" + value + "]").getString(0);
    }

    private void pollDownload() throws Exception {
        for (int i = 0; i < 120; i++) {
            JSONObject response = getJson("/api/v1/remote-downloads/" + lastDownloadId);
            JSONObject download = response.getJSONObject("download");
            String status = download.getString("status");
            setStatus("VM Download: " + status);
            if ("succeeded".equals(status)) {
                inspectDownload();
                return;
            }
            if ("failed".equals(status)) {
                setStatus("Download fehlgeschlagen: " + download.optString("error"));
                return;
            }
            Thread.sleep(1000);
        }
        setStatus("Download dauert zu lange.");
    }

    private void inspectDownload() throws Exception {
        JSONObject response = postJson("/api/v1/remote-downloads/" + lastDownloadId + "/inspect", new JSONObject());
        JSONObject inspection = response.getJSONObject("inspection");
        JSONArray entries = inspection.getJSONArray("supported_entries");
        if (entries.length() == 0) {
            setStatus("Keine unterstützte Modell-/Projektdatei gefunden: " + inspection.optString("kind"));
            return;
        }
        setStatus("Gefundene Dateien: " + entries.length());
        String kind = inspection.optString("kind");
        mainHandler.post(() -> renderImportEntries(kind, entries));
    }

    private void renderImportEntries(String kind, JSONArray entries) {
        importList.removeAllViews();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            String path = "archive".equals(kind) ? entry.optString("path") : null;
            String label = entry.optString("filename") + " (" + entry.optString("import_type") + ")";
            Button button = new Button(this);
            button.setText("Importieren: " + label);
            button.setOnClickListener(v -> importEntry(path));
            importList.addView(button, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void importEntry(String entryPath) {
        setStatus("Import auf VM läuft...");
        executor.submit(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("download_id", lastDownloadId);
                if (entryPath != null && !entryPath.isEmpty()) {
                    body.put("entry_path", entryPath);
                }
                JSONObject response = postJson("/api/v1/imports", body);
                JSONObject model = response.getJSONObject("imported_model");
                setStatus("Importiert: " + model.getString("filename"));
            } catch (Exception e) {
                setStatus("Import fehlgeschlagen: " + e.getMessage());
            }
        });
    }

    private void openCurrentPageExternally() {
        String url = webView.getUrl();
        if (url == null || url.isEmpty()) {
            return;
        }
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private JSONObject getJson(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(serverBase() + path).openConnection();
        connection.setRequestMethod("GET");
        return readJson(connection);
    }

    private JSONObject postJson(String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(serverBase() + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        try (OutputStream stream = connection.getOutputStream()) {
            stream.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        return readJson(connection);
    }

    private JSONObject postMultipartUpload(String path, String filename, String mimeType, String sourceUrl, String referer, byte[] data) throws Exception {
        String boundary = "----OrcaMobile" + System.currentTimeMillis();
        HttpURLConnection connection = (HttpURLConnection) new URL(serverBase() + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setDoOutput(true);
        try (DataOutputStream stream = new DataOutputStream(connection.getOutputStream())) {
            writeMultipartField(stream, boundary, "source_url", sourceUrl);
            writeMultipartField(stream, boundary, "mime_type", mimeType == null ? "" : mimeType);
            writeMultipartField(stream, boundary, "referer", referer == null ? "" : referer);
            stream.writeBytes("--" + boundary + "\r\n");
            stream.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename.replace("\"", "_") + "\"\r\n");
            stream.writeBytes("Content-Type: " + (mimeType == null || mimeType.isEmpty() ? "application/octet-stream" : mimeType) + "\r\n\r\n");
            stream.write(data);
            stream.writeBytes("\r\n--" + boundary + "--\r\n");
        }
        return readJson(connection);
    }

    private void writeMultipartField(DataOutputStream stream, String boundary, String name, String value) throws Exception {
        stream.writeBytes("--" + boundary + "\r\n");
        stream.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        stream.write(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
        stream.writeBytes("\r\n");
    }

    private JSONObject readJson(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        if (code >= 400) {
            throw new IllegalStateException(builder.toString());
        }
        return new JSONObject(builder.toString());
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private static class PendingBlob {
        final String filename;
        final String mimeType;
        final String sourceUrl;
        final String referer;
        final ByteArrayOutputStream data = new ByteArrayOutputStream();

        PendingBlob(String filename, String mimeType, String sourceUrl, String referer) {
            this.filename = filename;
            this.mimeType = mimeType;
            this.sourceUrl = sourceUrl;
            this.referer = referer;
        }
    }

    private class BlobBridge {
        @JavascriptInterface
        public void startBlob(String id, String filename, String mimeType, String sourceUrl, String referer) {
            synchronized (pendingBlobs) {
                pendingBlobs.put(id, new PendingBlob(filename, mimeType, sourceUrl, referer));
            }
        }

        @JavascriptInterface
        public void appendBlobChunk(String id, String base64Chunk) {
            byte[] chunk = Base64.decode(base64Chunk, Base64.DEFAULT);
            synchronized (pendingBlobs) {
                PendingBlob pending = pendingBlobs.get(id);
                if (pending != null) {
                    pending.data.write(chunk, 0, chunk.length);
                }
            }
        }

        @JavascriptInterface
        public void finishBlob(String id) {
            PendingBlob pending;
            synchronized (pendingBlobs) {
                pending = pendingBlobs.remove(id);
            }
            if (pending != null) {
                uploadBlobToVm(pending);
            }
        }

        @JavascriptInterface
        public void failBlob(String id, String error) {
            synchronized (pendingBlobs) {
                pendingBlobs.remove(id);
            }
            setStatus("Blob konnte nicht gelesen werden: " + error);
        }
    }
}
