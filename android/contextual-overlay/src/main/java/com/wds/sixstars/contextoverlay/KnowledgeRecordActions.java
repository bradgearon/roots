package com.wds.sixstars.contextoverlay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.EditText;
import android.widget.Toast;
import com.wds.sixstars.contextoverlay.tree.NativeTree;
import com.wds.sixstars.contextoverlay.tree.TreeRuntime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Source navigation never uses fabricated X IDs or a search URL as a post URL. */
final class KnowledgeRecordActions {
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"RootsRecordActions");t.setDaemon(true);return t;});
    static void open(Activity activity,NativeTree.Master record,Runnable changed){
        String url=XPostLink.forRecord(record);
        if(url.isEmpty()){options(activity,record,changed);return;}
        Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse(url));
        try{activity.startActivity(new Intent(intent).setPackage("com.twitter.android"));}
        catch(android.content.ActivityNotFoundException e){
            try{activity.startActivity(intent);}catch(android.content.ActivityNotFoundException ignored){toast(activity,"No app can open the source link.");}
        }
    }
    private static void toast(Activity a,String message){if(!a.isDestroyed())Toast.makeText(a,message,Toast.LENGTH_LONG).show();}
    private static String prefix(NativeTree.Master record){return record.key().startsWith("local:mesh:")?"record.mesh.":"record.x.";}
    static void options(Activity activity,NativeTree.Master record,Runnable changed){
        String language=new OverlayPreferences(activity).targetLanguage();
        String title=record.latest("topic");if(title.isEmpty())title=record.first("author");if(title.isEmpty())title="Knowledge record";
        final String[] labels={"View original and localized text","Attach exact X post link","Translate into "+language,"Confirm original is already "+language,"Add private understanding","Mark wanted","Publish a shareable remembrance"};
        new AlertDialog.Builder(activity).setTitle(title).setItems(labels,(dialog,index)->{
            switch(index){
                case 0 -> {
                    NativeTree.Fragment original=record.body(),local=record.localized(language);
                    String message=local.sequence()==original.sequence()?original.body():
                        "Local language:\n"+local.body()+"\n\nOriginal:\n"+original.body();
                    String link=XPostLink.forRecord(record);if(!link.isEmpty())message+="\n\n"+link;
                    new AlertDialog.Builder(activity).setTitle("Knowledge").setMessage(message).setPositiveButton("Close",null).show();
                }
                case 1 -> attach(activity,record,changed);
                case 2 -> translate(activity,record,language,changed);
                case 3 -> new AlertDialog.Builder(activity).setTitle("Confirm language")
                    .setMessage("Use the original as "+language+" knowledge? Only confirm if it is already in that language. This does not generate a translation.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Confirm",(d,w)->store(activity,record,"localized",record.body().body(),language,TreeRuntime.get(activity).captureEpoch(),changed)).show();
                case 4 -> understand(activity,record,language,changed);
                case 5 -> store(activity,record,"want","wanted","",TreeRuntime.get(activity).captureEpoch(),changed);
                case 6 -> MeshActivity.publish(activity,record,changed);
            }
        }).setNegativeButton("Close",null).show();
    }
    private static void understand(Activity activity,NativeTree.Master record,String language,Runnable changed){
        EditText input=new EditText(activity);input.setMinLines(3);input.setHint("What do you understand about this knowledge?");
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(3000)});
        new AlertDialog.Builder(activity).setTitle("Private understanding")
            .setMessage("A local-language interpretation or relationship. This remains private and contributes to the home ranking.")
            .setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{
                String text=input.getText().toString().trim();if(!text.isEmpty())store(activity,record,"understanding",text,language,TreeRuntime.get(activity).captureEpoch(),changed);
            }).show();
    }
    private static void attach(Activity activity,NativeTree.Master record,Runnable changed){
        EditText input=new EditText(activity);input.setSingleLine(true);input.setHint("https://x.com/…/status/…");input.setText(XPostLink.forRecord(record));
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Attach the original post link")
            .setMessage("Copy the link from that exact post in X. Roots cannot invent a missing post identifier.")
            .setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b->{
            String canonical=XPostLink.canonical(input.getText().toString());
            if(canonical.isEmpty()){input.setError("Enter an exact HTTPS X status URL.");return;}
            store(activity,record,"link-confirmed",canonical,"",TreeRuntime.get(activity).captureEpoch(),changed);dialog.dismiss();
        }));dialog.show();
    }
    private static void translate(Activity activity,NativeTree.Master record,String language,Runnable changed){
        if(!new OverlayPreferences(activity).enabled()){toast(activity,"Enable local translation consent in Settings first.");return;}
        NativeTree.Fragment original=record.body();
        if(original.body().length()>1200){toast(activity,"This record is too long for the current translation limit.");return;}
        long epoch=TreeRuntime.get(activity).captureEpoch();
        toast(activity,"Translating locally…");
        InferenceHistory.Attempt attempt=InferenceHistory.get(activity).begin("Phone","PLaMo","Localize knowledge","Explicit private localization");
        new LocalPLaMoTranslationProvider(activity).translate(original.body(),original.language(),language,new TranslationProvider.Result(){
            @Override public void started(){attempt.running("Running");}
            @Override public void completed(String text,String error){
                attempt.finish(error==null&&text!=null&&!text.isBlank(),error==null?"Local generation completed":"Local generation failed");
                if(error!=null||text==null||text.isBlank()){activity.runOnUiThread(()->toast(activity,error==null?"No translation returned.":error));return;}
                store(activity,record,"translation",text,language,epoch,changed);
            }
        });
    }
    private static void store(Activity activity,NativeTree.Master record,String role,String text,String language,long epoch,Runnable changed){
        TreeRuntime tree=TreeRuntime.get(activity);
        WORK.execute(()->{
            String error=null;
            try{String source=prefix(record)+((role.equals("translation")||role.equals("localized")||role.equals("understanding"))?language+".":"")+role;
                tree.storeExplicit(List.of(new NativeTree.Observation(source,record.key(),text,language,System.currentTimeMillis())),epoch);}
            catch(Exception|LinkageError e){error="Could not save record: "+e.getMessage();}
            String message=error;
            activity.runOnUiThread(()->{if(activity.isDestroyed())return;if(message==null){changed.run();toast(activity,"Knowledge updated.");}else toast(activity,message);});
        });
    }
}
