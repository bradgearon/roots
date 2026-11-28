package com.wds.sixstars.contextoverlay;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.IOException;
import com.wds.sixstars.contextoverlay.tree.NativeTree;
import com.wds.sixstars.contextoverlay.tree.TreeRuntime;
import java.util.*;
import org.json.JSONObject;

/** Durable public receipts. Each verified publication is also retained in the
 * main Tree as an external reference; its peer provenance is not an authority
 * or private capability. The promoted marker makes crash recovery idempotent. */
final class ExternalKnowledgeStore extends SQLiteOpenHelper {
    private static final int LIMIT=100000;
    private static ExternalKnowledgeStore instance;
    static synchronized ExternalKnowledgeStore get(Context c){
        if(instance==null)instance=new ExternalKnowledgeStore(c.getApplicationContext());
        return instance;
    }
    private ExternalKnowledgeStore(Context context){super(context,"roots-external-publications.db",null,2);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE publications(id TEXT PRIMARY KEY, body TEXT NOT NULL, peer TEXT NOT NULL, received_at INTEGER NOT NULL, promoted INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX external_received ON publications(received_at DESC)");
        createInventory(db);
    }
    private static void createInventory(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS inventory_progress(peer TEXT NOT NULL,bucket INTEGER NOT NULL,remote TEXT NOT NULL,local_hash TEXT NOT NULL,cursor TEXT NOT NULL,complete INTEGER NOT NULL,PRIMARY KEY(peer,bucket))");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int from,int to){
        if(from==1&&to==2){createInventory(db);return;}
        throw new IllegalStateException("Unsupported external database migration");
    }
    Map<Integer,KnowledgeManifest.Checkpoint> inventoryProgress(String peer)throws Exception{
        if(!EncounterBindings.digest(peer))throw new IOException("Invalid inventory identity");
        Map<Integer,KnowledgeManifest.Checkpoint> out=new HashMap<>();
        try(Cursor cur=getReadableDatabase().rawQuery("SELECT bucket,remote,local_hash,cursor,complete FROM inventory_progress WHERE peer=?",new String[]{peer})){
            while(cur.moveToNext()){
                int bucket=cur.getInt(0);String remote=cur.getString(1),local=cur.getString(2),after=cur.getString(3);
                if(bucket<0||bucket>=KnowledgeManifest.BUCKETS||!EncounterBindings.digest(remote)||!EncounterBindings.digest(local)||
                    (!after.isEmpty()&&KnowledgeManifest.bucket(after)!=bucket))continue;
                out.put(bucket,new KnowledgeManifest.Checkpoint(remote,local,after,cur.getInt(4)!=0));
            }
        }
        return out;
    }
    void saveInventoryProgress(String peer,int bucket,KnowledgeManifest.Part remote,KnowledgeManifest.Part local,String after,boolean complete)throws Exception{
        if(!EncounterBindings.digest(peer))throw new IOException("Invalid inventory identity");
        KnowledgeManifest.checkBucket(bucket);
        if(!after.isEmpty()&&KnowledgeManifest.bucket(after)!=bucket)throw new IOException("Invalid inventory checkpoint");
        ContentValues values=new ContentValues();values.put("peer",peer);values.put("bucket",bucket);
        values.put("remote",remote.root());values.put("local_hash",local.root());values.put("cursor",after);values.put("complete",complete?1:0);
        if(getWritableDatabase().insertWithOnConflict("inventory_progress",null,values,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IOException("Could not checkpoint inventory");
    }
    record InventoryUpdate(int bucket,KnowledgeManifest.Part remote,KnowledgeManifest.Part local,String after,boolean complete){}
    void saveInventoryProgressBatch(String peer,List<InventoryUpdate> updates)throws Exception{
        if(updates.isEmpty())return;
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            for(InventoryUpdate update:updates)saveInventoryProgress(peer,update.bucket(),update.remote(),update.local(),update.after(),update.complete());
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    record Publication(MeshItem item,String peer,long receivedAt,boolean promoted){}
    boolean receive(MeshItem item,String peer)throws Exception{
        if(!EncounterBindings.digest(peer)||!RootGraphExchange.fitsFrame(item))throw new IOException("Invalid or oversized external publication");
        String json=item.json().toString();
        if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw new IOException("Publication too large");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            try(Cursor cur=db.rawQuery("SELECT 1 FROM publications WHERE id=?",new String[]{item.id()})){
                if(cur.moveToFirst()){db.setTransactionSuccessful();return false;}
            }
            long count=android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM publications",null);
            if(count>=LIMIT)throw new IOException("External publication storage limit reached");
            ContentValues values=new ContentValues();
            values.put("id",item.id());values.put("body",json);values.put("peer",peer);values.put("received_at",System.currentTimeMillis());
            db.insertOrThrow("publications",null,values);db.setTransactionSuccessful();return true;
        }finally{db.endTransaction();}
    }
    /** Commit an entire verified chunk as one receipt transaction, one native
     * Tree transaction and one marker transaction. A crash between the two
     * databases leaves pending receipts recoverable; ACK happens afterwards. */
    synchronized int retainBatch(List<MeshItem> items,String peer,TreeRuntime tree,long epoch)throws Exception{
        if(items.isEmpty()||items.size()>KnowledgeChunk.MAX_ITEMS||!EncounterBindings.digest(peer))throw new IOException("Invalid receipt batch");
        List<Publication> pending=new ArrayList<>();
        SQLiteDatabase db=getWritableDatabase();
        long now=System.currentTimeMillis();int added=0;
        db.beginTransaction();
        try{
            long count=android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM publications",null);
            for(MeshItem item:items){
                if(!RootGraphExchange.fitsArchive(item))throw new IOException("Invalid publication size");
                boolean exists=false;
                try(Cursor cur=db.rawQuery("SELECT body,peer,received_at,promoted FROM publications WHERE id=?",new String[]{item.id()})){
                    if(cur.moveToFirst()){
                        exists=true;
                        if(cur.getInt(3)==0){
                            MeshItem saved=MeshItem.parse(new JSONObject(cur.getString(0)));
                            if(!saved.id().equals(item.id())||!EncounterBindings.digest(cur.getString(1)))throw new IOException("Invalid pending receipt");
                            pending.add(new Publication(saved,cur.getString(1),cur.getLong(2),false));
                        }
                    }
                }
                if(exists)continue;
                if(count>=LIMIT)throw new IOException("External publication storage limit reached");
                ContentValues values=new ContentValues();
                values.put("id",item.id());values.put("body",item.json().toString());values.put("peer",peer);values.put("received_at",now);
                db.insertOrThrow("publications",null,values);count++;added++;
                pending.add(new Publication(item,peer,now,false));
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        if(!pending.isEmpty()){
            List<NativeTree.Observation> rows=new ArrayList<>();
            for(Publication publication:pending)rows.addAll(publication.item().receivedRows(publication.peer(),publication.receivedAt()));
            // A logical archive can contain thousands of items. The native
            // ABI still accepts 256 observations at a time; retain in bounded
            // native transactions and compile the derived snapshot once.
            tree.storeBulkExplicit(rows,epoch);
            db.beginTransaction();
            try{
                ContentValues mark=new ContentValues();mark.put("promoted",1);
                for(Publication publication:pending)db.update("publications",mark,"id=?",new String[]{publication.item().id()});
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            ExternalTreeImport.notifyImported();
        }
        return added;
    }
    long count(){return android.database.DatabaseUtils.longForQuery(getReadableDatabase(),"SELECT COUNT(*) FROM publications",null);}
    NavigableMap<String,MeshItem> catalog()throws Exception{
        NavigableMap<String,MeshItem> result=new TreeMap<>();
        try(Cursor cur=getReadableDatabase().rawQuery("SELECT id,body FROM publications ORDER BY id",null)){
            while(cur.moveToNext()){
                MeshItem item=MeshItem.parse(new JSONObject(cur.getString(1)));
                if(!item.id().equals(cur.getString(0)))throw new IOException("Invalid stored publication");
                result.put(item.id(),item);
            }
        }
        return result;
    }
    List<Publication> recent(int limit)throws Exception{
        if(limit<1||limit>200)throw new IllegalArgumentException("Invalid limit");
        List<Publication> result=new ArrayList<>();
        try(Cursor cur=getReadableDatabase().rawQuery("SELECT id,body,peer,received_at,promoted FROM publications ORDER BY received_at DESC,id LIMIT "+limit,null)){
            while(cur.moveToNext()){
                MeshItem item=MeshItem.parse(new JSONObject(cur.getString(1)));
                if(!item.id().equals(cur.getString(0)))throw new IOException("Invalid stored publication");
                result.add(new Publication(item,cur.getString(2),cur.getLong(3),cur.getInt(4)!=0));
            }
        }
        return result;
    }
    java.util.Set<String> pendingSet(){
        java.util.Set<String> ids=new java.util.HashSet<>();
        try(Cursor cur=getReadableDatabase().rawQuery("SELECT id FROM publications WHERE promoted=0",null)){
            while(cur.moveToNext())ids.add(cur.getString(0));
        }
        return ids;
    }
    java.util.List<String> pendingIds(String after,int limit){
        if(limit<1||limit>100)throw new IllegalArgumentException("Invalid pending page");
        java.util.List<String> ids=new java.util.ArrayList<>();
        try(Cursor cur=getReadableDatabase().rawQuery("SELECT id FROM publications WHERE promoted=0 AND id>? ORDER BY id LIMIT "+limit,new String[]{after})){
            while(cur.moveToNext())ids.add(cur.getString(0));
        }
        return ids;
    }
    synchronized void promote(String id,com.wds.sixstars.contextoverlay.tree.TreeRuntime tree,long epoch)throws Exception{
        if(!EncounterBindings.digest(id))throw new IOException("Invalid publication");
        MeshItem item;String peer;long receivedAt;boolean previouslyPromoted;
        try(Cursor cur=getReadableDatabase().rawQuery("SELECT body,peer,received_at,promoted FROM publications WHERE id=?",new String[]{id})){
            if(!cur.moveToFirst())throw new IOException("Publication is unavailable");
            item=MeshItem.parse(new JSONObject(cur.getString(0)));peer=cur.getString(1);
            receivedAt=cur.getLong(2);previouslyPromoted=cur.getInt(3)!=0;
        }
        if(!item.id().equals(id)||!EncounterBindings.digest(peer))throw new IOException("Invalid publication");
        // Native Tree uniqueness makes retry safe if the two databases were
        // interrupted between the Tree commit and the external marker.
        long inserted=item.storeReceived(tree,epoch,peer,receivedAt);
        ContentValues values=new ContentValues();values.put("promoted",1);
        getWritableDatabase().update("publications",values,"id=?",new String[]{id});
        if(inserted>0||!previouslyPromoted)ExternalTreeImport.notifyImported();
    }
}
