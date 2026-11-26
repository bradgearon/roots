package com.wds.sixstars.contextoverlay;

import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Same wire contract as SecureComputeRoute. No bearer token or plaintext
 * post sent even when the configured URL is HTTP. No redirects. */
final class SecureLanCompute {
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final String REQUEST="roots.compute.v1.request",RESPONSE="roots.compute.v1.response";
    static final class Packet {
        final String wire;
        final byte[] nonce,responseKey;
        Packet(String wire,byte[] nonce,byte[] responseKey){
            this.wire=wire;this.nonce=nonce;this.responseKey=responseKey;
        }
        JSONObject open(String text)throws Exception{
            JSONObject reply=new JSONObject(text);
            byte[] replyNonce=Base64.decode(reply.getString("nonce"),Base64.DEFAULT);
            if(replyNonce.length!=12)throw new java.io.IOException("Invalid reply nonce");
            byte[] plain=crypt(Cipher.DECRYPT_MODE,responseKey,replyNonce,nonce,Base64.decode(reply.getString("data"),Base64.DEFAULT));
            try{return new JSONObject(new String(plain,StandardCharsets.UTF_8));}
            finally{java.util.Arrays.fill(plain,(byte)0);}
        }
        void clear(){java.util.Arrays.fill(responseKey,(byte)0);}
    }
    static Packet seal(String secret,JSONObject payload)throws Exception{
        byte[] key=new byte[32];
        if(!secret.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid pairing");
        for(int i=0;i<key.length;i++)key[i]=(byte)Integer.parseInt(secret.substring(i*2,i*2+2),16);
        byte[] nonce=new byte[12];RANDOM.nextBytes(nonce);
        try{
            payload.put("at",System.currentTimeMillis());
            byte[] encrypted=crypt(Cipher.ENCRYPT_MODE,derive(key,REQUEST),nonce,REQUEST.getBytes(StandardCharsets.US_ASCII),payload.toString().getBytes(StandardCharsets.UTF_8));
            JSONObject wire=new JSONObject().put("nonce",Base64.encodeToString(nonce,Base64.NO_WRAP)).put("data",Base64.encodeToString(encrypted,Base64.NO_WRAP));
            return new Packet(wire.toString(),nonce,derive(key,RESPONSE));
        }finally{java.util.Arrays.fill(key,(byte)0);}
    }
    private static byte[] derive(byte[] key,String label)throws Exception{
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
        return mac.doFinal(label.getBytes(StandardCharsets.US_ASCII));
    }
    private static byte[] crypt(int mode,byte[] key,byte[] nonce,byte[] aad,byte[] data)throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        cipher.updateAAD(aad);return cipher.doFinal(data);
    }
}
