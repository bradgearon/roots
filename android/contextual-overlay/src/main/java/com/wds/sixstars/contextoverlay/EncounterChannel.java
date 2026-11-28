package com.wds.sixstars.contextoverlay;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.*;

/** REP/1 public Axon channel. Retain the original encrypted bind negotiation
 * for old peers; mutually capable peers switch to authenticated plaintext
 * public messages and whole compressed archives. No confidentiality is
 * promised for public knowledge. Private Mesh and GPU transports are separate. */
final class EncounterChannel {
    static final int MAX_FRAME=65536; // Legacy REP message limit only.
    private static final int PUBLIC_CONTROL_MAX=1024*1024;
    private static final byte[] PUBLIC_LABEL="Roots public plaintext v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PUBLIC_KEY_LABEL="Roots REP public integrity key v1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAGIC=0x52455031;
    private static final byte[] LABEL="Roots REP/1 handshake".getBytes(StandardCharsets.US_ASCII);
    private final DataInputStream in;
    private final DataOutputStream out;
    private final SecretKeySpec sendKey,receiveKey;
    final String peerId;
    private long sent,received;
    private byte[] publicSend,publicReceive;
    boolean publicPlaintext(){return publicSend!=null;}
    void enablePublicPlaintext()throws GeneralSecurityException{
        if(publicPlaintext())return;
        publicSend=hmac(sendKey.getEncoded(),PUBLIC_KEY_LABEL);
        publicReceive=hmac(receiveKey.getEncoded(),PUBLIC_KEY_LABEL);
    }
    private static Mac publicMac(byte[] key,long sequence,int length)throws GeneralSecurityException{
        if(sequence<0||sequence==Long.MAX_VALUE)throw new GeneralSecurityException("REP sequence exhausted");
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
        mac.update(PUBLIC_LABEL);mac.update(ByteBuffer.allocate(12).putLong(sequence).putInt(length).array());
        return mac;
    }
    private void sendPlain(byte[] data,int limit)throws Exception{
        if(!publicPlaintext()||data.length<1||data.length>limit)throw new IOException("Invalid public payload length");
        Mac mac=publicMac(publicSend,sent,data.length);
        mac.update(data);
        // One length, one unencrypted payload, one integrity tag. The stream
        // and network stack handle segmentation; there is no frame splitting.
        out.writeInt(data.length);out.write(data);out.write(mac.doFinal());out.flush();sent++;
    }
    private byte[] readPlain(int limit,int expected)throws Exception{
        if(!publicPlaintext())throw new IOException("Public plaintext not negotiated");
        int length=in.readInt();
        if(length<1||length>limit||(expected>=0&&length!=expected))throw new IOException("Invalid public payload length");
        byte[] bytes=new byte[length],tag=new byte[32];in.readFully(bytes);in.readFully(tag);
        Mac mac=publicMac(publicReceive,received,length);
        if(!MessageDigest.isEqual(mac.doFinal(bytes),tag))throw new GeneralSecurityException("Public payload integrity failure");
        received++;return bytes;
    }
    void sendPublicBytes(byte[] bytes,int limit)throws Exception{sendPlain(bytes,limit);}
    byte[] receivePublicBytes(int expected,int limit)throws Exception{return readPlain(limit,expected);}

    private EncounterChannel(DataInputStream in,DataOutputStream out,byte[] send,byte[] receive,String peerId){
        this.in=in;this.out=out;sendKey=new SecretKeySpec(send,"AES");receiveKey=new SecretKeySpec(receive,"AES");this.peerId=peerId;
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
    void send(byte[] data)throws Exception{
        if(publicPlaintext()){sendPlain(data,PUBLIC_CONTROL_MAX);return;}
        if(data.length<1||data.length>MAX_FRAME)throw new IOException("Invalid REP payload size");
        byte[] encrypted=cipher(Cipher.ENCRYPT_MODE,sendKey,sent).doFinal(data);
        blob(out,encrypted);out.flush();sent++;
    }
    byte[] receive()throws Exception{
        if(publicPlaintext())return readPlain(PUBLIC_CONTROL_MAX,-1);
        byte[] encrypted=blob(in,MAX_FRAME+16);
        if(encrypted.length<17)throw new IOException("Invalid encrypted REP frame");
        byte[] result=cipher(Cipher.DECRYPT_MODE,receiveKey,received).doFinal(encrypted);received++;return result;
    }
}
