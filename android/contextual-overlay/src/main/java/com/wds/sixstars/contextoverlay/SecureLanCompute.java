package com.wds.sixstars.contextoverlay;

import android.net.Network;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
    static JSONObject exchange(Network network,String base,String secret,JSONObject payload,boolean probe)throws Exception{
        byte[] key=new byte[32];
        if(!secret.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid pairing");
        for(int i=0;i<key.length;i++)key[i]=(byte)Integer.parseInt(secret.substring(i*2,i*2+2),16);
        byte[] nonce=new byte[12];RANDOM.nextBytes(nonce);
        payload.put("at",System.currentTimeMillis());
        byte[] encrypted=crypt(Cipher.ENCRYPT_MODE,derive(key,REQUEST),nonce,REQUEST.getBytes(StandardCharsets.US_ASCII),payload.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject wire=new JSONObject().put("nonce",Base64.encodeToString(nonce,Base64.NO_WRAP)).put("data",Base64.encodeToString(encrypted,Base64.NO_WRAP));
        HttpURLConnection conn=(HttpURLConnection)network.openConnection(new URL(base+"/api/compute/v1/exchange"));
        try{
            conn.setInstanceFollowRedirects(false);conn.setUseCaches(false);conn.setRequestMethod("POST");conn.setDoOutput(true);
            conn.setConnectTimeout(probe?650:1500);conn.setReadTimeout(probe?1000:30000);
            conn.setRequestProperty("Content-Type","application/json");conn.setRequestProperty("Accept","application/json");
            byte[] body=wire.toString().getBytes(StandardCharsets.UTF_8);conn.setFixedLengthStreamingMode(body.length);
            try(java.io.OutputStream out=conn.getOutputStream()){out.write(body);}
            if(conn.getResponseCode()!=200)throw new java.io.IOException("Paired compute unavailable");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=conn.getInputStream()){
                byte[] b=new byte[2048];int n;
                while((n=in.read(b))!=-1){if(out.size()+n>98304)throw new java.io.IOException("Reply too large");out.write(b,0,n);}
            }
            JSONObject reply=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            byte[] replyNonce=Base64.decode(reply.getString("nonce"),Base64.DEFAULT);
            if(replyNonce.length!=12)throw new java.io.IOException("Invalid reply nonce");
            byte[] plain=crypt(Cipher.DECRYPT_MODE,derive(key,RESPONSE),replyNonce,nonce,Base64.decode(reply.getString("data"),Base64.DEFAULT));
            return new JSONObject(new String(plain,StandardCharsets.UTF_8));
        }finally{conn.disconnect();java.util.Arrays.fill(key,(byte)0);}
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
