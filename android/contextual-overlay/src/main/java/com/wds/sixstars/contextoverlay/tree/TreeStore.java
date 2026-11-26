package com.wds.sixstars.contextoverlay.tree;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Local authority for observed text fragments; no inferred tweet IDs or authors. */
final class TreeStore extends SQLiteOpenHelper {
    static final int CAPACITY = 10000;
    static final int MAX_AGENTS = 32;
    TreeStore(Context context) {
        super(context, "roots-scroll-tree.db", null, 1);
        setWriteAheadLoggingEnabled(true);
    }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE fragments (seq INTEGER PRIMARY KEY AUTOINCREMENT, key TEXT NOT NULL UNIQUE, body TEXT NOT NULL, searchable TEXT NOT NULL, source TEXT NOT NULL, language TEXT NOT NULL, captured_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE agents (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, term TEXT NOT NULL)");
        db.execSQL("CREATE TABLE branch_members (agent_id INTEGER NOT NULL REFERENCES agents(id) ON DELETE CASCADE, fragment_seq INTEGER NOT NULL REFERENCES fragments(seq) ON DELETE CASCADE, PRIMARY KEY(agent_id,fragment_seq))");
        db.execSQL("CREATE INDEX branch_fragment ON branch_members(fragment_seq)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported Tree schema upgrade");
    }
    static String normalize(String value) { return value.trim().toLowerCase(Locale.ROOT); }

    static final class Observation {
        final String key, body, language;
        final long at;
        Observation(String key, String body, String language, long at) {
            this.key=key; this.body=body; this.language=language; this.at=at;
        }
    }
    static final class Fragment {
        final long seq, at; final String key, body, language;
        Fragment(Cursor c) {
            seq=c.getLong(0); key=c.getString(1); body=c.getString(2);
            language=c.getString(3); at=c.getLong(4);
        }
    }
    static final class Agent {
        final long id; final String name, term;
        Agent(Cursor c) { id=c.getLong(0); name=c.getString(1); term=c.getString(2); }
        @Override public String toString() { return name; }
    }
    static final class Batch {
        int inserted, duplicate, full;
    }
    static int count(SQLiteDatabase db) {
        try(Cursor c=db.rawQuery("SELECT count(*) FROM fragments", null)) {
            c.moveToFirst(); return c.getInt(0);
        }
    }
    int count() { return count(getReadableDatabase()); }

    Batch insert(List<Observation> observations, java.util.function.BooleanSupplier permitted) {
        Batch result = new Batch();
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            int total=count(db);
            for (Observation o:observations) {
                if (!permitted.getAsBoolean()) return new Batch(); // rollback batch
                if (total>=CAPACITY) {
                    try(Cursor c=db.rawQuery("SELECT 1 FROM fragments WHERE key=?",new String[]{o.key})) {
                        if(c.moveToFirst()) result.duplicate++; else result.full++;
                    }
                    continue;
                }
                ContentValues v=new ContentValues();
                v.put("key",o.key); v.put("body",o.body); v.put("searchable",normalize(o.body));
                v.put("source","x.android.home.fragment"); v.put("language",o.language); v.put("captured_ms",o.at);
                long seq=db.insertWithOnConflict("fragments",null,v,SQLiteDatabase.CONFLICT_IGNORE);
                if(seq<0) { result.duplicate++; continue; }
                total++; result.inserted++;
                db.execSQL("INSERT OR IGNORE INTO branch_members(agent_id,fragment_seq) SELECT id,? FROM agents WHERE instr(?,term)>0",
                        new Object[]{seq,normalize(o.body)});
            }
            if(!permitted.getAsBoolean()) return new Batch();
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return result;
    }
    List<Fragment> page(String query, long agentId, long before, int limit) {
        ArrayList<Fragment> result=new ArrayList<>();
        String sql="SELECT f.seq,f.key,f.body,f.language,f.captured_ms FROM fragments f WHERE f.seq<? AND instr(f.searchable,?)>0";
        ArrayList<String> args=new ArrayList<>();
        args.add(Long.toString(before)); args.add(normalize(query));
        if(agentId>0) { sql+=" AND EXISTS(SELECT 1 FROM branch_members b WHERE b.fragment_seq=f.seq AND b.agent_id=?)"; args.add(Long.toString(agentId)); }
        sql+=" ORDER BY f.seq DESC LIMIT ?";
        args.add(Integer.toString(Math.max(1,Math.min(100,limit))));
        try(Cursor c=getReadableDatabase().rawQuery(sql,args.toArray(new String[0]))) {
            while(c.moveToNext()) result.add(new Fragment(c));
        }
        return result;
    }
    List<Agent> agents() {
        ArrayList<Agent> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name,term FROM agents ORDER BY id",null)) {
            while(c.moveToNext()) result.add(new Agent(c));
        }
        return result;
    }
    long createAgent(String name,String term) {
        name=name.trim(); term=normalize(term);
        if(name.isEmpty() || name.length()>80 || term.isEmpty() || term.length()>120)
            throw new IllegalArgumentException("Use a name (1–80) and a literal search phrase (1–120).");
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            try(Cursor c=db.rawQuery("SELECT count(*) FROM agents",null)) {
                c.moveToFirst(); if(c.getInt(0)>=MAX_AGENTS) throw new IllegalStateException("Agent limit reached.");
            }
            ContentValues v=new ContentValues();v.put("name",name);v.put("term",term);
            long id=db.insertOrThrow("agents",null,v);
            db.execSQL("INSERT INTO branch_members(agent_id,fragment_seq) SELECT ?,seq FROM fragments WHERE instr(searchable,?)>0",new Object[]{id,term});
            db.setTransactionSuccessful();return id;
        } finally {db.endTransaction();}
    }
    void deleteAgent(long id) {getWritableDatabase().delete("agents","id=?",new String[]{Long.toString(id)});}
    void clear() {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {db.delete("branch_members",null,null);db.delete("agents",null,null);db.delete("fragments",null,null);db.setTransactionSuccessful();}
        finally {db.endTransaction();}
    }
    static final class Snapshot {
        final List<TreeGgufWriter.Row> rows; final long revision;
        Snapshot(List<TreeGgufWriter.Row> rows,long revision){this.rows=rows;this.revision=revision;}
    }
    Snapshot snapshot() {
        List<TreeGgufWriter.Row> rows=new ArrayList<>(); long rev=0;
        // Single ordered SELECT on a WAL reader; row mapping and revision come
        // from the same statement. No write transaction while compiling.
        try(Cursor c=getReadableDatabase().rawQuery("SELECT seq,key,body FROM fragments ORDER BY seq",null)) {
            while(c.moveToNext()) {rev=c.getLong(0);rows.add(new TreeGgufWriter.Row(c.getString(1),c.getString(2)));}
        }
        return new Snapshot(rows,rev);
    }
}
