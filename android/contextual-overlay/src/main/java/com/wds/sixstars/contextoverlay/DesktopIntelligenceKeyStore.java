package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Independent capability key for desktop intelligence, encrypted by Android
 * Keystore and stored outside backups. It never shares translation credentials. */
final class DesktopIntelligenceKeyStore {
    private static final String ALIAS="roots.desktop.intelligence.access.v1";
    private static final byte[] AAD="roots.desktop.intelligence.v1".getBytes(StandardCharsets.US_ASCII);
    private DesktopIntelligenceKeyStore(){}
    private static AtomicFile file(Context context){return new AtomicFile(new File(context.getNoBackupFilesDir(),"desktop-intelligence.access"));}
    private static SecretKey key(boolean create)throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return ((KeyStore.SecretKeyEntry)store.getEntry(ALIAS,null)).getSecretKey();
        if(!create)throw new IOException("No intelligence key.");
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }
    static synchronized void save(Context context,String value)throws IOException{
        String paired=DesktopIntelligenceProtocol.pairedKey(value);
        try{
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true));cipher.updateAAD(AAD);
            byte[] encrypted=cipher.doFinal(paired.getBytes(StandardCharsets.US_ASCII));
            JSONObject json=new JSONObject().put("version",1).put("iv",Base64.getEncoder().encodeToString(cipher.getIV()))
                .put("data",Base64.getEncoder().encodeToString(encrypted));
            AtomicFile file=file(context);FileOutputStream out=file.startWrite();
            try{out.write(json.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
            catch(Exception e){file.failWrite(out);throw e;}
        }catch(Exception error){throw new IOException("Could not securely save the desktop intelligence key. Try saving it again.");}
    }
    static synchronized String read(Context context){
        try{
            AtomicFile file=file(context);
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(FileInputStream in=file.openRead()){
                byte[] chunk=new byte[512];int n;
                while((n=in.read(chunk))!=-1){if(buffer.size()+n>2048)return "";buffer.write(chunk,0,n);}
            }
            byte[] bytes=buffer.toByteArray();
            JSONObject json=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            if(json.getInt("version")!=1)return "";
            byte[] iv=Base64.getDecoder().decode(json.getString("iv"));if(iv.length!=12)return "";
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,iv));cipher.updateAAD(AAD);
            return DesktopIntelligenceProtocol.pairedKey(new String(cipher.doFinal(Base64.getDecoder().decode(json.getString("data"))),StandardCharsets.US_ASCII));
        }catch(Exception error){return "";}
    }
    static boolean configured(Context context){return !read(context).isEmpty();}
    static synchronized void clear(Context context)throws IOException{
        AtomicFile file=file(context);file.delete();
        try{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);store.deleteEntry(ALIAS);}
        catch(Exception ignored){
            if(file.getBaseFile().exists()||new File(file.getBaseFile().getPath()+".bak").exists()||new File(file.getBaseFile().getPath()+".new").exists())
                throw new IOException("Could not remove the saved intelligence key.");
        }
    }
}
