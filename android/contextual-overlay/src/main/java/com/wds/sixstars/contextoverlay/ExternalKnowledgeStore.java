package com.wds.sixstars.contextoverlay;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.IOException;
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
    private ExternalKnowledgeStore(Context context){super(context,"roots-external-publications.db",null,1);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE publications(id TEXT PRIMARY KEY, body TEXT NOT NULL, peer TEXT NOT NULL, received_at INTEGER NOT NULL, promoted INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX external_received ON publications(received_at DESC)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int from,int to){throw new IllegalStateException("Unsupported external database migration");}
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
