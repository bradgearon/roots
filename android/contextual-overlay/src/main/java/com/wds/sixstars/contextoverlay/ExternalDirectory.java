package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.security.SecureRandom;
import java.util.*;
import org.json.*;

/** External directory client. No Internal Mesh route, identity or capability
 * secret is ever read. Search returns verified public cards, not bindings. */
final class ExternalDirectory {
    record Card(String peer,String name,List<String> topics,long revision,long expires,List<String> capabilities){}
    record Result(List<Card> cards,boolean partial){}
    private static boolean loaded;
    private ExternalDirectory(){}
    private static synchronized void load()throws IOException{
        if(loaded)return;
        try{System.loadLibrary("roots_whirlwind");loaded=true;}
        catch(LinkageError failure){throw new IOException("Build the Whirlwind Android library before searching the Internet directory.");}
    }
    static String seeds(Context c){return c.getSharedPreferences("roots_external_directory",Context.MODE_PRIVATE).getString("bootstrap","");}
    static void saveSeeds(Context c,String raw){
        if(raw.length()>4096)throw new IllegalArgumentException("Bootstrap address list too long");
        List<String> seeds=new ArrayList<>();
        for(String line:raw.split("\\R")){
            String addr=line.trim();if(addr.isEmpty())continue;
            if(addr.length()>512||!addr.startsWith("/")||!addr.matches("[A-Za-z0-9._:/-]+")||!addr.matches(".*/p2p/[A-Za-z0-9]+"))
                throw new IllegalArgumentException("Use external multiaddresses ending in /p2p/<peer-id>.");
            if(!seeds.contains(addr))seeds.add(addr);
        }
        if(seeds.size()>8)throw new IllegalArgumentException("Up to eight external bootstrap addresses");
        c.getSharedPreferences("roots_external_directory",Context.MODE_PRIVATE).edit().putString("bootstrap",String.join("\n",seeds)).apply();
    }
    private static synchronized byte[] seed(Context c)throws IOException{
        AtomicFile file=new AtomicFile(new File(c.getNoBackupFilesDir(),"external-directory.key"));
        try(FileInputStream input=file.openRead()){
            byte[] bytes=new byte[32];new DataInputStream(input).readFully(bytes);
            if(input.read()!=-1){Arrays.fill(bytes,(byte)0);throw new IOException("Invalid external identity");}
            return bytes;
        }catch(FileNotFoundException missing){
            byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
            FileOutputStream out=file.startWrite();
            try{out.write(bytes);file.finishWrite(out);return bytes;}
            catch(IOException failure){file.failWrite(out);Arrays.fill(bytes,(byte)0);throw failure;}
        }
    }
    static Result search(Context c,String term)throws Exception{
        term=term.trim().toLowerCase(Locale.ROOT);
        if(!term.isEmpty()&&!term.equals("*")&&!term.matches("[a-z0-9_-]{1,48}"))throw new IllegalArgumentException("Search one public topic tag, such as music or neuroscience, or leave blank to browse.");
        String seeds=seeds(c);
        if(seeds.isEmpty())throw new IOException("Configure at least one reachable External Mesh bootstrap node.");
        load();byte[] key=seed(c);
        try{
            String raw=nativeSearch(c.getApplicationContext(),key,seeds,term);
            if(raw==null||raw.length()>256*1024)throw new IOException("Invalid directory result");
            JSONObject json=new JSONObject(raw);JSONArray rows=json.getJSONArray("nodes");
            if(rows.length()>48)throw new IOException("Directory result limit exceeded");
            List<Card> cards=new ArrayList<>();
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);String peer=row.getString("peer_id"),name=row.getString("display_name");
                if(peer.length()>120||name.length()>120||!peer.matches("[A-Za-z0-9]+")||row.getInt("version")!=1)throw new IOException("Invalid verified card");
                long expires=row.getLong("expires");if(expires<=System.currentTimeMillis()/1000L)continue;
                cards.add(new Card(peer,name,strings(row.getJSONArray("topics"),12),row.getLong("revision"),expires,strings(row.getJSONArray("capabilities"),8)));
            }
            return new Result(List.copyOf(cards),json.getBoolean("partial"));
        }finally{Arrays.fill(key,(byte)0);}
    }
    private static List<String> strings(JSONArray array,int max)throws Exception{
        if(array.length()>max)throw new IOException("Directory field too large");
        List<String> result=new ArrayList<>();
        for(int i=0;i<array.length();i++){String value=array.getString(i);if(value.length()>48)throw new IOException("Directory tag too large");result.add(value);}
        return List.copyOf(result);
    }
    private static native String nativeSearch(Context c,byte[] seed,String seeds,String query)throws IOException;
}
