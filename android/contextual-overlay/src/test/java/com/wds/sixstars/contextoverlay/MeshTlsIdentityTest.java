package com.wds.sixstars.contextoverlay;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import org.junit.Test;
import static org.junit.Assert.*;

/** JVM signatures are real, but AndroidKeyStore authorization/migration is
 * checked by the production self-check on the actual Android device. */
public class MeshTlsIdentityTest {
    private static KeyPair pair()throws GeneralSecurityException{
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    @Test public void acceptsRealP256RawHandshakeSignatures()throws Exception{
        KeyPair key=pair();
        MeshTlsIdentity.verifySigning(key.getPrivate(),key.getPublic());
    }

    @Test public void rejectsAKeyAndCertificateWithDifferentPublicKeys()throws Exception{
        KeyPair key=pair(),other=pair();
        GeneralSecurityException error=assertThrows(GeneralSecurityException.class,
            ()->MeshTlsIdentity.verifySigning(key.getPrivate(),other.getPublic()));
        assertTrue(error.getMessage().contains("could not complete TLS signing"));
        assertNotNull(error.getCause());
    }

    @Test public void rejectsAnOpaqueKeyWithoutASigningProvider()throws Exception{
        PrivateKey unavailable=new PrivateKey(){
            @Override public String getAlgorithm(){return "EC";}
            @Override public String getFormat(){return null;}
            @Override public byte[] getEncoded(){throw new IllegalStateException("PRIVATE_PROVIDER_DIAGNOSTIC");}
        };
        GeneralSecurityException error=assertThrows(GeneralSecurityException.class,
            ()->MeshTlsIdentity.verifySigning(unavailable,pair().getPublic()));
        assertFalse(error.getMessage().contains("PRIVATE_PROVIDER_DIAGNOSTIC"));
        assertTrue(error.getMessage().contains("this device"));
    }

    @Test public void rejectsIncompleteIdentityBeforeAdvertisingReadiness()throws Exception{
        assertThrows(GeneralSecurityException.class,()->MeshTlsIdentity.verifySigning(null,pair().getPublic()));
        assertThrows(GeneralSecurityException.class,()->MeshTlsIdentity.verifySigning(pair().getPrivate(),null));
    }
}
