package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** App-private persistent peer identity, policy, cursor and bounded interaction
 * history. No radio address, location, private text or credentials are retained. */
final class EncounterBindings {
    private static final int MAX_BINDINGS=1024,MAX_BYTES=8192,MAX_EVENTS=200,MAX_HISTORY_BYTES=256*1024;
    private static EncounterBindings instance;
    static synchronized EncounterBindings get(Context c){
        if(instance==null)instance=new EncounterBindings(c.getApplicationContext());
        return instance;
    }
    private final File directory;
    private final Map<String,Peer> peers=new HashMap<>();
    private final List<Event> events=new ArrayList<>();
    private boolean loaded,historyLoaded;
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> listeners=new java.util.concurrent.CopyOnWriteArrayList<>();
    void addListener(Runnable listener){listeners.addIfAbsent(listener);}
    void removeListener(Runnable listener){listeners.remove(listener);}
    private void changed(){for(Runnable listener:listeners)try{listener.run();}catch(RuntimeException ignored){}}
    synchronized List<Peer> subscriptions(){
        load();List<Peer> list=new ArrayList<>(peers.values());
        list.sort(Comparator.comparingLong(Peer::lastSeen).reversed().thenComparing(p->p.binding().peer()));
        return list;
    }
    record Binding(String peer,String root,String after,long encounters){}
    record Peer(Binding binding,String label,boolean blocked,long firstSeen,long lastSeen,long sent,long received,String result){}
    record Event(long at,String peer,String result,int sent,int received){}
    record Snapshot(List<Peer> peers,List<Event> events){}
    private EncounterBindings(Context context){directory=new File(context.getNoBackupFilesDir(),"encounter-axons");}
    static boolean digest(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private File path(String peer)throws IOException{
        if(!digest(peer))throw new IOException("Invalid encounter identity");
        return new File(directory,peer+".axon");
    }
    private void ensureDirectory()throws IOException{
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create binding store");
    }
    private static String read(File path,int max)throws Exception{
        try(FileInputStream input=new AtomicFile(path).openRead()){
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] chunk=new byte[1024];int n;
            while((n=input.read(chunk))!=-1){if(buffer.size()+n>max)throw new IOException("Invalid store length");buffer.write(chunk,0,n);}
            return new String(buffer.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static void write(File path,String data,int max)throws Exception{
        byte[] bytes=data.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>max)throw new IOException("Encounter store limit reached");
        AtomicFile file=new AtomicFile(path);FileOutputStream out=file.startWrite();
        try{out.write(bytes);file.finishWrite(out);}catch(Exception failure){file.failWrite(out);throw failure;}
    }
    private Peer decode(String id)throws Exception{
        JSONObject j=new JSONObject(read(path(id),MAX_BYTES));
        if(j.getInt("version")!=1||!id.equals(j.getString("peer")))throw new IOException("Invalid binding");
        String root=j.getString("root"),after=j.getString("after");
        if((!root.isEmpty()&&!digest(root))||(!after.isEmpty()&&!digest(after)))throw new IOException("Invalid checkpoint");
        Binding binding=new Binding(id,root,after,j.getLong("encounters"));
        return new Peer(binding,j.optString("label",""),j.optBoolean("blocked"),j.optLong("first_seen"),j.optLong("last_seen"),
            j.optLong("sent"),j.optLong("received"),j.optString("result","Bound"));
    }
    private void load(){
        if(loaded)return;
        String[] names=directory.list();
        if(names!=null)for(String name:names){
            if(!name.matches("[0-9a-f]{64}\\.axon(?:\\.bak|\\.new)?"))continue;
            String id=name.substring(0,64);if(peers.containsKey(id))continue;
            try{peers.put(id,decode(id));}
            catch(Exception corrupt){
                // Never silently reauthorize an unreadable peer policy.
                peers.put(id,new Peer(new Binding(id,"","",0),"",true,0,0,0,0,"Unreadable binding · blocked"));
            }
        }
        loaded=true;
    }
    private void loadHistory(){
        if(historyLoaded)return;
        try{
            JSONArray rows=new JSONArray(read(new File(directory,"interactions.json"),MAX_HISTORY_BYTES));
            for(int i=Math.max(0,rows.length()-MAX_EVENTS);i<rows.length();i++){
                JSONObject j=rows.getJSONObject(i);String peer=j.getString("peer");
                if(peer.isEmpty()||digest(peer))events.add(new Event(j.getLong("at"),peer,j.getString("result"),j.getInt("sent"),j.getInt("received")));
            }
        }catch(Exception ignored){}
        historyLoaded=true;
    }
    private void persist(Peer p)throws Exception{
        ensureDirectory();Binding b=p.binding();
        JSONObject j=new JSONObject().put("version",1).put("format","roots.axon.subscription").put("transport","ble").put("peer",b.peer()).put("scope","root-knowledge")
            .put("topics","*").put("root",b.root()).put("after",b.after()).put("encounters",b.encounters())
            .put("label",p.label()).put("blocked",p.blocked()).put("first_seen",p.firstSeen()).put("last_seen",p.lastSeen())
            .put("sent",p.sent()).put("received",p.received()).put("result",p.result());
        write(path(b.peer()),j.toString(),MAX_BYTES);peers.put(b.peer(),p);
    }
    synchronized Snapshot snapshot(){
        load();loadHistory();
        List<Peer> list=new ArrayList<>(peers.values());
        list.sort(Comparator.comparingLong(Peer::lastSeen).reversed().thenComparing(p->p.binding().peer()));
        List<Event> history=new ArrayList<>(events);Collections.reverse(history);
        return new Snapshot(list,history);
    }
    synchronized int availableCount(){load();int count=0;for(Peer p:peers.values())if(!p.blocked())count++;return count;}
    synchronized boolean blocked(String peer){load();Peer p=peers.get(peer);return p!=null&&p.blocked();}
    synchronized Binding bind(String peer)throws Exception{
        load();if(!digest(peer))throw new IOException("Invalid peer");
        Peer p=peers.get(peer);long now=System.currentTimeMillis();
        if(p==null){
            if(peers.size()>=MAX_BINDINGS)throw new IOException("Encounter binding limit reached");
            p=new Peer(new Binding(peer,"","",0),"",false,now,now,0,0,"New binding");
        }
        if(p.blocked())throw new IOException("Peer blocked");
        Binding old=p.binding(),b=new Binding(peer,old.root(),old.after(),Math.min(Long.MAX_VALUE-1,old.encounters())+1);
        persist(new Peer(b,p.label(),false,p.firstSeen()==0?now:p.firstSeen(),now,p.sent(),p.received(),"Exchanging"));
        changed();return b;
    }
    synchronized void save(Binding b)throws Exception{
        load();Peer p=peers.get(b.peer());
        if(p==null||p.blocked()||p.binding().encounters()!=b.encounters())throw new IOException("Binding no longer active");
        persist(new Peer(b,p.label(),false,p.firstSeen(),p.lastSeen(),p.sent(),p.received(),p.result()));
    }
    synchronized void setBlocked(String id,boolean blocked)throws Exception{
        load();Peer p=peers.get(id);if(p==null)return;
        persist(new Peer(p.binding(),p.label(),blocked,p.firstSeen(),p.lastSeen(),p.sent(),p.received(),blocked?"Blocked":"Available for encounters"));
        try{event(id,blocked?"Blocked by you":"Unblocked by you",0,0);}finally{changed();}
    }
    synchronized void rename(String id,String label)throws Exception{
        load();Peer p=peers.get(id);if(p==null)return;
        label=label.trim();if(label.length()>60)throw new IOException("Label too long");
        persist(new Peer(p.binding(),label,p.blocked(),p.firstSeen(),p.lastSeen(),p.sent(),p.received(),p.result()));changed();
    }
    synchronized void outcome(String peer,String result,int sent,int received)throws Exception{
        load();Peer p=peers.get(peer);
        if(p!=null)persist(new Peer(p.binding(),p.label(),p.blocked(),p.firstSeen(),System.currentTimeMillis(),
            p.sent()+sent,p.received()+received,p.blocked()?"Blocked":result));
        try{event(peer,result,sent,received);}finally{changed();}
    }
    private void event(String peer,String result,int sent,int received)throws Exception{
        loadHistory();ensureDirectory();
        events.add(new Event(System.currentTimeMillis(),peer,result,sent,received));
        while(events.size()>MAX_EVENTS)events.remove(0);
        JSONArray rows=new JSONArray();
        for(Event e:events)rows.put(new JSONObject().put("at",e.at()).put("peer",e.peer()).put("result",e.result()).put("sent",e.sent()).put("received",e.received()));
        write(new File(directory,"interactions.json"),rows.toString(),MAX_HISTORY_BYTES);
    }
    synchronized void clearHistory()throws Exception{
        ensureDirectory();write(new File(directory,"interactions.json"),"[]",MAX_HISTORY_BYTES);events.clear();historyLoaded=true;
    }
}
