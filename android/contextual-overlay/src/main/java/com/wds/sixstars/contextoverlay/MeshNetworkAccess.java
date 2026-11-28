package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/** A persistent network authority, with separate enrollment and member grants.
 * The root secret is non-exportable and is never included in an invitation. */
final class MeshNetworkAccess {
    private static final String ALIAS="roots.mesh.network-root.v1";
    private static final String STORE="AndroidKeyStore";
    private MeshNetworkAccess(){}

    static synchronized SecretKey loadOrCreate(Context context)throws Exception{
        KeyStore store=KeyStore.getInstance(STORE);store.load(null);
        File marker=new File(context.getNoBackupFilesDir(),"mesh-network-root.v1");
        java.security.Key key=store.getKey(ALIAS,null);
        if(key==null){
            if(marker.exists()||store.containsAlias(ALIAS))
                throw new GeneralSecurityException("Saved Mesh network root is unavailable; not replacing it automatically.");
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256,STORE);
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256).setKeySize(256).build());
            key=generator.generateKey();
        }
        if(!(key instanceof SecretKey))throw new GeneralSecurityException("Invalid Mesh network root.");
        SecretKey root=(SecretKey)key;
        // Verify the persisted root before advertising access. The marker
        // is a non-secret, domain-separated verifier, not an access token.
        String verifier=derive(root,"persistence","","");
        MeshTlsIdentity.rememberValue(marker,verifier,"Mesh network root");
        return root;
    }

    static String enrollment(SecretKey root,String issuer)throws GeneralSecurityException{
        requirePin(issuer);
        return derive(root,"enrollment",issuer,"");
    }
    static String member(SecretKey root,String issuer,String recipient)throws GeneralSecurityException{
        requirePin(issuer);requirePin(recipient);
        return derive(root,"member",issuer,recipient);
    }
    private static void requirePin(String pin)throws GeneralSecurityException{
        if(!validHex(pin))throw new GeneralSecurityException("Invalid Mesh certificate identity.");
    }
    static boolean validHex(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static String derive(SecretKey root,String purpose,String issuer,String member)throws GeneralSecurityException{
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(root);
        return MeshItem.hex(mac.doFinal(("roots.mesh.network.v1\n"+purpose+"\n"+issuer+"\n"+member).getBytes(StandardCharsets.US_ASCII)));
    }
    static boolean equal(String expected,String actual){
        return validHex(expected)&&validHex(actual)&&MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII),actual.getBytes(StandardCharsets.US_ASCII));
    }
    static String nonce(SecureRandom random){byte[] bytes=new byte[32];random.nextBytes(bytes);return MeshItem.hex(bytes);}
    private static byte[] challenge(String issuer,String member,String nonce)throws GeneralSecurityException{
        requirePin(issuer);requirePin(member);requirePin(nonce);
        return ("roots.mesh.member-proof.v1\n"+issuer+"\n"+member+"\n"+nonce).getBytes(StandardCharsets.US_ASCII);
    }
    static String proof(PrivateKey key,String issuer,String member,String nonce)throws GeneralSecurityException{
        Signature signer=Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);signer.update(challenge(issuer,member,nonce));
        return Base64.getEncoder().encodeToString(signer.sign());
    }
    static boolean verifyProof(String encodedCertificate,String encodedProof,String issuer,String member,String nonce){
        try{
            if(encodedCertificate==null||encodedCertificate.length()>8192||encodedProof==null||encodedProof.length()>512)return false;
            byte[] der=Base64.getDecoder().decode(encodedCertificate);
            X509Certificate cert=(X509Certificate)CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(der));
            cert.checkValidity();
            if(!"EC".equals(cert.getPublicKey().getAlgorithm())||
                !equal(member,MeshItem.hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()))))return false;
            Signature verifier=Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(cert.getPublicKey());verifier.update(challenge(issuer,member,nonce));
            return verifier.verify(Base64.getDecoder().decode(encodedProof));
        }catch(Exception e){return false;}
    }
}
