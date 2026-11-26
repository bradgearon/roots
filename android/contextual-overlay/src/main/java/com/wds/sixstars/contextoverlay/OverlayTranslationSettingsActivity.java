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

        LinearLayout shell=RootsUi.shell(this);
        shell.addView(RootsUi.header(this,"Settings","Roots · translation and knowledge"));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout sections = RootsUi.column(this);
        sections.setPadding(dp(16),dp(8),dp(16),dp(20));
        scroll.addView(sections);
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout content=RootsUi.section(this,sections,"Accessibility & permissions",
                "Capture consent and Android service",true);
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

        LinearLayout permissions=content;
        LinearLayout diagnosticSection=RootsUi.section(this,sections,"Diagnostics","Scan and transport status",false);
        content=diagnosticSection;
        diagnostics = text("", 13, 0xFF9BCAD8);
        content.addView(diagnostics);
        Button refreshDiagnostics = button("Refresh scan diagnostics");
        refreshDiagnostics.setOnClickListener(v -> refreshStatus());
        content.addView(refreshDiagnostics);
        content=permissions;

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

        content=RootsUi.section(this,sections,"Knowledge & storage","Local observations, branches and capture",false);
        Button tree = button("Open Tree of Life");
        tree.setOnClickListener(v -> startActivity(new Intent(this, TreeActivity.class)));
        content.addView(tree);

        LinearLayout models=RootsUi.section(this,sections,"Models & inference","On-device model, acceleration and tests",false);
        content=models;
        Button download = button("Download local PLaMo model (~550 MB)");
        download.setOnClickListener(v -> LocalPLaMoTranslationProvider.downloadModel(this, (message, error) -> runOnUiThread(() -> new AlertDialog.Builder(this).setMessage(error == null ? message : error).setPositiveButton("OK", null).show())));
        content.addView(download);
        content=RootsUi.section(this,sections,"Desktop bridge","Connect to your trusted desktop",false);
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

        LinearLayout translation=RootsUi.section(this,sections,"Translation","Language, mode and scheduling",true);
        content=translation;
        content.addView(text("Translate into", 13, 0xFFAAC8D4));
        targetLanguage = new Spinner(this);
        String[] languages = { "English", "Japanese", "Spanish", "French", "German" };
        String[] codes = { "en", "ja", "es", "fr", "de" };
        RootsUi.styleSpinner(this, targetLanguage, languages);
        String configured = prefs.targetLanguage();
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(configured)) targetLanguage.setSelection(i);
        }
        targetLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                if(!codes[position].equals(prefs.targetLanguage())){
                    prefs.setTargetLanguage(codes[position]); refreshStatus();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
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
        content.addView(text("Predictive translation scheduling", 13, 0xFFAAC8D4));
        CheckBox activity = new CheckBox(this);
        activity.setText("Show translation activity dot");
        activity.setTextColor(0xFFE5EFF4);
        activity.setChecked(prefs.showTranslationActivity());
        activity.setOnCheckedChangeListener((button,checked)->prefs.setShowTranslationActivity(checked));
        content.addView(activity);
        content.addView(text("Roots prioritizes readable posts using measured scroll direction and learned backend latency. Names follow viable bodies; recently seen content is a lower-priority rollup at rest. One provider request is outstanding so waiting work can be reprioritized instead of piling up in a FIFO. Manual requests take the next available slot.", 12, 0xFFAAC8D4));
        content=RootsUi.section(this,sections,"Appearance","Match your X theme",false);
        content.addView(text("Inline overlay theme", 13, 0xFFAAC8D4));
        inlineAppearance = new Spinner(this);
        String[] appearanceLabels = { "Follow Android system", "X Black", "X Dim", "X Light" };
        String[] appearanceValues = {
                AutoInlineThemeService.SYSTEM, AutoInlineThemeService.BLACK,
                AutoInlineThemeService.DIM, AutoInlineThemeService.LIGHT
        };
        RootsUi.styleSpinner(this, inlineAppearance, appearanceLabels);
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
            "post body, without adding a card below it. Expansion controls open clipped translations. Likes, media, and post " +
            "actions remain accessible. Longer translations are shortened to fit " +
            "the original text area. Manual mode keeps the existing ⇄ controls.",
            12, 0xFFAAC8D4));

        content=permissions;
        Button system = button("Open Accessibility settings");
        system.setOnClickListener(v -> openAndroidSettings());
        content.addView(system);

        content=models;
        content.addView(text("Local inference acceleration", 16, 0xFFFFFFFF));
        boolean vulkanBuilt=LocalPLaMoTranslationProvider.vulkanBuilt();
        CheckBox vulkan = new CheckBox(this);
        vulkan.setText(vulkanBuilt ? "Vulkan GPU (experimental; compare latency and output)" : "Vulkan GPU (not included in this APK)");
        vulkan.setTextColor(0xFFE5EFF4);
        vulkan.setEnabled(vulkanBuilt);
        if(!vulkanBuilt && prefs.localVulkanEnabled()) prefs.setLocalVulkanEnabled(false);
        vulkan.setChecked(prefs.localVulkanEnabled());
        vulkan.setOnCheckedChangeListener((button,checked)->prefs.setLocalVulkanEnabled(checked));
        content.addView(vulkan);
        content.addView(text("CPU thread count (also used for GPU fallback operations)", 13, 0xFFAAC8D4));
        Spinner threads = new Spinner(this);
        int[] threadValues={0,2,4,6,8};
        String[] threadLabels={"Auto (up to 6)", "2 threads", "4 threads", "6 threads", "8 threads"};
        RootsUi.styleSpinner(this, threads, threadLabels);
        for(int i=0;i<threadValues.length;i++)if(threadValues[i]==prefs.localThreadSetting())threads.setSelection(i);
        threads.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){prefs.setLocalThreadSetting(threadValues[position]);}
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
        content.addView(threads);
        content.addView(text("ARM-optimized CPU kernels are enabled in the standard ARM64 build. Use the same sample twice or three times per setting; exclude first model load, and check the output. GPU is opt-in because driver compatibility and speed vary.",12,0xFFAAC8D4));

        content.addView(text("PLaMo translation connection test", 18, 0xFFFFFFFF));
        Button sample = button("Test actual PLaMo translation");
        content.addView(sample);
        preview = text("Sample: Bonjour à tous!", 14, 0xFFE5EFF4);
        content.addView(preview);
        sample.setOnClickListener(v -> {
            preview.setText("Translating with PLaMo…");
            sample.setEnabled(false);
            long start=android.os.SystemClock.elapsedRealtime();
            boolean local=!prefs.desktopTranslationEnabled();
            String acceleration=local?(prefs.localVulkanEnabled()?"Vulkan requested":"CPU")+" / "+prefs.localThreads()+" threads":"Desktop";
            TranslationRuntime.provider(this).translate("Bonjour à tous!", "fr", prefs.targetLanguage(),
                new TranslationProvider.Result(){
                @Override public void partial(String text){runOnUiThread(()->{if(!isDestroyed())StreamText.reveal(preview,text);});}
                @Override public void completed(String result,String error){runOnUiThread(() -> {
                    if(isDestroyed())return;
                    sample.setEnabled(true);
                    preview.setText((error == null ? "PLaMo: " + result : "Cannot translate: " + error) +
                        "\nTransport: " + TranslationRuntime.transportStatus() + "\n" + acceleration + "\nElapsed: " + (android.os.SystemClock.elapsedRealtime()-start) + " ms");
                });}
                });
        });

        content=RootsUi.section(this,sections,"FunctionGemma","Local micro-cognition and operation proposals",false);
        content.addView(text("FunctionGemma 270M selects a bounded operation and proposes arguments. It does not execute tools, modify the Tree, or establish facts. Text entered here is processed on this device; CPU is used independently of the translation GPU setting.",13,0xFFAAC8D4));
        Button functionDownload=button("Download FunctionGemma Q8 (~292 MB)");
        content.addView(functionDownload);
        TextView functionStatus=text("Download the model, then test a request.",14,0xFFE5EFF4);
        functionDownload.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("FunctionGemma model terms")
            .setMessage("Google Gemma terms apply. Review https://ai.google.dev/gemma/terms before downloading. Model weights are downloaded from Hugging Face over Wi-Fi; your requests remain on-device.")
            .setNeutralButton("Review terms",(d,w)->startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(LocalFunctionGemmaProvider.TERMS))))
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Agree and download",(d,w)->LocalFunctionGemmaProvider.download(this,(message,error)->runOnUiThread(()->functionStatus.setText(error==null?message:error))))
            .show());
        EditText functionInput=new EditText(this);
        functionInput.setTextColor(0xFFE5EFF4);
        functionInput.setHintTextColor(0xFFAAC8D4);
        functionInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        functionInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(FunctionGemmaProtocol.MAX_INPUT)});
        functionInput.setMinLines(2);
        functionInput.setText("Examine the semantic displacement: your eyes disturb the sea when it feels too rough.");
        content.addView(functionInput);
        Button functionSample=button("Test FunctionGemma operation proposal");
        content.addView(functionSample);
        functionSample.setOnClickListener(v->{
            functionSample.setEnabled(false);
            functionStatus.setText("Inspecting the request locally…");
            LocalFunctionGemmaProvider.propose(this,functionInput.getText().toString(),new LocalFunctionGemmaProvider.Result(){
                @Override public void partial(String prefix){runOnUiThread(()->{if(!isDestroyed())StreamText.reveal(functionStatus,prefix);});}
                @Override public void completed(String message,String error){runOnUiThread(()->{
                    if(isDestroyed())return;
                    functionSample.setEnabled(true);
                    functionStatus.setText(error==null?message:error);
                });}
            });
        });
        Button functionRelease=button("Release FunctionGemma memory");
        content.addView(functionRelease);
        functionRelease.setOnClickListener(v->LocalFunctionGemmaProvider.release(this,(message,error)->runOnUiThread(()->functionStatus.setText(error==null?message:error))));
        content.addView(functionStatus);
        Button movement=button("Open Poetic Movement lab");
        movement.setOnClickListener(view->startActivity(new Intent(this,PoeticMovementActivity.class)));
        content.addView(movement);

        content.addView(text(
            "In Manual mode, tap the ⇄ button positioned next to each post body. " +
            "In Automatic mode, translated text appears directly over the original post text, " +
            "with touches passed through to X. Only the small expansion controls intercept taps. " +
            "Text disappears when the original post moves off-screen or you leave X.",
            13, 0xFFAAC8D4));
        // Put everyday controls first, leave maintenance and disclosure accessible.
        View translationCard=(View)translation.getParent();
        sections.removeView(translationCard);sections.addView(translationCard,0);
        shell.addView(RootsUi.navigation(this,true));
        RootsUi.styleInputs(this, shell);
        setContentView(shell);
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
                "\nScheduling: predictive, one outstanding request\nBridge: " + prefs.bridgeUrl() +
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
        button.setBackground(RootsUi.rounded(this,0xFF164B50,10,0xFF2A7779));
        button.setPadding(dp(12),dp(10),dp(12),dp(10));
        button.setMinHeight(dp(48));
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
