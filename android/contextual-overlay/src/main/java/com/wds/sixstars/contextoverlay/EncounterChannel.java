package com.wds.sixstars.contextoverlay;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Public Axon messages over a single protected connection.
 * REP/1 is the existing authenticated BLE connection; REP/2 is application
 * framing and identity proof over standard TLS. Archives are not separately
 * encrypted. The TLS path has no additional cipher or MAC. */
final class EncounterChannel {
    static final int MAX_FRAME=65536; // Legacy BLE control messages only.
    private static final int TLS_CONTROL_MAX=1024*1024;
    private static final int MAGIC=0x52455031,TLS_MAGIC=0x52455032;
    private static final byte[] LABEL="Roots REP/1 handshake".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TLS_LABEL="Roots REP/2 TLS identity".getBytes(StandardCharsets.US_ASCII);
    private final DataInputStream in;
    private final DataOutputStream out;
    private final SecretKeySpec sendKey,receiveKey;
    private final boolean tlsTransport;
    final String peerId;
    private long sent,received;

    private EncounterChannel(DataInputStream in,DataOutputStream out,byte[] send,byte[] receive,String peerId){
        this.in=in;this.out=out;sendKey=new SecretKeySpec(send,"AES");receiveKey=new SecretKeySpec(receive,"AES");this.peerId=peerId;tlsTransport=false;
    }
    private EncounterChannel(DataInputStream in,DataOutputStream out,String peerId){
        this.in=in;this.out=out;sendKey=null;receiveKey=null;this.peerId=peerId;tlsTransport=true;
    }
    boolean overTls(){return tlsTransport;}

