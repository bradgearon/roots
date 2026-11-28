package com.wds.sixstars.contextoverlay;

import android.app.*;
import android.widget.Toast;
import com.wds.sixstars.contextoverlay.tree.NativeTree;
import com.wds.sixstars.contextoverlay.tree.TreeRuntime;
import java.util.*;
import java.util.concurrent.*;

/** Explicit public projection of existing Tree records. Never publishes private
 * understanding, prompts, author/contact metadata or raw screen observations.
 * The user must confirm that selected source posts are public and redistributable:
 * possession of an X status URL is not proof of public visibility. */
final class PublicTreePublishing {
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"RootsPublicProjection");t.setDaemon(true);return t;});
    private PublicTreePublishing(){}
    static boolean structuredOriginal(NativeTree.Master record){
        if(record==null||!record.key().startsWith("local:x:"))return false;
        for(NativeTree.Fragment member:record.members())if("record.x.body".equals(member.source()))return true;
        return false;
    }
    static MeshItem project(NativeTree.Master record)throws Exception{
        if(!record.key().startsWith("local:x:"))return null;
        String link=XPostLink.forRecord(record);
        if(link.isEmpty())return null;
        for(NativeTree.Fragment member:record.members()){
            if(!"record.x.body".equals(member.source()))continue;
            String body=member.body(),language=member.language();
            if(body==null||body.trim().isEmpty()||body.length()>3000)return null;
            if(language==null||!language.matches("[a-z]{2,3}"))language="und";
            return MeshItem.create(language,"Public X post",body,link);
        }
        return null;
    }
    static void confirmOne(Activity activity,NativeTree.Master record,Runnable changed){
        try{
            MeshItem item=project(record);
            if(item==null){toast(activity,"Only an original X post with an exact source link can use this shortcut. Write a shareable remembrance for other items.");return;}
            new AlertDialog.Builder(activity).setTitle("Publish original public post?")
                .setMessage(item.text()+"\n\n"+item.link()+"\n\nConfirm this is public and you have permission to redistribute it. It may be copied permanently. No private understanding or other observations will be included.")
                .setNegativeButton("Cancel",null).setPositiveButton("Publish",(d,w)->publish(activity,Collections.singletonList(item),changed)).show();
        }catch(Exception failure){toast(activity,"Record is not eligible for public projection.");}
    }
    static void choose(Activity activity,Runnable changed){
        if(!NativeTree.available()){toast(activity,"Native Tree unavailable.");return;}
        WORK.execute(()->{
            try{
                TreeRuntime tree=TreeRuntime.get(activity);List<MeshItem> items=new ArrayList<>();
                Set<String> seen=new HashSet<>();
                for(NativeTree.Master record:tree.allRecords("",200)){
                    MeshItem item=project(record);if(item!=null&&seen.add(item.id()))items.add(item);
                    if(items.size()>=60)break;
                }
                activity.runOnUiThread(()->{
                    if(activity.isDestroyed())return;
                    if(items.isEmpty()){toast(activity,"No recent original posts with exact links. Attach a source link or write a shareable remembrance.");return;}
                    String[] labels=new String[items.size()];boolean[] chosen=new boolean[items.size()];
                    for(int i=0;i<items.size();i++){
                        MeshItem item=items.get(i);String body=item.text().replace('\n',' ');
                        labels[i]=(body.length()>160?body.substring(0,160)+"…":body)+"\n"+item.link();
                    }
                    new AlertDialog.Builder(activity).setTitle("Select public records to publish")
                        .setMultiChoiceItems(labels,chosen,(d,i,on)->chosen[i]=on)
                        .setNegativeButton("Cancel",null).setPositiveButton("Review selection",(d,w)->{
                            List<MeshItem> selected=new ArrayList<>();for(int i=0;i<items.size();i++)if(chosen[i])selected.add(items.get(i));
                            if(selected.isEmpty()){toast(activity,"No records selected.");return;}
                            new AlertDialog.Builder(activity).setTitle("Publish "+selected.size()+" public records?")
                                .setMessage("Confirm all selected original posts are public and you have permission to redistribute them. Only original text and exact source links will be shared; private notes and raw captures remain private. Recipients may retain and forward copies.")
                                .setNegativeButton("Cancel",null).setPositiveButton("Publish",(confirm,which)->publish(activity,selected,changed)).show();
                        }).show();
                });
            }catch(Exception|LinkageError error){activity.runOnUiThread(()->toast(activity,"Could not load recent Tree records."));}
        });
    }
    private static void publish(Activity activity,List<MeshItem> selected,Runnable changed){
        TreeRuntime tree=TreeRuntime.get(activity);long epoch=tree.captureEpoch();
        WORK.execute(()->{
            int done=0;
            try{for(MeshItem item:selected){item.store(tree,epoch);done++;}}
            catch(Exception|LinkageError failure){/* Report partial completion, never claim all published. */}
            int count=done;MeshPeer.get(activity).invalidateEncounterPublicationCount();EncounterService.refreshDiscovery();
            activity.runOnUiThread(()->{if(!activity.isDestroyed()){changed.run();toast(activity,count+" of "+selected.size()+" records published for automatic exchange.");}});
        });
    }
    private static void toast(Activity activity,String text){if(!activity.isDestroyed())Toast.makeText(activity,text,Toast.LENGTH_LONG).show();}
}
