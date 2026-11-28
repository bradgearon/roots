package com.wds.sixstars.contextoverlay;

import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509TrustManager;
import static com.wds.sixstars.contextoverlay.MeshConnectionDiagnostics.*;

/** The certificate pin is the same SHA-256 of DER used in the invitation.
 * Observe the trust callback separately from successful TLS negotiation. */
final class MeshTlsPin implements X509TrustManager {
    private final DesktopTlsPin delegate;
    private final String expected;
    private volatile Check observation=check("TLS identity",SKIP,
        "The certificate verifier was not reached.","Inspect the listening peer's TLS handshake self-check.");
    MeshTlsPin(String pin){delegate=new DesktopTlsPin(pin);expected=pin.substring(0,12);}
    Check observation(){return observation;}
    boolean rejected(){return FAIL.equals(observation.state());}
    @Override public void checkServerTrusted(X509Certificate[] chain,String authType)throws CertificateException{
        String presented="none";
        try{
            if(chain!=null&&chain.length>0)
                presented=MeshItem.hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())).substring(0,12);
            delegate.checkServerTrusted(chain,authType);
            observation=check("TLS identity",PASS,"Presented certificate matches bound identity "+expected+"…; dates verified.","");
        }catch(CertificateException error){
            String reason=error instanceof CertificateExpiredException?"The certificate has expired. ":
                error instanceof CertificateNotYetValidException?"The certificate is not yet valid. ":
                "The presented certificate did not pass pin verification. ";
            observation=check("TLS identity",FAIL,reason+"Expected "+expected+"…; presented "+presented+(presented.equals("none")?".":"…."),
                "Verify the listening peer and its current invitation. Only approve a changed certificate identity deliberately.");
            throw error;
        }catch(java.security.GeneralSecurityException error){
            observation=check("TLS identity",FAIL,"Could not compute the presented certificate fingerprint.","Check TLS support on this device.");
            throw new CertificateException("Mesh certificate fingerprint unavailable",error);
        }
    }
    @Override public void checkClientTrusted(X509Certificate[] chain,String authType)throws CertificateException{
        delegate.checkClientTrusted(chain,authType);
    }
    @Override public X509Certificate[] getAcceptedIssuers(){return delegate.getAcceptedIssuers();}
    void verifySession(SSLSession session)throws CertificateException{
        java.security.cert.Certificate[] chain;
        try{chain=session.getPeerCertificates();}
        catch(javax.net.ssl.SSLPeerUnverifiedException error){throw new CertificateException("Missing peer certificate",error);}
        if(chain.length==0||!(chain[0] instanceof X509Certificate))throw new CertificateException("Missing peer certificate");
        checkServerTrusted(new X509Certificate[]{(X509Certificate)chain[0]},"EC");
    }
}
