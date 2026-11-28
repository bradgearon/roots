package com.wds.sixstars.contextoverlay;

import android.app.*;
import android.os.Bundle;
import android.widget.*;
import com.wds.sixstars.contextoverlay.tree.TreeRuntime;
import java.util.*;
import java.util.concurrent.*;

/** External receipt history; records normally already live in the main Tree. */
public final class ExternalKnowledgeActivity extends Activity {
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private LinearLayout items;
    private TextView status;
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout shell=RootsUi.shell(this);
        shell.addView(RootsUi.header(this,"External knowledge","Shared brain · public publications"));
        ScrollView scroll=new ScrollView(this);items=RootsUi.column(this);
        items.setPadding(RootsUi.dp(this,16),0,RootsUi.dp(this,16),0);scroll.addView(items);
        status=RootsUi.text(this,"Loading public knowledge…",13,RootsUi.MUTED,false);shell.addView(status);
        shell.addView(RootsUi.action(this,"Refresh",this::load));
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        shell.addView(RootsUi.action(this,"‹ Encounters",this::finish));setContentView(shell);ExternalTreeImport.schedule(this);load();
    }
    private void load(){
        if(work.isShutdown())return;
        work.execute(()->{
            try{
                List<ExternalKnowledgeStore.Publication> rows=ExternalKnowledgeStore.get(this).recent(100);
                runOnUiThread(()->{if(!isDestroyed())render(rows);});
            }catch(Exception failure){runOnUiThread(()->status.setText("Could not read external publications"));}
        });
    }
    private void render(List<ExternalKnowledgeStore.Publication> rows){
        items.removeAllViews();status.setText(rows.size()+" recent receipts · automatically retained as external references");
        for(var row:rows){
            MeshItem item=row.item();
            LinearLayout card=RootsUi.card(this,items);
            card.addView(RootsUi.text(this,item.topic(),16,RootsUi.TEXT,true));
            TextView body=RootsUi.text(this,item.text(),14,RootsUi.TEXT,false);body.setTextIsSelectable(true);card.addView(body);
            card.addView(RootsUi.text(this,"Source peer "+row.peer().substring(0,12)+" · "+item.id().substring(0,12)+" · "+item.language(),12,RootsUi.MUTED,false));
            if(!item.link().isEmpty())card.addView(RootsUi.text(this,item.link(),12,RootsUi.MUTED,false));
            card.addView(RootsUi.text(this,row.promoted()?"Retained in main Tree":"Pending Tree retention",13,RootsUi.ACCENT,false));
            if(!row.promoted())card.addView(RootsUi.action(this,"Retry Tree retention",()->confirm(item)));
        }
    }
    private void confirm(MeshItem item){
        new AlertDialog.Builder(this).setTitle("Bring public knowledge into your Tree?")
            .setMessage("Copies the selected publication with its original content hash and source link. It does not grant the publisher private access or authorize computation. Treat it as untrusted reference material.")
            .setNegativeButton("Cancel",null).setPositiveButton("Bring inside",(d,w)->{
                TreeRuntime tree=TreeRuntime.get(this);long epoch=tree.captureEpoch();
                if(work.isShutdown())return;
                work.execute(()->{try{
                    ExternalKnowledgeStore.get(this).promote(item.id(),tree,epoch);
                    runOnUiThread(()->{if(!isDestroyed()){Toast.makeText(this,"Imported into your Tree",Toast.LENGTH_SHORT).show();load();}});
                }catch(Exception|LinkageError e){runOnUiThread(()->status.setText("Import failed · no private access granted"));}});
            }).show();
    }
    @Override protected void onDestroy(){work.shutdownNow();super.onDestroy();}
}
