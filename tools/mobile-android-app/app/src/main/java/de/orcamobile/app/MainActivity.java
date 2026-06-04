package de.orcamobile.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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
    private static final String PREFS_NAME = "orca_mobile";
    private static final String PREF_SERVER_BASE = "server_base";
    private static final String DEFAULT_SERVER_BASE = "http://192.168.1.79:8787";

    private static final int C_BACKGROUND = Color.rgb(248, 249, 250);
    private static final int C_SURFACE = Color.WHITE;
    private static final int C_SURFACE_SOFT = Color.rgb(237, 238, 239);
    private static final int C_TEXT = Color.rgb(25, 28, 29);
    private static final int C_MUTED = Color.rgb(64, 72, 80);
    private static final int C_PRIMARY = Color.rgb(0, 93, 144);
    private static final int C_PRIMARY_LIGHT = Color.rgb(205, 229, 255);
    private static final int C_DANGER = Color.rgb(186, 26, 26);
    private static final int C_BORDER = Color.rgb(191, 199, 209);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, PendingBlob> pendingBlobs = new HashMap<>();

    private LinearLayout root;
    private FrameLayout contentFrame;
    private LinearLayout bottomNav;
    private LinearLayout workflowPanel;
    private TextView statusText;
    private TextView connectionBadge;
    private WebView webView;
    private EditText serverInput;

    private String currentTab = "browse";
    private String lastDownloadId;
    private String lastImportedModelId;
    private String lastJobId;
    private JSONObject lastPrinterStatus;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BACKGROUND);

        root.addView(createHeader(), new LinearLayout.LayoutParams(-1, dp(64)));

        contentFrame = new FrameLayout(this);
        root.addView(contentFrame, new LinearLayout.LayoutParams(-1, 0, 1));

        statusText = new TextView(this);
        statusText.setText("Bereit");
        statusText.setTextColor(C_MUTED);
        statusText.setTextSize(13);
        statusText.setPadding(dp(20), dp(8), dp(20), dp(8));
        root.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER);
        bottomNav.setPadding(dp(8), dp(6), dp(8), dp(8));
        bottomNav.setBackground(cardBackground(C_SURFACE, 0, C_BORDER, dp(20)));
        root.addView(bottomNav, new LinearLayout.LayoutParams(-1, dp(74)));

        webView = createWebView();
        workflowPanel = vertical();

        setContentView(root);
        selectTab("browse");
        webView.loadUrl("https://www.printables.com/");
    }

    private View createHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(8), dp(20), dp(8));
        header.setBackgroundColor(C_BACKGROUND);

        TextView title = new TextView(this);
        title.setText("Orca Mobile");
        title.setTextColor(C_PRIMARY);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -1, 1));

        connectionBadge = chip("Verbunden", C_PRIMARY_LIGHT, C_PRIMARY);
        header.addView(connectionBadge, new LinearLayout.LayoutParams(-2, dp(36)));
        return header;
    }

    private WebView createWebView() {
        WebView view = new WebView(this);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        view.addJavascriptInterface(new BlobBridge(), "OrcaBlobBridge");
        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectBlobCaptureScript();
            }
        });
        view.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            if (url.startsWith("blob:")) {
                startBlobRemoteDownload(url, fileName, mimeType);
                return;
            }
            String cookies = CookieManager.getInstance().getCookie(url);
            confirmRemoteDownload(url, fileName, mimeType, userAgent, cookies);
        });
        return view;
    }

    private void selectTab(String tab) {
        currentTab = tab;
        contentFrame.removeAllViews();
        renderBottomNav();
        if ("browse".equals(tab)) {
            renderBrowse();
        } else if ("project".equals(tab)) {
            renderProjects();
        } else if ("control".equals(tab)) {
            renderControl();
        } else {
            renderSettings();
        }
    }

    private void renderBottomNav() {
        bottomNav.removeAllViews();
        bottomNav.addView(navButton("Entdecken", "browse"), new LinearLayout.LayoutParams(0, -1, 1));
        bottomNav.addView(navButton("Projekte", "project"), new LinearLayout.LayoutParams(0, -1, 1));
        bottomNav.addView(navButton("Drucker", "control"), new LinearLayout.LayoutParams(0, -1, 1));
        bottomNav.addView(navButton("Einstellungen", "settings"), new LinearLayout.LayoutParams(0, -1, 1));
    }

    private Button navButton(String label, String tab) {
        boolean active = tab.equals(currentTab);
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(12);
        button.setTextColor(active ? C_PRIMARY : C_MUTED);
        button.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
        button.setBackground(cardBackground(active ? C_PRIMARY_LIGHT : Color.TRANSPARENT, 0, active ? C_PRIMARY_LIGHT : Color.TRANSPARENT, dp(18)));
        button.setOnClickListener(v -> selectTab(tab));
        return button;
    }

    private void renderBrowse() {
        LinearLayout screen = vertical();
        screen.setPadding(dp(20), dp(10), dp(20), dp(8));
        addScreenTitle(screen, "Entdecken", "Durchsuche Modell-Webseiten und importiere Dateien direkt auf die VM.");

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        LinearLayout platforms = new LinearLayout(this);
        platforms.setOrientation(LinearLayout.HORIZONTAL);
        addPlatformChip(platforms, "Printables", "https://www.printables.com/");
        addPlatformChip(platforms, "MakerWorld", "https://makerworld.com/");
        addPlatformChip(platforms, "Thingiverse", "https://www.thingiverse.com/");
        addPlatformChip(platforms, "Cults3D", "https://cults3d.com/");
        scroll.addView(platforms);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, dp(54)));

        detach(webView);
        LinearLayout browserCard = card();
        browserCard.setPadding(0, 0, 0, 0);
        browserCard.addView(webView, new LinearLayout.LayoutParams(-1, -1));
        screen.addView(browserCard, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView hint = body(lastDownloadId == null ? "Downloads erscheinen nach der Prüfung im Tab Projekte." : "Download erkannt. Wechsle zu Projekte fur Import und Druckvorbereitung.");
        hint.setGravity(Gravity.CENTER);
        screen.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        contentFrame.addView(screen);
    }

    private void renderProjects() {
        LinearLayout screen = screenScrollContent();
        addScreenTitle(screen, "Projekte", "Importierte Modelle und Druckvorbereitung.");
        attachWorkflowPanel(screen, "Aktueller Arbeitsablauf");
        if (workflowPanel.getChildCount() == 0) {
            addInfoCard(workflowPanel, "Noch kein Modell importiert", "Lade in Entdecken ein Modell herunter und importiere es. Danach erscheinen hier Slice- und Druckaktionen.");
        }
        contentFrame.addView(wrapScroll(screen));
    }

    private void renderControl() {
        LinearLayout screen = screenScrollContent();
        addScreenTitle(screen, "Drucker", "Status, Temperaturen und Druckkontrolle fur deinen Bambu Lab A1.");

        LinearLayout statusCard = card();
        TextView state = headline(lastPrinterStatus == null ? "Status unbekannt" : readableState(lastPrinterStatus.optString("gcode_state", "unbekannt")));
        statusCard.addView(state);
        statusCard.addView(body(lastPrinterStatus == null ? "Tippe auf Status aktualisieren." : formatPrinterStatus(lastPrinterStatus)));
        screen.addView(statusCard);

        LinearLayout metrics = horizontal();
        metrics.addView(metricCard("Duse", temperatureText("nozzle")), new LinearLayout.LayoutParams(0, -2, 1));
        metrics.addView(metricCard("Bett", temperatureText("bed")), new LinearLayout.LayoutParams(0, -2, 1));
        screen.addView(metrics);

        LinearLayout amsCard = card();
        amsCard.addView(headline("Meine Filamente"));
        amsCard.addView(body("AMS-Daten konnen bei Fremdspulen unvollstandig sein. Die manuelle Slot-Zuordnung folgt in einem nachsten Schritt."));
        LinearLayout slots = horizontal();
        for (int i = 1; i <= 4; i++) {
            slots.addView(slotCard("Slot " + i), new LinearLayout.LayoutParams(0, dp(86), 1));
        }
        amsCard.addView(slots);
        screen.addView(amsCard);

        screen.addView(primaryButton("Status aktualisieren", v -> checkPrinterStatus()), new LinearLayout.LayoutParams(-1, dp(52)));
        screen.addView(dangerButton("Druck stoppen", v -> confirmCancelPrint()), new LinearLayout.LayoutParams(-1, dp(52)));
        contentFrame.addView(wrapScroll(screen));
    }

    private void renderSettings() {
        LinearLayout screen = screenScrollContent();
        addScreenTitle(screen, "Einstellungen", "Server, VM und Drucker einfach verbinden.");

        LinearLayout serverCard = card();
        serverCard.addView(headline("Server Setup"));
        serverInput = new EditText(this);
        serverInput.setSingleLine(true);
        serverInput.setText(loadServerBase());
        serverInput.setTextColor(C_TEXT);
        serverInput.setTextSize(16);
        serverInput.setBackground(cardBackground(C_SURFACE_SOFT, 0, C_BORDER, dp(12)));
        serverInput.setPadding(dp(14), 0, dp(14), 0);
        serverCard.addView(serverInput, new LinearLayout.LayoutParams(-1, dp(52)));
        serverCard.addView(primaryButton("Server speichern", v -> saveServerBase()), new LinearLayout.LayoutParams(-1, dp(52)));
        screen.addView(serverCard);

        LinearLayout healthCard = card();
        healthCard.addView(headline("Verbindung"));
        healthCard.addView(body("Prufe, ob VM, OrcaSlicer und Drucker erreichbar sind."));
        healthCard.addView(primaryButton("Verbindung testen", v -> checkHealth()), new LinearLayout.LayoutParams(-1, dp(52)));
        screen.addView(healthCard);

        LinearLayout amsSettings = card();
        amsSettings.addView(headline("Filament & AMS"));
        amsSettings.addView(body("Fremdspulen werden nicht immer korrekt erkannt. Deshalb wird die App spater eigene Slotnamen, Farben und Materialien speichern."));
        screen.addView(amsSettings);

        contentFrame.addView(wrapScroll(screen));
    }

    private void addPlatformChip(LinearLayout parent, String label, String url) {
        Button button = secondaryButton(label, v -> webView.loadUrl(url));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(44));
        params.setMargins(0, 0, dp(8), 0);
        parent.addView(button, params);
    }

    private void attachWorkflowPanel(LinearLayout parent, String title) {
        detach(workflowPanel);
        LinearLayout section = card();
        section.addView(headline(title));
        section.addView(workflowPanel);
        parent.addView(section, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addInfoCard(LinearLayout parent, String title, String text) {
        LinearLayout info = card();
        info.addView(headline(title));
        info.addView(body(text));
        parent.addView(info);
    }

    private LinearLayout metricCard(String label, String value) {
        LinearLayout card = card();
        card.addView(label(label));
        TextView number = headline(value);
        number.setTextSize(24);
        card.addView(number);
        return card;
    }

    private LinearLayout slotCard(String label) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        card.setBackground(cardBackground(C_SURFACE_SOFT, dp(1), C_BORDER, dp(16)));
        TextView swatch = new TextView(this);
        swatch.setText(" ");
        swatch.setBackground(cardBackground(Color.LTGRAY, 0, Color.TRANSPARENT, dp(20)));
        card.addView(swatch, new LinearLayout.LayoutParams(dp(34), dp(34)));
        card.addView(label(label));
        return card;
    }

    private String temperatureText(String type) {
        if (lastPrinterStatus == null) {
            return "--";
        }
        String current = lastPrinterStatus.optString(type + "_temper", "--");
        String target = lastPrinterStatus.optString(type + "_target_temper", "--");
        return current + " / " + target + " C";
    }

    private String readableState(String state) {
        if ("RUNNING".equalsIgnoreCase(state)) return "Druckt";
        if ("IDLE".equalsIgnoreCase(state) || "FINISH".equalsIgnoreCase(state)) return "Bereit";
        if ("PAUSE".equalsIgnoreCase(state)) return "Pausiert";
        if ("FAILED".equalsIgnoreCase(state)) return "Fehler";
        return state;
    }

    private void addScreenTitle(LinearLayout parent, String title, String subtitle) {
        parent.addView(headline(title));
        parent.addView(body(subtitle));
    }

    private LinearLayout screenScrollContent() {
        LinearLayout screen = vertical();
        screen.setPadding(dp(20), dp(10), dp(20), dp(20));
        return screen;
    }

    private ScrollView wrapScroll(LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        return scroll;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, 0, 0, 0);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout card() {
        LinearLayout card = vertical();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(cardBackground(C_SURFACE, dp(1), Color.rgb(230, 234, 238), dp(24)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(10), 0, dp(10));
        card.setLayoutParams(params);
        return card;
    }

    private TextView headline(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(C_TEXT);
        view.setTextSize(20);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(2), 0, dp(6));
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(C_MUTED);
        view.setTextSize(15);
        view.setPadding(0, dp(2), 0, dp(8));
        return view;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(C_MUTED);
        view.setTextSize(12);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView chip(String text, int background, int foreground) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(foreground);
        view.setTextSize(13);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setBackground(cardBackground(background, 0, Color.TRANSPARENT, dp(18)));
        return view;
    }

    private Button primaryButton(String text, View.OnClickListener listener) {
        Button button = baseButton(text, listener);
        button.setTextColor(Color.WHITE);
        button.setBackground(cardBackground(C_PRIMARY, 0, C_PRIMARY, dp(24)));
        return button;
    }

    private Button secondaryButton(String text, View.OnClickListener listener) {
        Button button = baseButton(text, listener);
        button.setTextColor(C_TEXT);
        button.setBackground(cardBackground(C_SURFACE, dp(1), C_BORDER, dp(24)));
        return button;
    }

    private Button dangerButton(String text, View.OnClickListener listener) {
        Button button = baseButton(text, listener);
        button.setTextColor(Color.WHITE);
        button.setBackground(cardBackground(C_DANGER, 0, C_DANGER, dp(24)));
        return button;
    }

    private Button baseButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setOnClickListener(listener);
        return button;
    }

    private GradientDrawable cardBackground(int color, int strokeWidth, int strokeColor, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) {
            drawable.setStroke(strokeWidth, strokeColor);
        }
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void detach(View view) {
        ViewParentCompat.detach(view);
    }

    private static class ViewParentCompat {
        static void detach(View view) {
            if (view == null || view.getParent() == null) {
                return;
            }
            ((ViewGroup) view.getParent()).removeView(view);
        }
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

    private void confirmRemoteDownload(String url, String filename, String mimeType, String userAgent, String cookies) {
        new AlertDialog.Builder(this)
                .setTitle("Auf VM herunterladen?")
                .setMessage(filename + "\n\nDie Datei wird nicht auf dem Handy gespeichert.")
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("VM Download", (dialog, which) -> startRemoteDownload(url, filename, mimeType, userAgent, cookies))
                .show();
    }

    private String serverBase() {
        String value = serverInput == null ? loadServerBase() : serverInput.getText().toString().trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String loadServerBase() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_SERVER_BASE, DEFAULT_SERVER_BASE);
    }

    private void saveServerBase() {
        String value = serverBase();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_SERVER_BASE, value).apply();
        setStatus("Server gespeichert: " + value);
    }

    private void setStatus(String text) {
        mainHandler.post(() -> statusText.setText(text));
    }

    private void checkHealth() {
        setStatus("Verbindung wird gepruft...");
        executor.submit(() -> {
            try {
                JSONObject response = getJson("/health");
                boolean slicer = response.optBoolean("orcaslicer_configured");
                boolean printer = response.optBoolean("printer_configured");
                setStatus("VM erreichbar / OrcaSlicer: " + okText(slicer) + " / Drucker: " + okText(printer));
                mainHandler.post(() -> connectionBadge.setText("Verbunden"));
            } catch (Exception e) {
                mainHandler.post(() -> connectionBadge.setText("Offline"));
                setStatus("Verbindung fehlgeschlagen: " + e.getMessage());
            }
        });
    }

    private String okText(boolean ok) {
        return ok ? "bereit" : "nicht bereit";
    }

    private void checkPrinterStatus() {
        setStatus("Druckerstatus wird abgefragt...");
        executor.submit(() -> {
            try {
                JSONObject response = getJson("/api/v1/printer/status");
                lastPrinterStatus = response.getJSONObject("status");
                setStatus(formatPrinterStatus(lastPrinterStatus));
                if ("control".equals(currentTab)) {
                    mainHandler.post(this::renderControlSafely);
                }
            } catch (Exception e) {
                setStatus("Druckerstatus fehlgeschlagen: " + e.getMessage());
            }
        });
    }

    private void renderControlSafely() {
        if ("control".equals(currentTab)) {
            selectTab("control");
        }
    }

    private String formatPrinterStatus(JSONObject printerStatus) {
        String state = readableState(printerStatus.optString("gcode_state", "unbekannt"));
        String percent = printerStatus.has("mc_percent") ? printerStatus.optString("mc_percent") + "%" : "";
        String remaining = printerStatus.has("mc_remaining_time") ? printerStatus.optString("mc_remaining_time") + " min" : "";
        String file = printerStatus.optString("gcode_file", "");
        StringBuilder text = new StringBuilder("Drucker: ").append(state);
        if (!percent.isEmpty()) text.append(" / ").append(percent);
        if (!remaining.isEmpty()) text.append(" / ").append(remaining);
        if (!file.isEmpty()) text.append(" / ").append(file);
        return text.toString();
    }

    private void confirmCancelPrint() {
        new AlertDialog.Builder(this)
                .setTitle("Druck abbrechen?")
                .setMessage("Der aktuelle Druckauftrag wird am Bambu A1 gestoppt.")
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Stop", (dialog, which) -> cancelPrint())
                .show();
    }

    private void cancelPrint() {
        setStatus("Druckabbruch wird gesendet...");
        executor.submit(() -> {
            try {
                JSONObject response = postJson("/api/v1/printer/cancel", new JSONObject());
                JSONObject printerStatus = response.optJSONObject("status");
                setStatus(printerStatus == null ? "Druckabbruch gesendet." : "Druckabbruch gesendet: " + formatPrinterStatus(printerStatus));
            } catch (Exception e) {
                setStatus("Druckabbruch fehlgeschlagen: " + e.getMessage());
            }
        });
    }

    private void startRemoteDownload(String url, String filename, String mimeType, String userAgent, String cookies) {
        setStatus("Download wird auf der VM gestartet...");
        workflowPanel.removeAllViews();
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
        workflowPanel.removeAllViews();
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
            setStatus("Keine unterstutzte Modell-/Projektdatei gefunden: " + inspection.optString("kind"));
            return;
        }
        setStatus("Gefundene Dateien: " + entries.length());
        String kind = inspection.optString("kind");
        mainHandler.post(() -> {
            renderImportEntries(kind, entries);
            selectTab("project");
        });
    }

    private void renderImportEntries(String kind, JSONArray entries) {
        workflowPanel.removeAllViews();
        workflowPanel.addView(body("Wahle aus, welche Datei importiert werden soll."));
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            String path = "archive".equals(kind) ? entry.optString("path") : null;
            String label = entry.optString("filename") + " (" + entry.optString("import_type") + ")";
            workflowPanel.addView(primaryButton("Importieren: " + label, v -> importEntry(path)), new LinearLayout.LayoutParams(-1, dp(52)));
        }
    }

    private void importEntry(String entryPath) {
        setStatus("Import auf VM lauft...");
        executor.submit(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("download_id", lastDownloadId);
                if (entryPath != null && !entryPath.isEmpty()) {
                    body.put("entry_path", entryPath);
                }
                JSONObject response = postJson("/api/v1/imports", body);
                JSONObject model = response.getJSONObject("imported_model");
                lastImportedModelId = model.getString("id");
                setStatus("Importiert: " + model.getString("filename"));
                mainHandler.post(() -> {
                    renderImportedModelActions(model);
                    selectTab("project");
                });
            } catch (Exception e) {
                setStatus("Import fehlgeschlagen: " + e.getMessage());
            }
        });
    }

    private void renderImportedModelActions(JSONObject model) {
        workflowPanel.removeAllViews();
        addInfoCard(workflowPanel, "Importiert", model.optString("filename"));
        workflowPanel.addView(primaryButton("Druck vorbereiten", v -> startSliceForImportedModel()), new LinearLayout.LayoutParams(-1, dp(52)));
    }

    private void startSliceForImportedModel() {
        if (lastImportedModelId == null || lastImportedModelId.isEmpty()) {
            setStatus("Kein importiertes Modell ausgewahlt.");
            return;
        }
        setStatus("Slice-Job wird auf der VM gestartet...");
        executor.submit(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("cli_args", new JSONArray());
                JSONObject response = postJson("/api/v1/imports/" + lastImportedModelId + "/slice", body);
                lastJobId = response.getJSONObject("job").getString("id");
                pollSliceJob();
            } catch (Exception e) {
                setStatus("Slice konnte nicht gestartet werden: " + e.getMessage());
            }
        });
    }

    private void pollSliceJob() throws Exception {
        for (int i = 0; i < 360; i++) {
            JSONObject response = getJson("/api/v1/jobs/" + lastJobId);
            JSONObject job = response.getJSONObject("job");
            String status = job.getString("status");
            setStatus("Slice: " + status);
            if ("succeeded".equals(status)) {
                setStatus("Slice fertig: " + job.optString("output_name"));
                mainHandler.post(() -> renderSlicedJobActions(job));
                return;
            }
            if ("failed".equals(status)) {
                setStatus("Slice fehlgeschlagen: " + job.optString("error"));
                return;
            }
            Thread.sleep(1000);
        }
        setStatus("Slice dauert zu lange.");
    }

    private void renderSlicedJobActions(JSONObject job) {
        workflowPanel.removeAllViews();
        addInfoCard(workflowPanel, "Druckdatei berechnet", job.optString("output_name"));
        workflowPanel.addView(primaryButton("Druckdatei vorbereiten", v -> preparePrintFile()), new LinearLayout.LayoutParams(-1, dp(52)));
        if ("project".equals(currentTab)) selectTab("project");
    }

    private void preparePrintFile() {
        if (lastJobId == null || lastJobId.isEmpty()) {
            setStatus("Kein Slice-Job verfugbar.");
            return;
        }
        setStatus("Druckdatei wird vorbereitet...");
        executor.submit(() -> {
            try {
                JSONObject response = postJson("/api/v1/jobs/" + lastJobId + "/prepare-print", new JSONObject());
                JSONObject prepared = response.getJSONObject("prepared_print");
                setStatus("Vorbereitet: " + prepared.getString("filename"));
                mainHandler.post(() -> renderPreparedPrintActions(prepared));
            } catch (Exception e) {
                setStatus("Vorbereiten fehlgeschlagen: " + e.getMessage());
            }
        });
    }

    private void renderPreparedPrintActions(JSONObject prepared) {
        workflowPanel.removeAllViews();
        addInfoCard(workflowPanel, "Bereit zum Senden", prepared.optString("filename"));
        workflowPanel.addView(primaryButton("An Drucker senden", v -> uploadPrintFile()), new LinearLayout.LayoutParams(-1, dp(52)));
        if ("project".equals(currentTab)) selectTab("project");
    }

    private void uploadPrintFile() {
        if (lastJobId == null || lastJobId.isEmpty()) {
            setStatus("Kein Slice-Job verfugbar.");
            return;
        }
        setStatus("Upload zum Drucker lauft...");
        executor.submit(() -> {
            try {
                JSONObject response = postJson("/api/v1/jobs/" + lastJobId + "/upload-print", new JSONObject());
                JSONObject prepared = response.getJSONObject("prepared_print");
                setStatus("Hochgeladen: " + prepared.optString("uploaded_filename"));
                mainHandler.post(() -> renderUploadedPrintActions(prepared));
            } catch (Exception e) {
                setStatus("Upload fehlgeschlagen: " + e.getMessage());
            }
        });
    }

    private void renderUploadedPrintActions(JSONObject prepared) {
        workflowPanel.removeAllViews();
        addInfoCard(workflowPanel, "Auf Drucker gesendet", prepared.optString("uploaded_filename"));
        workflowPanel.addView(secondaryButton("Druckerstatus", v -> checkPrinterStatus()), new LinearLayout.LayoutParams(-1, dp(52)));
        workflowPanel.addView(primaryButton("Druck starten", v -> confirmStartPrint()), new LinearLayout.LayoutParams(-1, dp(52)));
        workflowPanel.addView(dangerButton("Druck abbrechen", v -> confirmCancelPrint()), new LinearLayout.LayoutParams(-1, dp(52)));
        if ("project".equals(currentTab)) selectTab("project");
    }

    private void confirmStartPrint() {
        setStatus("Druckerstatus wird vor Start gepruft...");
        executor.submit(() -> {
            try {
                JSONObject response = getJson("/api/v1/printer/status");
                JSONObject printerStatus = response.getJSONObject("status");
                String message = formatPrinterStatus(printerStatus) + "\n\nDer Druck wird auf dem Bambu A1 gestartet.";
                mainHandler.post(() -> showStartPrintDialog(message));
            } catch (Exception e) {
                String message = "Druckerstatus konnte nicht gelesen werden:\n" + e.getMessage() + "\n\nTrotzdem starten?";
                mainHandler.post(() -> showStartPrintDialog(message));
            }
        });
    }

    private void showStartPrintDialog(String message) {
        new AlertDialog.Builder(this)
                .setTitle("Druck starten?")
                .setMessage(message)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Starten", (dialog, which) -> startPrint())
                .show();
    }

    private void startPrint() {
        if (lastJobId == null || lastJobId.isEmpty()) {
            setStatus("Kein Slice-Job verfugbar.");
            return;
        }
        setStatus("Druckstart wird gesendet...");
        executor.submit(() -> {
            try {
                JSONObject response = postJson("/api/v1/jobs/" + lastJobId + "/start-print", new JSONObject());
                JSONObject status = response.optJSONObject("status");
                setStatus(status == null ? "Druckstart gesendet." : "Druckstart gesendet: " + status.optString("gcode_state"));
                mainHandler.post(() -> selectTab("control"));
            } catch (Exception e) {
                setStatus("Druckstart fehlgeschlagen: " + e.getMessage());
            }
        });
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
        if ("browse".equals(currentTab) && webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        if (!"browse".equals(currentTab)) {
            selectTab("browse");
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
