package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local peer access, separate from subscription policy. Only authenticated
 * ciphertext is persisted, under noBackup with a non-exportable Keystore key.
 * Member grants survive listener restarts and normal app updates. */
final class AxonPeerStore {
    private static final String KEY_ALIAS="roots.axon.peer-access.v1";
    private static final String FILE_NAME="subscription.axon-access";
    private static final String TYPE="roots.axon.peer-access";
    private static final int MAGIC=0x52415831, IV_BYTES=12, TAG_BITS=128;
    private static final int MAX_BYTES=16384;
    private AxonPeerStore(){}

    private static AtomicFile file(Context context){
        return new AtomicFile(new File(context.getNoBackupFilesDir(),FILE_NAME));
    }
    private static boolean hasFile(AtomicFile file){
        return file.getBaseFile().exists()||new File(file.getBaseFile().getPath()+".bak").exists();
    }
    /** Missing access is a normal migration state for an older policy-only Axon. */
    static synchronized List<MeshPeer.Invite> load(Context context,AxonSubscription policy)throws IOException{
        if(policy==null)return Collections.emptyList();
        byte[] sealed=null,plain=null;
        try{
            AtomicFile file=file(context);
            if(!hasFile(file))return Collections.emptyList();
            try(FileInputStream input=file.openRead()){sealed=readBounded(input);}
            plain=decrypt(sealed,key(false),policy);
            return decode(plain,policy);
        }catch(Exception failure){
            // Do not retain provider/parser causes: callers may display errors.
            throw new IOException("Saved peer access could not be opened. Paste the bound peers' current invitations and save them again.");
        }finally{
            if(plain!=null)Arrays.fill(plain,(byte)0);
            if(sealed!=null)Arrays.fill(sealed,(byte)0);
        }
    }
    /** Replaces the selected grants. Every entry must match the supplied policy. */
    static synchronized void save(Context context,AxonSubscription policy,List<MeshPeer.Invite> peers)throws IOException{
        byte[] plain=null,sealed=null;
        try{
            plain=encode(peers,policy);
            sealed=encrypt(plain,key(true),policy);
            AtomicFile file=file(context);
            FileOutputStream output=file.startWrite();
            try{output.write(sealed);file.finishWrite(output);}
            catch(Exception failure){file.failWrite(output);throw failure;}
        }catch(Exception failure){
            throw new IOException("Peer access could not be saved securely. Try again or remove the saved binding and bind again.");
        }finally{
            if(plain!=null)Arrays.fill(plain,(byte)0);
            if(sealed!=null)Arrays.fill(sealed,(byte)0);
        }
    }
    /** Persist a member grant received from the already pinned source.
     * Compare the exact previous grant so a concurrent rebind or address edit
     * is never overwritten by an in-flight request. */
    static synchronized boolean refreshDerivedGrant(Context context,MeshPeer.Invite previous,String derived)throws IOException{
        if(!MeshNetworkAccess.validHex(derived))throw invalid();
        try{
            AxonSubscription policy=AxonSubscription.load(context);
            if(policy==null||!policy.pins.contains(previous.pin()))return false;
            List<MeshPeer.Invite> current=new ArrayList<>(load(context,policy));
            for(int i=0;i<current.size();i++){
                MeshPeer.Invite saved=current.get(i);
                if(!saved.pin().equals(previous.pin())||!saved.host().equals(previous.host()))continue;
                if(MeshNetworkAccess.equal(saved.token(),derived))return true;
                if(!MeshNetworkAccess.equal(saved.token(),previous.token()))return false;
                current.set(i,new MeshPeer.Invite(saved.host(),saved.pin(),derived));
                save(context,policy,current);return true;
            }
            return false;
        }catch(Exception failure){throw new IOException("Member grant could not be saved securely.");}
    }
    static synchronized void clear(Context context){
        AtomicFile file=file(context);file.delete();
        boolean keyRemoved=false;
        try{
            KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
            keys.deleteEntry(KEY_ALIAS);keyRemoved=true;
        }catch(Exception ignored){/* Deleting every ciphertext file also removes access. */}
        boolean remaining=hasFile(file)||new File(file.getBaseFile().getPath()+".new").exists();
        if(remaining&&!keyRemoved)throw new IllegalStateException("Saved peer access could not be removed. Try again.");
    }
    private static SecretKey key(boolean create)throws Exception{
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
        KeyStore.Entry entry=keys.getEntry(KEY_ALIAS,null);
        if(entry instanceof KeyStore.SecretKeyEntry)return ((KeyStore.SecretKeyEntry)entry).getSecretKey();
        if(!create)throw invalid();
        if(entry!=null)throw invalid();
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).setKeySize(256).build());
        return generator.generateKey();
    }

    // These helpers use ordinary JCA and streams, so authenticated storage and
    // policy isolation can be checked on a host JVM without a mock Keystore.
    static byte[] readBounded(InputStream input)throws IOException{
        byte[] bytes=new byte[MAX_BYTES+1];int total=0;
        while(total<bytes.length){int count=input.read(bytes,total,bytes.length-total);if(count<0)break;total+=count;}
        if(total>MAX_BYTES)throw invalid();
        return Arrays.copyOf(bytes,total);
    }
    private static byte[] associatedData(AxonSubscription policy)throws IOException{
        if(policy==null||policy.pins.isEmpty()||policy.pins.size()>8)throw invalid();
        List<String> pins=new ArrayList<>(policy.pins);Collections.sort(pins);
        // Topic/model edits retain access; changing the bound identity set does not.
        return (TYPE+"/1\n"+String.join("\n",pins)).getBytes(StandardCharsets.US_ASCII);
    }
    static byte[] encrypt(byte[] plain,SecretKey key,AxonSubscription policy)throws IOException{
        try{
            if(plain==null||plain.length==0||plain.length>MAX_BYTES-4-IV_BYTES-TAG_BITS/8)throw invalid();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);
            cipher.updateAAD(associatedData(policy));
            byte[] iv=cipher.getIV(),encrypted=cipher.doFinal(plain);
            if(iv.length!=IV_BYTES)throw invalid();
            return ByteBuffer.allocate(4+IV_BYTES+encrypted.length).putInt(MAGIC).put(iv).put(encrypted).array();
        }catch(Exception failure){throw invalid();}
    }
    static byte[] decrypt(byte[] sealed,SecretKey key,AxonSubscription policy)throws IOException{
        try{
            if(sealed==null||sealed.length<4+IV_BYTES+TAG_BITS/8||sealed.length>MAX_BYTES)throw invalid();
            ByteBuffer input=ByteBuffer.wrap(sealed);
            if(input.getInt()!=MAGIC)throw invalid();
            byte[] iv=new byte[IV_BYTES];input.get(iv);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(TAG_BITS,iv));
            cipher.updateAAD(associatedData(policy));
            return cipher.doFinal(sealed,4+IV_BYTES,input.remaining());
        }catch(Exception failure){throw invalid();}
    }
    static byte[] encode(List<MeshPeer.Invite> peers,AxonSubscription policy)throws IOException{
        try{
            List<MeshPeer.Invite> checked=validate(peers,policy);JSONArray rows=new JSONArray();
            for(MeshPeer.Invite peer:checked)rows.put(new JSONObject().put("host",peer.host()).put("pin",peer.pin()).put("token",peer.token()));
            byte[] bytes=new JSONObject().put("type",TYPE).put("version",1).put("peers",rows).toString().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>MAX_BYTES-4-IV_BYTES-TAG_BITS/8)throw invalid();
            return bytes;
        }catch(Exception failure){throw invalid();}
    }
    static List<MeshPeer.Invite> decode(byte[] plain,AxonSubscription policy)throws IOException{
        try{
            if(plain==null||plain.length==0||plain.length>MAX_BYTES-4-IV_BYTES-TAG_BITS/8)throw invalid();
            JSONObject json=new JSONObject(new String(plain,StandardCharsets.UTF_8));
            if(!TYPE.equals(json.getString("type"))||json.getInt("version")!=1)throw invalid();
            JSONArray rows=json.getJSONArray("peers");if(rows.length()==0||rows.length()>8)throw invalid();
            List<MeshPeer.Invite> peers=new ArrayList<>();
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);
                peers.add(new MeshPeer.Invite(row.getString("host"),row.getString("pin"),row.getString("token")));
            }
            return validate(peers,policy);
        }catch(Exception failure){throw invalid();}
    }
    private static List<MeshPeer.Invite> validate(List<MeshPeer.Invite> peers,AxonSubscription policy)throws IOException{
        if(policy==null||peers==null||peers.isEmpty()||peers.size()>8)throw invalid();
        List<MeshPeer.Invite> checked=new ArrayList<>();Set<String> hosts=new HashSet<>(),pins=new HashSet<>();
        for(MeshPeer.Invite peer:peers){
            if(peer==null||peer.host()==null||!peer.host().matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")||
                peer.pin()==null||!peer.pin().matches("[0-9a-f]{64}")||peer.token()==null||!peer.token().matches("[0-9a-f]{64}")||
                !policy.pins.contains(peer.pin()))throw invalid();
            StringBuilder host=new StringBuilder();
            for(String part:peer.host().split("\\.")){
                int octet=Integer.parseInt(part);if(octet>255)throw invalid();
                if(host.length()>0)host.append('.');host.append(octet);
            }
            if(!hosts.add(host.toString())||!pins.add(peer.pin()))throw invalid();
            checked.add(new MeshPeer.Invite(host.toString(),peer.pin(),peer.token()));
        }
        return Collections.unmodifiableList(checked);
    }
    private static IOException invalid(){return new IOException("Saved peer access is invalid or unavailable.");}
}
