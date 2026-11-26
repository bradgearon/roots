package com.wds.sixstars.contextoverlay;

import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509TrustManager;

/** TLS trust anchored to the exact certificate fingerprint obtained locally
 * with get-key.cmd. Never accepts arbitrary certificates or host changes. */
final class DesktopTlsPin implements X509TrustManager {
    private final byte[] expected=new byte[32];
    DesktopTlsPin(String hex){
        if(!hex.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid certificate fingerprint");
        for(int i=0;i<32;i++)expected[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
    }
    private void verify(X509Certificate certificate)throws CertificateException{
        certificate.checkValidity();
        try{
            if(!MessageDigest.isEqual(expected,MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())))
                throw new CertificateException("Desktop certificate fingerprint changed");
        }catch(java.security.NoSuchAlgorithmException e){throw new CertificateException(e);}
    }
    @Override public void checkServerTrusted(X509Certificate[] chain,String authType)throws CertificateException{
        if(chain==null||chain.length==0)throw new CertificateException("Missing desktop certificate");
        verify(chain[0]);
    }
    @Override public void checkClientTrusted(X509Certificate[] chain,String authType)throws CertificateException{throw new CertificateException("Client certificate not supported");}
    @Override public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
    boolean matchesPeer(SSLSession session){
        try{verify((X509Certificate)session.getPeerCertificates()[0]);return true;}
        catch(Exception e){return false;}
    }
}
