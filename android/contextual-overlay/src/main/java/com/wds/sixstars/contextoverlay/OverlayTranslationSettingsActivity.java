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
        shell.addView(RootsUi.action(this,"Live inference history",()->startActivity(new Intent(this,InferenceHistoryActivity.class))));
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
            "Automatic routing uses a TLS WebSocket with the paired desktop certificate. An outdated desktop bridge is not used. " +
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
        Button inspectPost=button("X timeline inspector");
        inspectPost.setOnClickListener(v->showPostInspector());
        content.addView(inspectPost);
        content=permissions;

        Button consent = button("I consent — open Accessibility settings");
        consent.setOnClickListener(v -> showConsentDialog());
        content.addView(consent);

        Button stop = button("Stop X text processing");
        stop.setOnClickListener(v -> {
            prefs.setEnabled(false);
            XAccessibilityInspector.cancel();
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
        content=RootsUi.section(this,sections,"Desktop bridge","Pair a desktop and remember familiar networks",false);
        content.addView(text("Desktop bridge address (https://YOUR-PC:8223)", 13, 0xFFAAC8D4));
        bridgeAddress = new EditText(this);
        bridgeAddress.setSingleLine(true);
        bridgeAddress.setText(prefs.bridgeUrl());
        bridgeAddress.setTextColor(Color.WHITE);
        bridgeAddress.setHint("https://192.168.1.100:8223");
        bridgeAddress.setHintTextColor(0xFF8AADB6);
        bridgeAddress.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        content.addView(bridgeAddress);
        Button remember=button("Remember current network for desktop");
        remember.setOnClickListener(v->{
            try{
                prefs.setBridgeUrl(bridgeAddress.getText().toString());
                TranslationRuntime.disconnect();
                boolean ok=FastestComputeRouter.rememberCurrent(this);
                refreshStatus();
                status.setText(status.getText()+(ok?"\nCurrent network remembered.":"\nPair a desktop and connect to Wi-Fi first."));
            }catch(IllegalArgumentException e){status.setText(e.getMessage());}
        });
        content.addView(remember);
        content.addView(text("Run get-key.cmd on the desktop to copy the key and TLS certificate fingerprint together. Paste once below.", 13, 0xFFAAC8D4));
        translationKey = new EditText(this);
        translationKey.setSingleLine(true);
        translationKey.setHint(OverlayTranslationKeyStore.isConfigured(this)
                ? "Key saved securely — leave blank to keep" : "Run get-key.cmd on desktop, paste key");
        translationKey.setHintTextColor(0xFF8AADB6);
        translationKey.setTextColor(Color.WHITE);
        translationKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(translationKey);

        Button saveKey = button("Save translation connection");
        saveKey.setOnClickListener(v -> saveTranslationConnection());
        content.addView(saveKey);
        TextView desktopTestStatus=text("Tests only the desktop, with a fixed sample. Never falls back to phone.",13,0xFFAAC8D4);
        Button testDesktop=button("Test desktop only");
        content.addView(testDesktop);
        content.addView(desktopTestStatus);
        testDesktop.setOnClickListener(v->{
            // Do not silently test an older saved destination when an edit is pending.
            String entered=bridgeAddress.getText().toString().trim().replaceAll("/+$","").replaceFirst("^http://","https://");
            if(!entered.equals(prefs.bridgeUrl())||!translationKey.getText().toString().trim().isEmpty()){
                desktopTestStatus.setText("Save translation connection first, then test "+entered);
                return;
            }
            testDesktop.setEnabled(false);desktopTestStatus.setText("Sending sample to "+prefs.bridgeUrl()+"…");
            FastestComputeRouter.testDesktop(this,(message,error)->runOnUiThread(()->{
                if(isDestroyed())return;
                testDesktop.setEnabled(true);desktopTestStatus.setText(error==null?message:error);
                refreshStatus();
            }));
        });

        Button clearKey = button("Clear translation key");
        clearKey.setOnClickListener(v -> {
            OverlayTranslationKeyStore.clear(this);
            FastestComputeRouter.forgetNetworks(this);
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
        content.addView(text("Translate into (43 languages)", 13, 0xFFAAC8D4));
        targetLanguage = new Spinner(this);
        String[] languages = TranslationLanguages.settingLabels("Follow Android language");
        String[] codes = TranslationLanguages.settingCodes();
        RootsUi.styleSpinner(this, targetLanguage, languages);
        String configured = prefs.targetLanguageSetting();
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(configured)) targetLanguage.setSelection(i);
        }
        targetLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                if(!codes[position].equals(prefs.targetLanguageSetting())){
                    prefs.setTargetLanguage(codes[position]); if(com.wds.sixstars.contextoverlay.tree.NativeTree.available())TreeRuntime.get(OverlayTranslationSettingsActivity.this).languageChanged(); refreshStatus();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
        content.addView(targetLanguage);

        desktopTranslation = new CheckBox(this);
        desktopTranslation.setText("Automatically use fastest paired compute (phone or desktop)");
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
                    .setMessage("Roots may send selected visible X text to your paired desktop when it is available and faster, including in automatic mode. " +
                            "Content may include sensitive posts or private messages. Requests are encrypted with the paired key. " +
                            "The phone remains available as a fallback. No location or Wi-Fi name is required. Continue?")
                    .setNegativeButton("Not now", null)
                    .setPositiveButton("I consent to desktop translation", (dialog, which) -> {
                        prefs.setDesktopTranslationEnabled(true);
                        TranslationRuntime.disconnect();
                        TranslationRuntime.startCompute(this);
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
            String acceleration=prefs.desktopTranslationEnabled()?"Automatic paired compute":
                    (prefs.localVulkanEnabled()?"Vulkan requested":"CPU")+" / "+prefs.localThreads()+" threads";
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

        content=RootsUi.section(this,sections,"Intelligence","General reasoning · phone or paired desktop",true);
        content.addView(text("Qwen3.5 0.8B is the default. Phone models load on request, release idle context after 30 seconds and unload weights after two minutes. Translation remains independent.",13,0xFFAAC8D4));
        Spinner intelligence=new Spinner(this);
        RootsUi.styleSpinner(this,intelligence,IntelligenceModel.labels());
        intelligence.setSelection(IntelligenceModel.selected(this).ordinal());
        content.addView(intelligence);
        TextView intelligenceStatus=text(LocalIntelligenceProvider.status(this,IntelligenceModel.selected(this)),14,0xFFE5EFF4);
        content.addView(text("Desktop intelligence uses the private Internal Mesh, not public Axons. Run launch-intelligence-remote.cmd for away-from-home use or launch-intelligence.cmd for LAN; pair using get-intelligence-key.cmd. When enabled, your general intelligence requests, Mesh diagnosis reports and approved Axon reference notes are sent to that paired PC. Poetic Movement and element-flow profiling continue on the phone.",13,0xFFAAC8D4));
        CheckBox desktopIntelligence=new CheckBox(this);
        desktopIntelligence.setText("Use desktop intelligence");desktopIntelligence.setTextColor(0xFFE5EFF4);
        desktopIntelligence.setChecked(prefs.desktopIntelligenceEnabled());content.addView(desktopIntelligence);
        EditText intelligenceAddress=new EditText(this);intelligenceAddress.setSingleLine(true);
        intelligenceAddress.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        intelligenceAddress.setHint("roots+iroh://… or HTTPS on port 8221");intelligenceAddress.setText(prefs.desktopIntelligenceUrl());content.addView(intelligenceAddress);
        EditText intelligenceKey=new EditText(this);intelligenceKey.setSingleLine(true);intelligenceKey.setSaveEnabled(false);
        intelligenceKey.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if(android.os.Build.VERSION.SDK_INT>=26)intelligenceKey.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        intelligenceKey.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(129)});
        intelligenceKey.setHint(DesktopIntelligenceKeyStore.configured(this)?"Intelligence key saved — leave blank to keep":"Paste key from get-intelligence-key.cmd");content.addView(intelligenceKey);
        desktopIntelligence.setOnCheckedChangeListener((button,checked)->{
            if(checked&&(prefs.desktopIntelligenceUrl().isEmpty()||!DesktopIntelligenceKeyStore.configured(this))){
                desktopIntelligence.setChecked(false);intelligenceStatus.setText("Save the PC intelligence address and its connection key first.");return;
            }
            prefs.setDesktopIntelligenceEnabled(checked);
            intelligenceStatus.setText(LocalIntelligenceProvider.status(this,IntelligenceModel.selected(this)));
        });
        Button saveIntelligence=button("Save intelligence connection");content.addView(saveIntelligence);
        saveIntelligence.setOnClickListener(v->{
            try{
                String base=DesktopIntelligenceProtocol.baseUrl(intelligenceAddress.getText().toString());
                String typed=intelligenceKey.getText().toString().trim();
                if(!typed.isEmpty())DesktopIntelligenceProtocol.pairedKey(typed);
                DesktopIntelligenceProvider.disconnect();
                if(!typed.isEmpty())DesktopIntelligenceKeyStore.save(this,typed);
                prefs.setDesktopIntelligenceUrl(base);intelligenceAddress.setText(base);intelligenceKey.setText("");
                intelligenceKey.setHint(DesktopIntelligenceKeyStore.configured(this)?"Intelligence key saved — leave blank to keep":"Paste key from get-intelligence-key.cmd");
                intelligenceStatus.setText("Intelligence connection saved. Test it, then enable Use desktop intelligence.");
            }catch(Exception error){intelligenceStatus.setText(error.getMessage());}
        });
        Button copyInternalIdentity=button("Copy my Internal Mesh device identity");content.addView(copyInternalIdentity);
        copyInternalIdentity.setOnClickListener(v->{
            // Public device keys only; no bearer or private key in clipboard.
            new Thread(()->{
                try{
                    String identity=DesktopWhirlwind.identity(this);
                    runOnUiThread(()->{
                        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        if(clipboard!=null)clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Internal Mesh device identity",identity));
                        intelligenceStatus.setText("Internal device identity copied. On your PC run allow-internal-device.cmd and paste these two lines. Only approved devices may connect.");
                    });
                }catch(Exception e){runOnUiThread(()->intelligenceStatus.setText("Could not create Internal Mesh identity. Build the Whirlwind Android library first."));}
            },"RootsInternalIdentity").start();
        });
        Button testIntelligenceConnection=button("Test desktop intelligence connection");content.addView(testIntelligenceConnection);
        testIntelligenceConnection.setOnClickListener(v->{
            try{
                if(!DesktopIntelligenceProtocol.baseUrl(intelligenceAddress.getText().toString()).equals(prefs.desktopIntelligenceUrl())||
                    !intelligenceKey.getText().toString().trim().isEmpty()){
                    intelligenceStatus.setText("Save intelligence connection first, then test the saved address.");return;
                }
            }catch(IllegalArgumentException error){intelligenceStatus.setText(error.getMessage());return;}
            testIntelligenceConnection.setEnabled(false);intelligenceStatus.setText("Checking the intelligence listener on port 8221…");
            DesktopIntelligenceProvider.testConnection(this,IntelligenceModel.selected(this),(message,error)->runOnUiThread(()->{
                if(isDestroyed())return;testIntelligenceConnection.setEnabled(true);intelligenceStatus.setText(error==null?message:error);
            }));
        });
        Button clearIntelligence=button("Clear intelligence connection key");content.addView(clearIntelligence);
        clearIntelligence.setOnClickListener(v->{
            prefs.setDesktopIntelligenceEnabled(false);desktopIntelligence.setChecked(false);
            try{
                DesktopIntelligenceKeyStore.clear(this);intelligenceKey.setText("");intelligenceKey.setHint("Paste key from get-intelligence-key.cmd");
                intelligenceStatus.setText("Desktop intelligence key removed. Intelligence now runs on the phone.");
            }catch(Exception error){intelligenceStatus.setText(error.getMessage());}
        });
        intelligence.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                IntelligenceModel model=IntelligenceModel.values()[position];model.select(OverlayTranslationSettingsActivity.this);
                intelligenceStatus.setText(LocalIntelligenceProvider.status(OverlayTranslationSettingsActivity.this,model));
            }
            public void onNothingSelected(AdapterView<?> parent){}
        });
        Button intelligenceDownload=button("Download selected intelligence model");
        content.addView(intelligenceDownload);
        intelligenceDownload.setOnClickListener(v->{
            IntelligenceModel model=IntelligenceModel.values()[intelligence.getSelectedItemPosition()];
            new AlertDialog.Builder(this).setTitle(model.label)
                .setMessage("Download verified GGUF weights for on-device intelligence over unmetered network. Model terms: "+model.terms()+". The desktop uses its own model installation.")
                .setNeutralButton("Review terms",(d,w)->startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(model.terms()))))
                .setNegativeButton("Cancel",null).setPositiveButton("Agree and download",(d,w)->
                    LocalIntelligenceProvider.download(this,model,(message,error)->runOnUiThread(()->{
                        if(!isDestroyed())intelligenceStatus.setText(error==null?message:error);
                    }))).show();
        });
        EditText intelligenceInput=new EditText(this);
        intelligenceInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        intelligenceInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(3000)});
        intelligenceInput.setText("Explain the relationship in this image: the root listens for rain before the sky speaks.");
        content.addView(intelligenceInput);
        Button intelligenceTest=button("Test general intelligence");
        content.addView(intelligenceTest);
        intelligenceTest.setOnClickListener(v->{
            IntelligenceModel model=IntelligenceModel.values()[intelligence.getSelectedItemPosition()];
            intelligenceTest.setEnabled(false);intelligenceStatus.setText("Queued…");
            LocalIntelligenceProvider.generate(this,model,intelligenceInput.getText().toString(),new LocalIntelligenceProvider.Result(){
                @Override public void partial(String prefix){runOnUiThread(()->{if(!isDestroyed())StreamText.reveal(intelligenceStatus,prefix);});}
                @Override public void completed(String message,String error){runOnUiThread(()->{
                    if(isDestroyed())return;intelligenceTest.setEnabled(true);intelligenceStatus.setText(error==null?message:error);
                });}
            });
        });
        Button runtimeNeed=button("Resolve request through Axon");
        runtimeNeed.setOnClickListener(v->{
            IntelligenceModel model=IntelligenceModel.values()[intelligence.getSelectedItemPosition()];
            String request=intelligenceInput.getText().toString();
            if(request.trim().isEmpty()||request.length()>1500||request.indexOf('<')>=0){intelligenceStatus.setText("Enter plain text (up to 1500 characters).");return;}
            AxonRuntime.choose(this,request,plan->{
                runtimeNeed.setEnabled(false);intelligenceTest.setEnabled(false);intelligenceStatus.setText("Preparing intelligence and Axon references…");
                LocalIntelligenceProvider.generateWithAxon(this,model,request,plan,new LocalIntelligenceProvider.Result(){
                    @Override public void partial(String prefix){runOnUiThread(()->{if(!isDestroyed())StreamText.reveal(intelligenceStatus,prefix);});}
                    @Override public void completed(String message,String error){runOnUiThread(()->{
                        if(isDestroyed())return;runtimeNeed.setEnabled(true);intelligenceTest.setEnabled(true);intelligenceStatus.setText(error==null?message:error);
                    });}
                });
            },error->intelligenceStatus.setText(error),()->{});
        });content.addView(runtimeNeed);
        Button intelligenceRelease=button("Release intelligence memory");
        intelligenceRelease.setOnClickListener(v->LocalIntelligenceProvider.release((message,error)->runOnUiThread(()->{
            if(!isDestroyed())intelligenceStatus.setText(error==null?message:error);
        })));
        content.addView(intelligenceRelease);
        content.addView(intelligenceStatus);
        Button movement=button("Open Poetic Movement lab");
        movement.setOnClickListener(view->startActivity(new Intent(this,PoeticMovementActivity.class)));
        content.addView(movement);

        content=RootsUi.section(this,sections,"Gemma flow analyzer","Observed model elements · optional dense inference profiling",false);
        content.addView(text("Measures relative activation energy in 32-channel FFN, attention-output and residual groups during one prefill row and up to eight decode rows. Baselines are private, per exact model and task. Full dense inference is preserved. Profiling may slow generation.",13,0xFFAAC8D4));
        android.widget.CheckBox observe=new android.widget.CheckBox(this);
        observe.setText("Observe intelligence requests and advise hot FFN pages");observe.setTextColor(0xFFE5EFF4);
        observe.setChecked(ElementFlow.enabled(this));
        observe.setOnCheckedChangeListener((v,checked)->ElementFlow.setEnabled(this,checked));content.addView(observe);
        TextView flowStatus=text("Select a model above and run a general intelligence or Poetic Movement request.",13,0xFFE5EFF4);
        Button inspectFlow=button("Read selected model flow");
        inspectFlow.setOnClickListener(v->{
            IntelligenceModel chosen=IntelligenceModel.values()[intelligence.getSelectedItemPosition()];
            inspectFlow.setEnabled(false);
            java.util.concurrent.CompletableFuture.supplyAsync(()->ElementFlow.report(this,chosen)).whenComplete((result,error)->runOnUiThread(()->{
                if(isDestroyed())return;inspectFlow.setEnabled(true);flowStatus.setText(error==null?result:"Flow report unavailable");
            }));
        });
        content.addView(inspectFlow);
        content.addView(flowStatus);
        Button resetFlow=button("Clear selected model baseline");
        resetFlow.setOnClickListener(v->{
            IntelligenceModel chosen=IntelligenceModel.values()[intelligence.getSelectedItemPosition()];
            new AlertDialog.Builder(this).setTitle("Clear measured flow?")
                .setMessage("Clears aggregate observations for "+chosen.label+". Model weights and Tree are unchanged.")
                .setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,w)->{
                    try{LocalPLaMoTranslationProvider.WORK.execute(()->{
                        try{ElementFlow.clear(this,chosen);runOnUiThread(()->{if(!isDestroyed())flowStatus.setText("Baseline cleared");});}
                        catch(Exception e){runOnUiThread(()->{if(!isDestroyed())flowStatus.setText("Could not clear baseline");});}
                    });}catch(java.util.concurrent.RejectedExecutionException e){flowStatus.setText("Local inference queue busy");}
                }).show();
        });content.addView(resetFlow);

        content=RootsUi.section(this,sections,"FunctionGemma","Specialized operation proposals",false);
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
            int selected = targetLanguage.getSelectedItemPosition();
            String[] codes = TranslationLanguages.settingCodes();
            if (selected >= 0 && selected < codes.length) prefs.setTargetLanguage(codes[selected]);
            TranslationRuntime.disconnect(); // key, URL or language may have changed
            TranslationRuntime.startCompute(this);
            boolean remembered=FastestComputeRouter.rememberCurrent(this);
            refreshStatus();
            status.setText(status.getText() + "\nTranslation connection saved."+(remembered?" Current network remembered.":""));
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
        if(prefs.enabled()&&prefs.desktopTranslationEnabled())TranslationRuntime.startCompute(this);
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
                guidance + "\nPaired compute permission: " +
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

    private void showPostInspector(){
        XAccessibilityInspector.Snapshot snapshot=XAccessibilityInspector.snapshot();
        AlertDialog.Builder dialog=new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("X timeline inspector");
        LinearLayout panel=RootsUi.column(this);
        panel.setBackgroundColor(RootsUi.BG);
        panel.setPadding(dp(16),dp(12),dp(16),dp(12));
        panel.addView(RootsUi.text(this,snapshot.status(),14,RootsUi.TEXT,true));
        panel.addView(RootsUi.text(this,"Automatically records recent snapshots while local capture is on. Scroll X normally. Copy report shows post text first. Copy node attributes exports a recent snapshot, preferring settled post text and span payloads.",13,RootsUi.MUTED,false));
        Button copy=button("Copy report");
        copy.setEnabled(!snapshot.report().isEmpty());
        copy.setAlpha(copy.isEnabled()?1f:0.45f);
        copy.setOnClickListener(v->copyInspectorReport("X timeline report",snapshot.report()));
        panel.addView(copy);
        Button attributes=button("Copy node attributes (JSON)");
        attributes.setEnabled(!snapshot.attributes().isEmpty());
        attributes.setAlpha(attributes.isEnabled()?1f:0.45f);
        attributes.setOnClickListener(v->copyInspectorReport("X accessibility node attributes",snapshot.attributes()));
        panel.addView(attributes);
        if(!snapshot.latest().isEmpty()){
            TextView detail=RootsUi.text(this,snapshot.latest(),13,RootsUi.TEXT,false);
            detail.setTypeface(android.graphics.Typeface.MONOSPACE);
            detail.setBackgroundColor(RootsUi.CARD);
            detail.setTextIsSelectable(true);
            detail.setPadding(dp(12),dp(8),dp(12),dp(8));
            ScrollView scroll=new ScrollView(this);
            scroll.setBackgroundColor(RootsUi.CARD);scroll.setFillViewport(true);
            scroll.addView(detail,new ScrollView.LayoutParams(-1,-2));
            int height=Math.min(dp(420),getResources().getDisplayMetrics().heightPixels*45/100);
            panel.addView(scroll,new LinearLayout.LayoutParams(-1,height));
        }
        dialog.setView(panel).setNegativeButton("Close",null);
        dialog.setNeutralButton("Record 90 seconds",(d,w)->{
            if(!prefs.enabled()||!accessibilityEnabled()){
                android.widget.Toast.makeText(this,"Enable Roots X accessibility first.",android.widget.Toast.LENGTH_LONG).show();return;
            }
            XAccessibilityInspector.arm();
            android.widget.Toast.makeText(this,"Scroll X normally, then return to the inspector.",android.widget.Toast.LENGTH_LONG).show();
        }).show();
    }

    private void copyInspectorReport(String label,String report){
        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(clipboard==null||report.isEmpty())return;
        try{
            // Diagnostic reports are plain text, not password/secret clips.
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label,report));
            android.widget.Toast.makeText(this,"Report copied",android.widget.Toast.LENGTH_SHORT).show();
        }catch(RuntimeException error){
            android.widget.Toast.makeText(this,"Could not copy the report.",android.widget.Toast.LENGTH_LONG).show();
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