    private static byte[] tlsHello(KeyPair identity)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(TLS_MAGIC);blob(out,identity.getPublic().getEncoded());
        byte[] nonce=new byte[32];new SecureRandom().nextBytes(nonce);out.write(nonce);
        return bytes.toByteArray();
    }
    private static PublicKey parseTlsHello(byte[] encoded)throws Exception{
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(encoded));
        if(in.readInt()!=TLS_MAGIC)throw new IOException("Unsupported TLS Axon protocol");
        PublicKey key=decode(blob(in,256));byte[] nonce=new byte[32];in.readFully(nonce);
        if(in.read()!=-1)throw new IOException("Trailing TLS Axon hello");
        return key;
    }
    /** Preserve the Axon signing identity independently of the TLS certificate.
     * Sign the ordered nonces and TLS server certificate hash. The expected
     * Axon key is verified before any inventory, credentials or data is sent.
     * This proof prevents relay across TLS endpoints with different certificates. */
    static EncounterChannel openOverTls(InputStream input,OutputStream output,KeyPair identity,
                                         boolean client,String expectedPeer,byte[] serverCertificateHash)throws Exception{
        if(serverCertificateHash.length!=32)throw new GeneralSecurityException("Invalid TLS certificate binding");
        DataInputStream in=new DataInputStream(input);DataOutputStream out=new DataOutputStream(output);
        byte[] local=tlsHello(identity),remote;
        if(client){blob(out,local);out.flush();remote=blob(in,1024);}
        else{remote=blob(in,1024);blob(out,local);out.flush();}
        PublicKey peer=parseTlsHello(remote);String id=identityId(peer);
        if(id.equals(identityId(identity.getPublic()))||(!expectedPeer.isEmpty()&&!expectedPeer.equals(id)))
            throw new GeneralSecurityException("TLS Axon identity mismatch");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream transcriptOut=new DataOutputStream(bytes);
        transcriptOut.write(TLS_LABEL);transcriptOut.write(serverCertificateHash);
        blob(transcriptOut,client?local:remote);blob(transcriptOut,client?remote:local);
        byte[] transcript=MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        byte[] sig=proof(transcript,client,identity.getPrivate()).sign(),other;
        // Server proves the binding before the client supplies its proof.
        if(client){
            other=blob(in,256);
            if(!proof(transcript,false,peer).verify(other))throw new GeneralSecurityException("Invalid TLS Axon server proof");
            blob(out,sig);out.flush();
        }else{
            blob(out,sig);out.flush();other=blob(in,256);
            if(!proof(transcript,true,peer).verify(other))throw new GeneralSecurityException("Invalid TLS Axon client proof");
        }
        return new EncounterChannel(in,out,id);
    }

    static KeyPair newEcKey()throws GeneralSecurityException{
        KeyPairGenerator gen=KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));return gen.generateKeyPair();
    }
    private static PublicKey decode(byte[] encoded)throws GeneralSecurityException{
        PublicKey result=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
        if(!(result instanceof java.security.interfaces.ECPublicKey))throw new GeneralSecurityException("Expected EC key");
        java.security.interfaces.ECPublicKey ec=(java.security.interfaces.ECPublicKey)result;
        AlgorithmParameters parameters=AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected=parameters.getParameterSpec(ECParameterSpec.class),actual=ec.getParams();
        if(!actual.getCurve().equals(expected.getCurve())||!actual.getGenerator().equals(expected.getGenerator())
            ||!actual.getOrder().equals(expected.getOrder())||actual.getCofactor()!=expected.getCofactor())
            throw new GeneralSecurityException("Expected P-256");
        return result;
    }
    private static void blob(DataOutputStream out,byte[] value)throws IOException{
        out.writeInt(value.length);out.write(value);
    }
    private static byte[] blob(DataInputStream in,int max)throws IOException{
        int n=in.readInt();if(n<1||n>max)throw new IOException("Invalid REP field length");
        byte[] result=new byte[n];in.readFully(result);return result;
    }
    private static byte[] hello(KeyPair identity,KeyPair ephemeral)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(MAGIC);blob(out,identity.getPublic().getEncoded());blob(out,ephemeral.getPublic().getEncoded());
        byte[] nonce=new byte[32];new SecureRandom().nextBytes(nonce);out.write(nonce);
        return bytes.toByteArray();
    }
    private record Hello(PublicKey identity,PublicKey ephemeral){}
    private static Hello parse(byte[] encoded)throws Exception{
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(encoded));
        if(in.readInt()!=MAGIC)throw new IOException("Unsupported encounter version");
        PublicKey identity=decode(blob(in,256)),ephemeral=decode(blob(in,256));
        byte[] nonce=new byte[32];in.readFully(nonce);if(in.read()!=-1)throw new IOException("Trailing handshake bytes");
        return new Hello(identity,ephemeral);
    }
    private static byte[] transcript(byte[] client,byte[] server)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.write(LABEL);blob(out,client);blob(out,server);return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
    }
    private static Signature proof(byte[] transcript,boolean client,Key key)throws GeneralSecurityException{
        Signature signature=Signature.getInstance("SHA256withECDSA");
        if(key instanceof PrivateKey)signature.initSign((PrivateKey)key);else signature.initVerify((PublicKey)key);
        signature.update((byte)(client?1:2));signature.update(transcript);return signature;
    }
    private static byte[] hmac(byte[] key,byte[] data)throws GeneralSecurityException{
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(data);
    }
    private static byte[] expand(byte[] prk,String context)throws GeneralSecurityException{
        byte[] label=context.getBytes(StandardCharsets.US_ASCII),info=Arrays.copyOf(label,label.length+1);
        info[info.length-1]=1;return hmac(prk,info);
    }
    static String identityId(PublicKey key)throws GeneralSecurityException{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(key.getEncoded());StringBuilder hex=new StringBuilder(64);
        for(byte b:digest)hex.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
        return hex.toString();
    }
    static EncounterChannel open(InputStream input,OutputStream output,KeyPair identity,boolean client)throws Exception{
        DataInputStream in=new DataInputStream(input);DataOutputStream out=new DataOutputStream(output);
        KeyPair ephemeral=newEcKey();byte[] local=hello(identity,ephemeral);
        // Small messages; role ordering also permits half-duplex embedded implementations.
        byte[] remote;
        if(client){blob(out,local);out.flush();remote=blob(in,1024);}
        else{remote=blob(in,1024);blob(out,local);out.flush();}
        Hello peer=parse(remote);
        String id=identityId(peer.identity());
        if(id.equals(identityId(identity.getPublic())))throw new GeneralSecurityException("Self encounter");
        byte[] transcript=transcript(client?local:remote,client?remote:local);
        byte[] sig=proof(transcript,client,identity.getPrivate()).sign();
        byte[] other;
        if(client){blob(out,sig);out.flush();other=blob(in,256);}
        else{other=blob(in,256);blob(out,sig);out.flush();}
        if(!proof(transcript,!client,peer.identity()).verify(other))throw new GeneralSecurityException("Invalid encounter proof");
        KeyAgreement agreement=KeyAgreement.getInstance("ECDH");
        agreement.init(ephemeral.getPrivate());agreement.doPhase(peer.ephemeral(),true);
        byte[] secret=agreement.generateSecret(),prk=hmac(transcript,secret);
        byte[] c2s=expand(prk,"Roots REP/1 client to server"),s2c=expand(prk,"Roots REP/1 server to client");
        try{return new EncounterChannel(in,out,client?c2s:s2c,client?s2c:c2s,id);}
        finally{Arrays.fill(secret,(byte)0);Arrays.fill(prk,(byte)0);Arrays.fill(c2s,(byte)0);Arrays.fill(s2c,(byte)0);}
    }
    private static Cipher cipher(int mode,SecretKeySpec key,long sequence)throws GeneralSecurityException{
        if(sequence<0||sequence==Long.MAX_VALUE)throw new GeneralSecurityException("REP sequence exhausted");
        byte[] nonce=ByteBuffer.allocate(12).putInt(0).putLong(sequence).array();
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode,key,new GCMParameterSpec(128,nonce));cipher.updateAAD(LABEL);cipher.updateAAD(nonce);return cipher;
    }
    private void sendRecord(byte[] data,int limit)throws Exception{
        if(data.length<1||data.length>limit)throw new IOException("Invalid Axon payload size");
        // TLS already protects these exact serialized/compressed bytes.
        // BLE retains its existing single connection-protection layer.
        if(tlsTransport)blob(out,data);
        else{blob(out,cipher(Cipher.ENCRYPT_MODE,sendKey,sent).doFinal(data));sent++;}
        out.flush();
    }
    private byte[] receiveRecord(int limit,int expected)throws Exception{
        byte[] data;
        if(tlsTransport)data=blob(in,limit);
        else{
            byte[] encrypted=blob(in,limit+16);
            if(encrypted.length<17)throw new IOException("Invalid protected BLE record");
            data=cipher(Cipher.DECRYPT_MODE,receiveKey,received).doFinal(encrypted);received++;
        }
        if(data.length<1||data.length>limit||(expected>=0&&data.length!=expected))throw new IOException("Invalid Axon payload length");
        return data;
    }
    void send(byte[] data)throws Exception{sendRecord(data,tlsTransport?TLS_CONTROL_MAX:MAX_FRAME);}
    byte[] receive()throws Exception{return receiveRecord(tlsTransport?TLS_CONTROL_MAX:MAX_FRAME,-1);}
    void sendPublicBytes(byte[] data,int limit)throws Exception{sendRecord(data,limit);}
    byte[] receivePublicBytes(int expected,int limit)throws Exception{return receiveRecord(limit,expected);}
}
