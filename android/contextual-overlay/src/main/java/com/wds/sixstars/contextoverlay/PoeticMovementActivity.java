package com.wds.sixstars.contextoverlay;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.InputFilter;

public final class PoeticMovementActivity extends Activity {
    private EditText profile;
    private CryptoWordGenerator.Words words;
    private CheckBox rate;
    private TextView candidates,selected,resolutionInput,resolved,status;
    private boolean secondStarted;
    private Button run,random,download;
    private android.widget.Spinner modelSelector;
    private boolean busy,closed;
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout shell=RootsUi.shell(this);
        shell.addView(RootsUi.header(this,getString(R.string.roots_ui_poetic),"Forge lab · two local inference requests"));
        ScrollView scroll=new ScrollView(this);
        LinearLayout content=RootsUi.column(this);int pad=RootsUi.dp(this,16);
        content.setPadding(pad,0,pad,pad);scroll.addView(content);
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout prep=RootsUi.card(this,content);
        prep.addView(RootsUi.text(this,"Preparation",18,RootsUi.TEXT,true));
        prep.addView(RootsUi.text(this,"Cryptographically random nouns and verbs. The selected general intelligence model chooses five pairs and resolves them locally in two requests. Nothing is saved or shared.",13,RootsUi.MUTED,false));
        modelSelector=new android.widget.Spinner(this);
        RootsUi.styleSpinner(this,modelSelector,IntelligenceModel.labels());
        modelSelector.setSelection(IntelligenceModel.selected(this).ordinal());
        prep.addView(modelSelector);
        download=button("Download selected model",this::downloadModel);prep.addView(download);
        modelSelector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> parent,android.view.View view,int position,long id){
                IntelligenceModel.values()[position].select(PoeticMovementActivity.this);
                if(!busy&&status!=null)status.setText(LocalIntelligenceProvider.localStatus(PoeticMovementActivity.this,IntelligenceModel.values()[position]));
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        random=button("Generate new nouns and verbs",this::generateWords);prep.addView(random);
        prep.addView(RootsUi.text(this,"Profile",14,RootsUi.TEXT,true));
        profile=new EditText(this);profile.setSingleLine(true);profile.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});
        profile.setText("Depth, beauty, novelty, coherence");prep.addView(profile);
        rate=new CheckBox(this);rate.setText("Rate each resolution in request 2");rate.setChecked(true);prep.addView(rate);
        candidates=RootsUi.text(this,"",14,RootsUi.MUTED,false);candidates.setTextIsSelectable(true);prep.addView(candidates);
        run=button("Run two requests",this::run);prep.addView(run);
        status=RootsUi.text(this,"Ready",13,RootsUi.ACCENT,true);prep.addView(status);
        LinearLayout first=RootsUi.card(this,content);
        first.addView(RootsUi.text(this,"1 · Selection and ordinary action",17,RootsUi.TEXT,true));
        selected=RootsUi.text(this,"Not run",15,RootsUi.TEXT,false);selected.setTextIsSelectable(true);first.addView(selected);
        LinearLayout second=RootsUi.card(this,content);
        second.addView(RootsUi.text(this,"2 · Displacement and resolution",17,RootsUi.TEXT,true));
        second.addView(RootsUi.text(this,"Independent request 2 input",13,RootsUi.MUTED,true));
        resolutionInput=RootsUi.text(this,"Not prepared",13,RootsUi.MUTED,false);resolutionInput.setTextIsSelectable(true);second.addView(resolutionInput);
        resolved=RootsUi.text(this,"Not run",15,RootsUi.TEXT,false);resolved.setTextIsSelectable(true);second.addView(resolved);
        shell.addView(RootsUi.action(this,getString(R.string.roots_ui_back),this::finish));
        RootsUi.styleInputs(this,shell);setContentView(shell);generateWords();
    }
    private Button button(String title,Runnable action){
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(RootsUi.TEXT);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF164B50));
        b.setOnClickListener(v->action.run());return b;
    }
    private IntelligenceModel model(){return IntelligenceModel.values()[modelSelector.getSelectedItemPosition()];}
    private void downloadModel(){
        IntelligenceModel chosen=model();
        new android.app.AlertDialog.Builder(this).setTitle(chosen.label)
            .setMessage("Download verified model weights over unmetered network. Model terms: "+chosen.terms()+". Inference remains on-device.")
            .setNeutralButton("Review terms",(d,w)->startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(chosen.terms()))))
            .setNegativeButton(R.string.roots_ui_cancel,null).setPositiveButton("Agree and download",(d,w)->{
                download.setEnabled(false);
                LocalIntelligenceProvider.download(this,chosen,(message,error)->runOnUiThread(()->{
                    if(closed)return;download.setEnabled(true);status.setText(error==null?message:error);
                }));
            }).show();
    }
    private PoeticMovement.Input input(){return PoeticMovement.input(words,profile.getText().toString(),rate.isChecked());}
    private void generateWords(){
        if(busy)return;
        words=CryptoWordGenerator.generate();
        candidates.setText("Nouns: "+String.join(", ",words.nouns())+"\n\nVerbs: "+String.join(", ",words.verbs()));
        selected.setText("Not run");resolutionInput.setText("Not prepared");resolved.setText("Not run");status.setText(LocalIntelligenceProvider.localStatus(this,model()));
    }
    private void showCandidates(PoeticMovement.Input in){
        candidates.setText("Nouns: "+String.join(", ",in.nouns())+"\n\nVerbs: "+String.join(", ",in.verbs()));
    }
    private void run(){
        if(busy)return;
        final PoeticMovement.Input in;
        try{in=input();}catch(IllegalArgumentException e){status.setText(e.getMessage());return;}
        final IntelligenceModel chosen=model();
        showCandidates(in);busy=true;run.setEnabled(false);random.setEnabled(false);profile.setEnabled(false);rate.setEnabled(false);modelSelector.setEnabled(false);download.setEnabled(false);
        secondStarted=false;selected.setText("Waiting…");resolutionInput.setText("Awaiting valid selection");resolved.setText("Not started");status.setText("Queued on local inference engine…");
        LocalIntelligenceProvider.testMovement(this,chosen,in,new LocalFunctionGemmaProvider.MovementResult(){
            @Override public void progress(String stage,String message){runOnUiThread(()->{
                if(closed)return;
                switch(stage){
                    case "selection_stream":StreamText.reveal(selected,message);break;
                    case "resolution_stream":StreamText.reveal(resolved,message);break;
                    case "selected":selected.setText(message);break;
                    case "resolution_input":resolutionInput.setText(message);break;
                    case "resolve":case "movement_resolve":secondStarted=true;resolved.setText("Generating…");status.setText(message);break;
                    case "invalid_selection":selected.setText("Invalid model output:\n"+message);resolved.setText("Not started — request 1 invalid");break;
                    case "invalid_resolution":resolved.setText("Invalid model output:\n"+message);break;
                    default:status.setText(message);
                }
            });}
            @Override public void completed(PoeticMovement.Run result,String error){runOnUiThread(()->{
                if(closed)return;
                busy=false;run.setEnabled(true);random.setEnabled(true);profile.setEnabled(true);rate.setEnabled(true);modelSelector.setEnabled(true);download.setEnabled(true);
                if(error!=null){status.setText(error);if(!secondStarted){resolutionInput.setText("Not prepared — request 1 did not complete");resolved.setText("Not started — request 1 did not complete");}else if(resolved.getText().toString().equals("Generating…"))resolved.setText("Request 2 failed");return;}
                StringBuilder b=new StringBuilder();
                for(PoeticMovement.Resolution r:result.resolutions()){
                    PoeticMovement.Pair p=result.pairs().get(r.index()-1);
                    b.append(r.index()).append(". ").append(p.noun()).append(" · ").append(p.replacement()).append('\n')
                     .append(r.expression()).append('\n');
                    if(r.rating()!=null)b.append("Model self-rating: ").append(r.rating()).append("/10\n");
                    b.append('\n');
                }
                resolved.setText(b.toString().trim());
                status.setText(chosen.label+"\n2 requests · "+result.elapsedMs()+" ms total\nSelection: "+result.firstStats()[6]+" tokens · resolution: "+result.secondStats()[6]+" tokens");
            });}
        });
    }
    @Override protected void onDestroy(){closed=true;super.onDestroy();}
}
