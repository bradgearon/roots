package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.util.AtomicFile;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Date;
import javax.security.auth.x500.X500Principal;

/** Android keeps Mesh private keys non-exportable. TLS signs an already hashed
 * handshake, so an EC key must permit NONEwithECDSA as well as certificate
 * signing. Legacy keys cannot gain that authorization after generation. */
final class MeshTlsIdentity {
    private static final String LEGACY_ALIAS="roots.mesh.tls.v1";
    private static final String CURRENT_ALIAS="roots.mesh.tls.v2";
    private static final String STORE="AndroidKeyStore";

    record Identity(String alias,PrivateKey key,X509Certificate certificate,boolean upgraded){
        @Override public String toString(){return "Mesh TLS identity "+alias+" (key and certificate omitted)";}
    }
    private MeshTlsIdentity(){}

    /** Prefer a completed migration, otherwise retain an already compatible
     * legacy identity. A replacement is generated without deleting the old key.
     * upgraded is true only when this invocation creates that replacement. */
    static synchronized Identity loadOrCreate(Context context)throws GeneralSecurityException,IOException{
        KeyStore store=KeyStore.getInstance(STORE);store.load(null);
        File marker=new File(context.getNoBackupFilesDir(),"mesh-tls-identity.v1");
        String previous=readValue(marker);
        if(previous!=null){
            int split=previous.indexOf('\n');
            if(split<0)throw new GeneralSecurityException("Saved Mesh identity is invalid; not replacing it.");
            String alias=previous.substring(0,split);
            if((!alias.equals(LEGACY_ALIAS)&&!alias.equals(CURRENT_ALIAS))||!store.containsAlias(alias))
                throw new GeneralSecurityException("Saved Mesh identity is unavailable; not replacing it automatically.");
            Identity identity=validated(store,alias,false);
            rememberIdentity(marker,identity);
            return identity;
        }
        // Adopt the existing compatible key once, including already-migrated v2.
        if(store.containsAlias(CURRENT_ALIAS))return rememberIdentity(marker,validated(store,CURRENT_ALIAS,false));
        boolean legacyExists=store.containsAlias(LEGACY_ALIAS);
        if(legacyExists){
            Identity legacy=read(store,LEGACY_ALIAS,false);
            if(permitsTlsSigning(legacy.key()))return rememberIdentity(marker,validate(legacy));
        }

        SecureRandom random=new SecureRandom();
        KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,STORE);
        generator.initialize(new KeyGenParameterSpec.Builder(CURRENT_ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
            // Conscrypt delegates opaque EC keys to NONEwithECDSA. SHA-only
            // authorization permits certificate creation but fails TLS signing.
            .setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512)
            .setCertificateSubject(new X500Principal("CN=Roots Mesh"))
            .setCertificateSerialNumber(new BigInteger(128,random).add(BigInteger.ONE))
            .setCertificateNotBefore(new Date(System.currentTimeMillis()-86400000L))
            .setCertificateNotAfter(new Date(System.currentTimeMillis()+5L*365*86400000L))
            .build());
        generator.generateKeyPair();
        return rememberIdentity(marker,validated(store,CURRENT_ALIAS,legacyExists));
    }

    static String fingerprint(Identity identity)throws GeneralSecurityException{
        return MeshItem.hex(MessageDigest.getInstance("SHA-256").digest(identity.certificate().getEncoded()));
    }
    private static Identity rememberIdentity(File marker,Identity identity)throws GeneralSecurityException,IOException{
        rememberValue(marker,identity.alias()+"\n"+fingerprint(identity),"Mesh TLS identity");
        return identity;
    }
    private static String readValue(File marker)throws IOException{
        AtomicFile file=new AtomicFile(marker);
        if(!marker.exists()&&!new File(marker.getPath()+".bak").exists())return null;
        try(FileInputStream input=file.openRead()){
            byte[] bytes=new byte[513];int total=0;
            while(total<bytes.length){int count=input.read(bytes,total,bytes.length-total);if(count<0)break;total+=count;}
            if(total==0||total>512)throw new IOException("Invalid Mesh persistence marker.");
            return new String(bytes,0,total,StandardCharsets.US_ASCII);
        }
    }
    /** Atomic pin/root verifier: an existing identity must never silently rotate. */
    static synchronized void rememberValue(File marker,String value,String label)throws IOException,GeneralSecurityException{
        String previous=readValue(marker);
        if(previous!=null){
            if(!MessageDigest.isEqual(previous.getBytes(StandardCharsets.US_ASCII),value.getBytes(StandardCharsets.US_ASCII)))
                throw new GeneralSecurityException(label+" changed unexpectedly; not replacing the saved identity.");
            return;
        }
        AtomicFile file=new AtomicFile(marker);
        FileOutputStream output=file.startWrite();
        try{output.write(value.getBytes(StandardCharsets.US_ASCII));file.finishWrite(output);}
        catch(IOException|RuntimeException failure){file.failWrite(output);throw failure;}
    }

    private static Identity read(KeyStore store,String alias,boolean upgraded)throws GeneralSecurityException{
        java.security.Key key=store.getKey(alias,null);
        java.security.cert.Certificate certificate=store.getCertificate(alias);
        if(!(key instanceof PrivateKey)||!(certificate instanceof X509Certificate))
            throw new GeneralSecurityException("This device's saved Mesh TLS identity is incomplete. The listener has not started.");
        return new Identity(alias,(PrivateKey)key,(X509Certificate)certificate,upgraded);
    }

    private static boolean permitsTlsSigning(PrivateKey key)throws GeneralSecurityException{
        KeyInfo info=KeyFactory.getInstance(key.getAlgorithm(),STORE).getKeySpec(key,KeyInfo.class);
        return (info.getPurposes()&KeyProperties.PURPOSE_SIGN)!=0&&
            Arrays.asList(info.getDigests()).contains(KeyProperties.DIGEST_NONE);
    }

    private static Identity validated(KeyStore store,String alias,boolean upgraded)throws GeneralSecurityException{
        Identity identity=read(store,alias,upgraded);
        if(!permitsTlsSigning(identity.key()))
            throw new GeneralSecurityException("This device's Mesh TLS key does not permit handshake signing. Update Roots on this device before starting its listener.");
        return validate(identity);
    }

    private static Identity validate(Identity identity)throws GeneralSecurityException{
        identity.certificate().checkValidity();
        verifySigning(identity.key(),identity.certificate().getPublicKey());
        return identity;
    }

    /** This same operation runs on Android before a listener is advertised.
     * Host tests exercise real signatures; only Android can check its Keystore
     * authorization and hardware/provider support. Never exports private keys. */
    static void verifySigning(PrivateKey privateKey,PublicKey publicKey)throws GeneralSecurityException{
        try{
            if(privateKey==null||publicKey==null||!"EC".equals(privateKey.getAlgorithm())||!"EC".equals(publicKey.getAlgorithm()))
                throw new GeneralSecurityException("Mesh TLS requires an EC key pair.");
            byte[] digest=new byte[32];new SecureRandom().nextBytes(digest);
            Signature signer=Signature.getInstance("NONEwithECDSA");
            signer.initSign(privateKey);signer.update(digest);
            byte[] proof=signer.sign();
            Signature verifier=Signature.getInstance("NONEwithECDSA");
            verifier.initVerify(publicKey);verifier.update(digest);
            if(!verifier.verify(proof))throw new GeneralSecurityException("Mesh key and certificate do not match.");
        }catch(GeneralSecurityException|RuntimeException error){
            // Providers may include device-specific messages. Keep the local UI
            // explanation fixed, and retain the cause for typed diagnostics.
            throw new GeneralSecurityException("This device's secure Mesh key could not complete TLS signing. Update Roots on this device and try Start listening again.",error);
        }
    }
}
