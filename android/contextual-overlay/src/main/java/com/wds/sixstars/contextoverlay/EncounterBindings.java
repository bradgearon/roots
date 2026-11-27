package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** A distinct persistent .axon per encounter identity. No MAC, IP, name or
 * location is retained. The binding records public exchange only, never
 * runtime inference authorization or LAN invitation credentials. */
final class EncounterBindings {
    private static final int MAX_BINDINGS=1024,MAX_BYTES=4096;
    private final File directory;
    record Binding(String peer,String root,String after,long encounters){}
    EncounterBindings(Context context){directory=new File(context.getNoBackupFilesDir(),"encounter-axons");}
    static boolean digest(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private File path(String peer)throws IOException{
        if(!digest(peer))throw new IOException("Invalid encounter identity");
        return new File(directory,peer+".axon");
    }
    synchronized int count(){
        String[] names=directory.list((dir,name)->name.matches("[0-9a-f]{64}\\.axon"));
        return names==null?0:names.length;
    }
    synchronized Binding bind(String peer)throws Exception{
        File path=path(peer);
        Binding previous;
        AtomicFile file=new AtomicFile(path);
        try(FileInputStream input=file.openRead()){
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] chunk=new byte[1024];int n;
            while((n=input.read(chunk))!=-1){if(buffer.size()+n>MAX_BYTES)throw new IOException("Invalid binding length");buffer.write(chunk,0,n);}
            byte[] data=buffer.toByteArray();
            if(data.length>MAX_BYTES)throw new IOException("Invalid binding length");
            JSONObject j=new JSONObject(new String(data,StandardCharsets.UTF_8));
            if(j.getInt("version")!=1||!peer.equals(j.getString("peer")))throw new IOException("Invalid binding");
            String root=j.getString("root"),after=j.getString("after");
            if((!root.isEmpty()&&!digest(root))||(!after.isEmpty()&&!digest(after)))throw new IOException("Invalid checkpoint");
            previous=new Binding(peer,root,after,j.getLong("encounters"));
        }catch(FileNotFoundException missing){
            if(count()>=MAX_BINDINGS)throw new IOException("Encounter binding limit reached");
            previous=new Binding(peer,"","",0);
        }
        Binding result=new Binding(peer,previous.root(),previous.after(),Math.min(Long.MAX_VALUE-1,previous.encounters()+1));
        save(result);return result;
    }
    synchronized void save(Binding value)throws Exception{
        File path=path(value.peer());
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create binding store");
        byte[] bytes=new JSONObject().put("version",1).put("peer",value.peer()).put("scope","published")
            .put("topics","*").put("root",value.root()).put("after",value.after()).put("encounters",value.encounters())
            .toString().getBytes(StandardCharsets.UTF_8);
        AtomicFile file=new AtomicFile(path);FileOutputStream out=file.startWrite();
        try{out.write(bytes);file.finishWrite(out);}catch(Exception failure){file.failWrite(out);throw failure;}
    }
    synchronized void clear(){
        File[] files=directory.listFiles();
        if(files!=null)for(File file:files)if(file.getName().matches("[0-9a-f]{64}\\.axon(?:\\.bak|\\.new)?"))file.delete();
    }
}
