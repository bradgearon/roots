package com.wds.sixstars.contextoverlay;

import android.app.*;
import android.os.Bundle;
import android.content.Intent;
import android.widget.*;
import android.text.InputFilter;
import java.util.*;
import java.util.concurrent.*;

/** Public directory browse/search. Nearby bound Axons and internet-discovered
 * directory cards are shown separately until their identities and exchange
 * protocol are unified. No search hit is represented as a binding. */
public final class NetworkExplorerActivity extends Activity {
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private EditText query;
    private TextView status,policy,searchButton;
    private LinearLayout nearby,remote,footprintRows;
    private TextView footprintStatus,previousPage,nextPage;
    private List<PublicFootprints.Card> footprints=Collections.emptyList();
    private int footprintPage;
    private static final int FOOTPRINT_PAGE_SIZE=24;
    private boolean busy,closed,initialBrowse;
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout shell=RootsUi.shell(this);shell.addView(RootsUi.header(this,getString(R.string.roots_ui_network),"External Mesh · shared brain"));
        ScrollView scroll=new ScrollView(this);LinearLayout content=RootsUi.column(this);
        content.setPadding(RootsUi.dp(this,16),0,RootsUi.dp(this,16),0);scroll.addView(content);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout controls=RootsUi.card(this,content);
        policy=RootsUi.text(this,"",14,RootsUi.ACCENT,true);controls.addView(policy);
        controls.addView(RootsUi.text(this,"Browse signed public node cards or search an exact public topic tag. This query is sent to the External Mesh; do not enter private prompts or personal information.",13,RootsUi.MUTED,false));
        query=new EditText(this);query.setSingleLine(true);query.setHint("music, neuroscience, engineering…");query.setFilters(new InputFilter[]{new InputFilter.LengthFilter(48)});controls.addView(query);
        searchButton=RootsUi.action(this,"Search / browse network",this::search);controls.addView(searchButton);
        controls.addView(RootsUi.action(this,"External bootstrap settings",this::configure));
        controls.addView(RootsUi.action(this,"Manage nearby connections",()->startActivity(new Intent(this,EncounterActivity.class))));
        status=RootsUi.text(this,"No search yet.",13,RootsUi.MUTED,false);controls.addView(status);
        LinearLayout local=RootsUi.card(this,content);local.addView(RootsUi.text(this,"Nearby bound Axons",18,RootsUi.TEXT,true));
        nearby=RootsUi.column(this);local.addView(nearby);
        LinearLayout savedIndex=RootsUi.card(this,content);
        savedIndex.addView(RootsUi.text(this,"Saved public Mesh index",18,RootsUi.TEXT,true));
        savedIndex.addView(RootsUi.text(this,"Verified contributor references received through Axon encounters. Includes offline contributors; not a complete network census. Opening an entry does not connect or subscribe.",13,RootsUi.MUTED,false));
        savedIndex.addView(RootsUi.action(this,"Refresh saved index",this::refreshFootprints));
        footprintStatus=RootsUi.text(this,"Loading saved footprints…",13,RootsUi.MUTED,false);savedIndex.addView(footprintStatus);
        footprintRows=RootsUi.column(this);savedIndex.addView(footprintRows);
        previousPage=RootsUi.action(this,"‹ Previous page",()->{if(footprintPage>0){footprintPage--;renderFootprints();}});savedIndex.addView(previousPage);
        nextPage=RootsUi.action(this,"Next page ›",()->{if((footprintPage+1)*FOOTPRINT_PAGE_SIZE<footprints.size()){footprintPage++;renderFootprints();}});savedIndex.addView(nextPage);
        LinearLayout internet=RootsUi.card(this,content);internet.addView(RootsUi.text(this,"Internet directory",18,RootsUi.TEXT,true));
        internet.addView(RootsUi.text(this,"A valid signature proves a card's owner, not the truth of its claims. Directory-only nodes do not yet offer Internet Axon exchange.",13,RootsUi.MUTED,false));
        remote=RootsUi.column(this);internet.addView(remote);
        shell.addView(RootsUi.action(this,"‹ Roots Mesh",this::finish));
        RootsUi.styleInputs(this,shell);setContentView(shell);
        if(!ExternalDirectory.seeds(this).isEmpty())initialBrowse=true;
        else status.setText("Configure an external bootstrap node to browse the Internet directory. Nearby bindings work without Internet.");
    }
    @Override protected void onResume(){
        super.onResume();
        policy.setText(EncounterSettings.enabled(this)?"Nearby automatic binding · enabled":"Nearby automatic binding · off");
        if(!work.isShutdown())work.execute(()->{
            var snapshot=EncounterBindings.get(this).snapshot();
            runOnUiThread(()->{if(!closed)renderNearby(snapshot);});
        });
        refreshFootprints();
        if(initialBrowse){initialBrowse=false;search();}
    }
    private void configure(){
        EditText field=new EditText(this);field.setText(ExternalDirectory.seeds(this));field.setHint("/ip4/…/tcp/8240/p2p/…");field.setMinLines(3);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4096)});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("External bootstrap nodes").setMessage("Enter one public External Mesh multiaddress per line (up to eight). Never paste an Internal Mesh address or key.").setView(field)
            .setNegativeButton(R.string.roots_ui_cancel,null).setPositiveButton(R.string.roots_ui_save,null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try{ExternalDirectory.saveSeeds(this,field.getText().toString());dialog.dismiss();status.setText("External bootstrap settings saved.");}
            catch(IllegalArgumentException failure){field.setError(failure.getMessage());}
        }));dialog.show();
    }
    private void renderNearby(EncounterBindings.Snapshot snapshot){
        nearby.removeAllViews();
        if(snapshot.peers().isEmpty())nearby.addView(RootsUi.text(this,"No saved nearby bindings yet.",13,RootsUi.MUTED,false));
        for(var peer:snapshot.peers()){
            String id=peer.binding().peer();
            LinearLayout row=RootsUi.card(this,nearby);
            row.addView(RootsUi.text(this,peer.label().isEmpty()?"Axon "+id.substring(0,12):peer.label(),15,RootsUi.TEXT,true));
            row.addView(RootsUi.text(this,(peer.blocked()?"Blocked":id.equals(EncounterService.activePeer())?"Connected":"Bound · offline")+" · "+peer.received()+" received",13,RootsUi.MUTED,false));
        }
    }
    private void refreshFootprints(){
        if(closed||work.isShutdown())return;
        work.execute(()->{
            try{
                List<PublicFootprints.Card> snapshot=PublicFootprints.get(this).snapshot();
                runOnUiThread(()->{if(!closed){footprints=snapshot;renderFootprints();}});
            }catch(Exception failure){runOnUiThread(()->{if(!closed)footprintStatus.setText("Saved public index unavailable.");});}
        });
    }
    private void renderFootprints(){
        footprintRows.removeAllViews();
        int pages=Math.max(1,(footprints.size()+FOOTPRINT_PAGE_SIZE-1)/FOOTPRINT_PAGE_SIZE);
        footprintPage=Math.min(footprintPage,pages-1);
        int from=footprintPage*FOOTPRINT_PAGE_SIZE,to=Math.min(footprints.size(),from+FOOTPRINT_PAGE_SIZE);
        footprintStatus.setText(footprints.size()+" saved verified contributors · page "+(footprintPage+1)+" / "+pages+" · partial directory");
        previousPage.setEnabled(footprintPage>0);nextPage.setEnabled(footprintPage+1<pages);
        if(footprints.isEmpty())footprintRows.addView(RootsUi.text(this,"No signed footprints yet. Encounter an updated Roots peer to receive its public index references.",13,RootsUi.MUTED,false));
        for(int i=from;i<to;i++){
            var card=footprints.get(i);
            footprintRows.addView(RootsUi.action(this,"Axon "+card.id().substring(0,12)+" · "+card.links().size()+" public references",()->showFootprint(card.id())));
        }
    }
    private void showFootprint(String id){
        PublicFootprints.Card found=null;
        for(var card:footprints)if(card.id().equals(id)){found=card;break;}
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("Public Axon "+id.substring(0,12))
            .setPositiveButton("Close",null).setNeutralButton("Copy ID",(d,w)->{
                android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                if(clipboard!=null)clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Public Axon ID",id));
            });
        if(found==null){dialog.setMessage("Referenced contributor · no verified footprint retained locally. This is not a live connection.\n"+id);}
        else if(found.links().isEmpty()){dialog.setMessage("Verified historical footprint · no public links in this revision.\n"+id);}
        else{
            List<String> links=found.links();
            String[] rows=new String[links.size()];
            for(int i=0;i<rows.length;i++){
                String target=links.get(i);boolean known=false;
                for(var card:footprints)if(card.id().equals(target)){known=true;break;}
                rows[i]="Axon "+target.substring(0,12)+(known?" · saved footprint":" · reference only");
            }
            dialog.setItems(rows,(d,index)->showFootprint(links.get(index)));
        }
        dialog.show();
    }
    private void search(){
        if(busy||closed)return;String term=query.getText().toString();
        busy=true;searchButton.setEnabled(false);status.setText("Searching external routing nodes…");
        work.execute(()->{
            try{
                ExternalDirectory.Result result=ExternalDirectory.search(this,term);
                runOnUiThread(()->{if(!closed){render(result);finishSearch();}});
            }catch(Exception|LinkageError failure){
                runOnUiThread(()->{if(!closed){status.setText(failure.getMessage()==null?"External directory unavailable":failure.getMessage());finishSearch();}});
            }
        });
    }
    private void finishSearch(){busy=false;searchButton.setEnabled(true);}
    private void render(ExternalDirectory.Result result){
        remote.removeAllViews();
        status.setText(result.cards().size()+" verified directory cards"+(result.partial()?" · partial search (bounded or timed out)":" · bounded search complete"));
        if(result.cards().isEmpty())remote.addView(RootsUi.text(this,"No matching verified cards returned. This does not mean the network has no such nodes.",13,RootsUi.MUTED,false));
        for(var card:result.cards()){
            LinearLayout row=RootsUi.card(this,remote);
            row.addView(RootsUi.text(this,card.name(),16,RootsUi.TEXT,true));
            TextView id=RootsUi.text(this,card.peer(),12,RootsUi.MUTED,false);id.setTextIsSelectable(true);row.addView(id);
            row.addView(RootsUi.text(this,"Topics: "+String.join(" · ",card.topics()),13,RootsUi.TEXT,false));
            row.addView(RootsUi.text(this,"Signature verified · discovered (directory only)",13,RootsUi.ACCENT,true));
        }
    }
    @Override protected void onDestroy(){closed=true;work.shutdownNow();super.onDestroy();}
}
