package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

/** Internal Mesh only. This is not a public Axon address or credential.
 * Existing HTTPS certificate pin and bearer authorization still apply. */
final class DesktopWhirlwind {
    private static final String PREFIX="roots+iroh://";
    private static boolean loaded;
    private DesktopWhirlwind(){}
    record Route(String endpoint,String relay,String fallback,String canonical){}
    record Tunnel(int port,String transport) implements AutoCloseable{
        String base(){return "https://127.0.0.1:"+port;}
        @Override public void close(){nativeClose();}
    }
    static boolean remote(String value){return value!=null&&value.trim().toLowerCase(Locale.ROOT).startsWith(PREFIX);}
    static Route route(String raw){
        try{
            URI uri=new URI(raw.trim());
            String endpoint=uri.getRawAuthority();
            if(!"roots+iroh".equalsIgnoreCase(uri.getScheme())||endpoint==null||!endpoint.matches("[0-9a-fA-F]{64}")
                ||uri.getRawFragment()!=null||!(uri.getRawPath()==null||uri.getRawPath().isEmpty()||uri.getRawPath().equals("/")))throw new IllegalArgumentException();
            String query=uri.getQuery(),relay="",fallback="";
            if(query!=null){
                for(String field:query.split("&",-1)){
                    if(field.startsWith("relay=")&&relay.isEmpty()){
                        relay=field.substring(6);
                        URI rel=new URI(relay);
                        if(relay.length()>512||!"https".equalsIgnoreCase(rel.getScheme())||rel.getHost()==null
                            ||rel.getUserInfo()!=null||rel.getQuery()!=null||rel.getFragment()!=null
                            ||!(rel.getPath()==null||rel.getPath().isEmpty()||rel.getPath().equals("/")))throw new IllegalArgumentException();
                    }else if(field.startsWith("fallback=")&&fallback.isEmpty()){
                        fallback=field.substring(9);
                        if(fallback.length()>1024||!fallback.startsWith("/")||!fallback.matches("[A-Za-z0-9._:/-]+")||!fallback.matches(".*/p2p/[A-Za-z0-9]+"))throw new IllegalArgumentException();
                    }else throw new IllegalArgumentException();
                }
            }
            if(relay.isEmpty()&&fallback.isEmpty())throw new IllegalArgumentException();
            endpoint=endpoint.toLowerCase(Locale.ROOT);
            String suffix=(relay.isEmpty()?"":"relay="+relay)+(fallback.isEmpty()?"":(relay.isEmpty()?"":"&")+"fallback="+fallback);
            return new Route(endpoint,relay,fallback,PREFIX+endpoint+"?"+suffix);
        }catch(Exception error){throw new IllegalArgumentException("Use the desktop's private roots+iroh:// address with its relay or pinned fallback route.");}
    }
    private static synchronized void load()throws IOException{
        if(loaded)return;
        try{System.loadLibrary("roots_whirlwind");loaded=true;}
        catch(LinkageError e){throw new IOException("Whirlwind native library missing. Build Whirlwind for Android and reinstall Roots.");}
    }
    private static synchronized byte[] seed(Context context)throws IOException{
        AtomicFile file=new AtomicFile(new File(context.getNoBackupFilesDir(),"internal-whirlwind.key"));
        try(FileInputStream input=file.openRead()){
            byte[] result=new byte[32];new DataInputStream(input).readFully(result);
            if(input.read()!=-1){Arrays.fill(result,(byte)0);throw new IOException("Invalid internal identity");}
            return result;
        }catch(FileNotFoundException missing){
            // Android app-private no-backup storage. No public BLE identity reuse.
            byte[] result=new byte[32];new SecureRandom().nextBytes(result);
            FileOutputStream out=file.startWrite();
            try{out.write(result);file.finishWrite(out);return result;}
            catch(IOException e){file.failWrite(out);Arrays.fill(result,(byte)0);throw e;}
        }
    }
    static String identity(Context context)throws IOException{
        load();byte[] key=seed(context);
        try{return nativeIdentity(key);}finally{Arrays.fill(key,(byte)0);}
    }
    static Tunnel open(Context context,Route route)throws IOException{
        load();byte[] key=seed(context);
        try{
            String result=nativeOpen(context.getApplicationContext(),key,route.endpoint(),route.relay(),route.fallback());
            String[] fields=result.split(":");
            if(fields.length!=2||!fields[0].matches("[0-9]{1,5}")||!(fields[1].equals("iroh")||fields[1].equals("libp2p")))throw new IOException("Invalid tunnel response");
            int port=Integer.parseInt(fields[0]);if(port<1||port>65535)throw new IOException("Invalid tunnel port");
            return new Tunnel(port,fields[1]);
        }catch(RuntimeException|IOException error){nativeClose();throw error;}
        finally{Arrays.fill(key,(byte)0);}
    }
    static synchronized void cancel(){if(loaded)nativeClose();}
    private static native String nativeIdentity(byte[] seed)throws IOException;
    private static native String nativeOpen(Context context,byte[] seed,String endpoint,String relay,String fallback)throws IOException;
    private static native void nativeClose();
}
