package com.wds.sixstars.contextoverlay;

import android.content.Context;
import java.io.IOException;
import java.net.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import javax.net.ssl.*;

/** Standard TLS for the public IP route, using the existing Android TLS key
 * manager. The persistent Axon key separately authenticates the TLS endpoint
 * before the application bind. No invitation or private grant is conveyed. */
final class EncounterTls {
    private EncounterTls(){}
    private static String[] protocols(String[] supported){
        return Arrays.stream(supported).filter(p->p.equals("TLSv1.2")||p.equals("TLSv1.3")).toArray(String[]::new);
    }
    static SSLServerSocket listener(Context context)throws Exception{
        MeshTlsIdentity.Identity identity=MeshTlsIdentity.loadOrCreate(context);
        SSLContext tls=SSLContext.getInstance("TLS");
        tls.init(new KeyManager[]{new MeshPeer.PeerKeyManager(identity)},null,null);
        SSLServerSocket socket=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket();
        socket.setUseClientMode(false);socket.setNeedClientAuth(false);
        socket.setEnabledProtocols(protocols(socket.getSupportedProtocols()));
        return socket;
    }
    // Discovery knows the Axon public-key fingerprint, not a DNS name or the
    // separate TLS certificate. This trust callback admits a provisional TLS
    // connection only. openOverTls must prove the expected Axon key against
    // the exact TLS server certificate before the caller receives a channel.
    static final class ProvisionalTrust implements X509TrustManager {
        @Override public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
        @Override public void checkClientTrusted(X509Certificate[] chain,String type)throws CertificateException{
            throw new CertificateException("Client certificates not used");
        }
        @Override public void checkServerTrusted(X509Certificate[] chain,String type)throws CertificateException{
            if(chain==null||chain.length!=1||!"EC".equals(chain[0].getPublicKey().getAlgorithm()))
                throw new CertificateException("Invalid Roots TLS certificate");
            chain[0].checkValidity();
        }
    }
    static SSLSocket client(Socket connected)throws Exception{
        SSLContext tls=SSLContext.getInstance("TLS");
        tls.init(null,new TrustManager[]{new ProvisionalTrust()},null);
        SSLSocket socket=(SSLSocket)tls.getSocketFactory().createSocket(connected,
            connected.getInetAddress().getHostAddress(),connected.getPort(),true);
        socket.setUseClientMode(true);socket.setEnabledProtocols(protocols(socket.getSupportedProtocols()));
        return socket;
    }
    static EncounterChannel authenticate(SSLSocket socket,KeyPair identity,boolean client,String expectedPeer)throws Exception{
        socket.startHandshake();
        java.security.cert.Certificate[] certs=client?socket.getSession().getPeerCertificates():socket.getSession().getLocalCertificates();
        if(certs==null||certs.length!=1||!(certs[0] instanceof X509Certificate))throw new IOException("Missing Roots TLS certificate");
        if(client&&(expectedPeer==null||!EncounterBindings.digest(expectedPeer)))throw new IOException("Missing expected Axon identity");
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(certs[0].getEncoded());
        return EncounterChannel.openOverTls(socket.getInputStream(),socket.getOutputStream(),identity,client,
            expectedPeer==null?"":expectedPeer,hash);
    }
}
