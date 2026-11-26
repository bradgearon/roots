package com.wds.sixstars.contextoverlay;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.wds.sixstars.contextoverlay.tree.TreeActivity;
import com.wds.sixstars.contextoverlay.tree.TreeRuntime;

/**
 * Explicit in-app disclosure and settings; self-contained so the library can be
 * moved to another host application without a Roots UI dependency.
 */
public final class OverlayTranslationSettingsActivity extends Activity {
    private OverlayPreferences prefs;
    private TextView status;
    private TextView diagnostics;
    private TextView preview;
    private EditText bridgeAddress;
    private EditText translationKey;
    private CheckBox desktopTranslation;
    private RadioGroup translationMode;
    private Spinner inlineAppearance;
    private int manualModeId;
    private int autoModeId;
    private Spinner targetLanguage;

    public static Intent openIntent(Context context) {
        return new Intent(context, OverlayTranslationSettingsActivity.class);
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = new OverlayPreferences(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(28), dp(20), dp(24));
        content.setBackgroundColor(0xFF0C1924);
        scroll.addView(content);

        TextView title = text("X Translation Overlay", 23, 0xFFB8FBE7);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(title);
        content.addView(text("Local PLaMo 3 · portable Roots module", 13, 0xFF90C7CE));

        content.addView(text("Accessibility permission disclosure", 18, 0xFFFFFFFF));
        TextView disclosure = text(
            "If enabled, Roots can read text and on-screen positions exposed by the X (Twitter) Android app while it is in the foreground. " +
            "This may include sensitive text shown inside X, including private messages. Small translation badges are drawn over the X screen using an Android accessibility overlay. " +
            "The feature does not take screenshots, press buttons in X, read other apps' text, or persist translation input. The optional Tree feature separately asks permission to retain local timeline fragments. " +
            "With desktop translation enabled, the text you tap may be sent to your configured Roots desktop over the local network for PLaMo inference. " +
            "If automatic translation is also enabled, eligible visible X text may be sent automatically. The X content could include private messages. " +
            "Plain HTTP does not encrypt text or the access key; use only a trusted home Wi-Fi network, or an HTTPS/VPN bridge. " +
            "By default translation runs on this device. Nothing is sent unless you separately enable desktop translation. " +
            "You can stop processing below, and disable the service at any time in Android Accessibility settings.",
            14, 0xFFE5EFF4);
        disclosure.setPadding(0, dp(8), 0, dp(14));
        content.addView(disclosure);

        status = text("", 14, 0xFFB9F6CA);
        content.addView(status);

        content.addView(text("X accessibility scan diagnostics", 17, 0xFFFFFFFF));
        diagnostics = text("", 13, 0xFF9BCAD8);
        content.addView(diagnostics);
        Button refreshDiagnostics = button("Refresh scan diagnostics");
        refreshDiagnostics.setOnClickListener(v -> refreshStatus());
        content.addView(refreshDiagnostics);

        Button consent = button("I consent — open Accessibility settings");
        consent.setOnClickListener(v -> showConsentDialog());
        content.addView(consent);

        Button stop = button("Stop X text processing");
        stop.setOnClickListener(v -> {
            prefs.setEnabled(false);
            TreeRuntime.get(this).setEnabled(false);
            prefs.setDesktopTranslationEnabled(false);
            prefs.setAutoPreview(false);
            TranslationRuntime.disconnect();
            if (desktopTranslation != null) desktopTranslation.setChecked(false);
            if (translationMode != null) translationMode.check(manualModeId);
            refreshStatus();
        });
        content.addView(stop);

        Button tree = button("Open Tree of Life — local search and capture");
        tree.setOnClickListener(v -> startActivity(new Intent(this, TreeActivity.class)));
        content.addView(tree);

        Button download = button("Download local PLaMo model (~550 MB)");
        download.setOnClickListener(v -> LocalPLaMoTranslationProvider.downloadModel(this, (message, error) -> runOnUiThread(() -> new AlertDialog.Builder(this).setMessage(error == null ? message : error).setPositiveButton("OK", null).show())));
        content.addView(download);
        content.addView(text("Desktop PLaMo connection", 18, 0xFFFFFFFF));
        content.addView(text("Desktop bridge address (http://YOUR-PC:8123)", 13, 0xFFAAC8D4));
        bridgeAddress = new EditText(this);
        bridgeAddress.setSingleLine(true);
        bridgeAddress.setText(prefs.bridgeUrl());
        bridgeAddress.setTextColor(Color.WHITE);
        bridgeAddress.setHint("http://192.168.1.100:8123");
        bridgeAddress.setHintTextColor(0xFF8AADB6);
        bridgeAddress.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        content.addView(bridgeAddress);

        content.addView(text("Desktop translation access key", 13, 0xFFAAC8D4));
        translationKey = new EditText(this);
        translationKey.setSingleLine(true);
        translationKey.setHint(OverlayTranslationKeyStore.isConfigured(this)
                ? "Key saved securely — leave blank to keep" : "Paste 64-character key from your desktop");
        translationKey.setHintTextColor(0xFF8AADB6);
        translationKey.setTextColor(Color.WHITE);
        translationKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(translationKey);

        Button saveKey = button("Save translation connection");
        saveKey.setOnClickListener(v -> saveTranslationConnection());
        content.addView(saveKey);

        Button clearKey = button("Clear translation key");
        clearKey.setOnClickListener(v -> {
            OverlayTranslationKeyStore.clear(this);
            prefs.setDesktopTranslationEnabled(false);
            TranslationRuntime.disconnect();
            if (desktopTranslation != null) desktopTranslation.setChecked(false);
            prefs.setAutoPreview(false);
            if (translationMode != null) translationMode.check(manualModeId);
            translationKey.setText("");
            refreshStatus();
        });
        content.addView(clearKey);

        content.addView(text("Translate into", 13, 0xFFAAC8D4));
        targetLanguage = new Spinner(this);
        String[] languages = { "English", "Japanese", "Spanish", "French", "German" };
        String[] codes = { "en", "ja", "es", "fr", "de" };
        ArrayAdapter<String> languagesAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, languages);
        targetLanguage.setAdapter(languagesAdapter);
        String configured = prefs.targetLanguage();
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(configured)) targetLanguage.setSelection(i);
        }
        content.addView(targetLanguage);

        desktopTranslation = new CheckBox(this);
        desktopTranslation.setText("Use desktop host instead of on-device PLaMo");
        desktopTranslation.setTextColor(0xFFE5EFF4);
        desktopTranslation.setChecked(prefs.desktopTranslationEnabled());
        desktopTranslation.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) {
                prefs.setDesktopTranslationEnabled(false);
                TranslationRuntime.disconnect();
                refreshStatus();
                return;
            }
            if (prefs.desktopTranslationEnabled()) {
                refreshStatus();
                return;
            }
            button.setChecked(false);
            if (!OverlayTranslationKeyStore.isConfigured(this)) {
                status.setText("Save the desktop translation key first.");
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Allow sending X text to your desktop?")
                    .setMessage("When you tap a Roots translation badge, its visible X text will be sent to the configured desktop bridge for PLaMo translation. " +
                            "If automatic mode is enabled, selected visible X text may be sent without tapping. " +
                            "Content may include sensitive X posts or private messages. HTTP on Wi-Fi is not encrypted. " +
                            "Use only a trusted network or HTTPS/VPN. You can turn this off at any time. Continue?")
                    .setNegativeButton("Not now", null)
                    .setPositiveButton("I consent to desktop translation", (dialog, which) -> {
                        prefs.setDesktopTranslationEnabled(true);
                        desktopTranslation.setChecked(true);
                        refreshStatus();
                    })
                    .show();
        });
        content.addView(desktopTranslation);

        content.addView(text("Translation display mode", 18, 0xFFFFFFFF));
        translationMode = new RadioGroup(this);
        translationMode.setOrientation(RadioGroup.VERTICAL);
        manualModeId = View.generateViewId();
        autoModeId = View.generateViewId();

        RadioButton manual = new RadioButton(this);
        manual.setId(manualModeId);
        manual.setText("Manual — one consistent ⇄ button per post");
        manual.setTextColor(0xFFE5EFF4);
        translationMode.addView(manual);

        RadioButton automatic = new RadioButton(this);
        automatic.setId(autoModeId);
        automatic.setText("Automatic (experimental) — replace post text as I scroll");
        automatic.setTextColor(0xFFE5EFF4);
        translationMode.addView(automatic);

        translationMode.check(prefs.autoPreview() ? autoModeId : manualModeId);
        translationMode.setOnCheckedChangeListener((group, selectedId) -> {
            if (selectedId == autoModeId && !prefs.translationEnabled()) {
                group.check(manualModeId);
                status.setText("Grant X text processing consent before using Automatic mode.");
                return;
            }
            prefs.setAutoPreview(selectedId == autoModeId);
            refreshStatus();
        });
        content.addView(translationMode);
        content.addView(text("Auto inline appearance (match your X theme)", 13, 0xFFAAC8D4));
        inlineAppearance = new Spinner(this);
        String[] appearanceLabels = { "Follow Android system", "X Black", "X Dim", "X Light" };
        String[] appearanceValues = {
                AutoInlineThemeService.SYSTEM, AutoInlineThemeService.BLACK,
                AutoInlineThemeService.DIM, AutoInlineThemeService.LIGHT
        };
        ArrayAdapter<String> appearanceAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, appearanceLabels);
        appearanceAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        inlineAppearance.setAdapter(appearanceAdapter);
        for (int i = 0; i < appearanceValues.length; i++) {
            if (appearanceValues[i].equals(prefs.inlineTheme()))
                inlineAppearance.setSelection(i);
        }
        inlineAppearance.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                    int position, long id) {
                prefs.setInlineTheme(appearanceValues[position]);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        content.addView(inlineAppearance);
        content.addView(text(
            "Automatic mode places touch-through translated text over the original " +
            "post body, without adding a card below it. The small 原 / language button " +
            "switches the original and translated versions. Likes, media, and post " +
            "actions remain accessible. Longer translations are shortened to fit " +
            "the original text area. Manual mode keeps the existing ⇄ controls.",
            12, 0xFFAAC8D4));

        Button system = button("Open Accessibility settings");
        system.setOnClickListener(v -> openAndroidSettings());
        content.addView(system);

        content.addView(text("PLaMo translation connection test", 18, 0xFFFFFFFF));
        Button sample = button("Test actual PLaMo translation");
        content.addView(sample);
        preview = text("Sample: Bonjour à tous!", 14, 0xFFE5EFF4);
        content.addView(preview);
        sample.setOnClickListener(v -> {
            preview.setText("Translating with PLaMo…");
            TranslationRuntime.provider(this).translate("Bonjour à tous!", "fr", prefs.targetLanguage(),
                (result, error) -> runOnUiThread(() -> preview.setText(
                    (error == null ? "PLaMo: " + result : "Cannot translate: " + error) +
                    "\\nTransport: " + TranslationRuntime.transportStatus())));
        });

        content.addView(text(
            "In Manual mode, tap the ⇄ button positioned next to each post body. " +
            "In Automatic mode, translated text appears directly over the original post text, " +
            "with touches passed through to X. Only the language toggle intercepts taps. " +
            "Text disappears when the original post moves off-screen or you leave X.",
            13, 0xFFAAC8D4));
        setContentView(scroll);
        refreshStatus();
    }

    private void saveTranslationConnection() {
        try {
            prefs.setBridgeUrl(bridgeAddress.getText().toString());
            String typed = translationKey.getText().toString().trim();
            if (!typed.isEmpty()) {
                OverlayTranslationKeyStore.save(this, typed);
                translationKey.setText("");
                translationKey.setHint("Key saved securely — leave blank to keep");
            }
            String[] codes = { "en", "ja", "es", "fr", "de" };
            prefs.setTargetLanguage(codes[targetLanguage.getSelectedItemPosition()]);
            TranslationRuntime.disconnect(); // key, URL or language may have changed
            refreshStatus();
            status.setText(status.getText() + "\nTranslation connection saved.");
        } catch (Exception error) {
            status.setText("Could not save translation settings: " + error.getMessage());
        }
    }

    private void showConsentDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Allow X screen text reading?")
            .setMessage("Roots will inspect visible X text and screen coordinates and show an overlay. X may expose private or sensitive text. " +
                "Only accessible X text is read. No text is transmitted to the desktop until you also enable the separate desktop translation consent. " +
                "Do you consent to reading visible X text for translation?")
            .setNegativeButton("Not now", null)
            .setPositiveButton("I consent", (dialog, which) -> {
                prefs.setEnabled(true);
                openAndroidSettings();
            })
            .show();
    }

    private void openAndroidSettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        if (status == null || prefs == null) return;
        boolean on = prefs.enabled();
        boolean granted = accessibilityEnabled();
        String guidance;
        if (granted && !on) {
            guidance = "\nAccessibility is ON, but Roots consent is OFF. "
                    + "Tap 'I consent' below to allow X text scanning.";
        } else if (on && !granted) {
            guidance = "\nEnable the Roots X Translation Overlay service in Android Accessibility settings. "
                    + "The floating accessibility shortcut alone is not the translation badge.";
        } else if (on) {
            guidance = "\nBoth permissions are enabled. Open X, then return here and refresh diagnostics.";
        } else {
            guidance = "\nFirst grant consent here, then enable the service in Android settings.";
        }
        status.setText("In-app consent: " + (on ? "ON" : "OFF") +
                " | Android service: " + (granted ? "ON" : "OFF") +
                guidance + "\nDesktop translation permission: " +
                (prefs.desktopTranslationEnabled() ? "ON" : "OFF") +
                " · key: " + (OverlayTranslationKeyStore.isConfigured(this) ? "saved" : "missing") +
                "\nDisplay mode: " + (prefs.autoPreview()
                        ? "Automatic — show translations while scrolling"
                        : "Manual — tap ⇄ to translate") +
                "\nBridge: " + prefs.bridgeUrl() +
                "\nTransport: " + TranslationRuntime.transportStatus());
        if (diagnostics != null) {
            diagnostics.setText(OverlayDiagnostics.summary(this));
        }
    }

    private boolean accessibilityEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        for (AccessibilityServiceInfo info :
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getId() != null &&
                    info.getId().contains("XTranslationAccessibilityService")) return true;
        }
        return false;
    }

    private TextView text(String content, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextColor(color);
        view.setTextSize(sp);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF206F75));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        layout.bottomMargin = dp(8);
        button.setLayoutParams(layout);
        return button;
    }

    private int dp(int px) {
        return Math.round(px * getResources().getDisplayMetrics().density);
    }
}
