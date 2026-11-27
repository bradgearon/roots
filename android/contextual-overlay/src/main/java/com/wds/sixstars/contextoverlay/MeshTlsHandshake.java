package com.wds.sixstars.contextoverlay;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/** Exercise the actual JSSE provider/key manager over a private loopback socket
 * before advertising an identity. No Mesh invitation or content is sent. */
final class MeshTlsHandshake {
    private static final int TIMEOUT_MS=2500;
    private MeshTlsHandshake(){}
    static String[] workingProtocols(SSLContext server,SSLContext client)throws IOException{
        List<String> supported=Arrays.asList(server.getSupportedSSLParameters().getProtocols());
        List<String> working=new ArrayList<>();
        Exception last=null;
        for(String protocol:new String[]{"TLSv1.3","TLSv1.2"}){
            if(!supported.contains(protocol))continue;
            try{probe(server,client,protocol);working.add(protocol);}
            catch(Exception failure){last=failure;}
        }
        if(working.isEmpty())throw new IOException("No pinned TLS 1.2 or 1.3 loopback handshake succeeded.",last);
        return working.toArray(new String[0]);
    }
    private static void probe(SSLContext server,SSLContext client,String protocol)throws Exception{
        InetAddress loop=InetAddress.getByName("127.0.0.1");
        try(SSLServerSocket listener=(SSLServerSocket)server.getServerSocketFactory().createServerSocket()){
            listener.setUseClientMode(false);
            listener.setEnabledProtocols(new String[]{protocol});
            listener.setSoTimeout(TIMEOUT_MS);
            listener.bind(new InetSocketAddress(loop,0),1);
            CompletableFuture<Void> accepted=new CompletableFuture<>();
            Thread worker=new Thread(()->{
                try(SSLSocket socket=(SSLSocket)listener.accept()){
                    socket.setSoTimeout(TIMEOUT_MS);
                    socket.startHandshake();
                    // Complete a tiny private round trip, not just the handshake.
                    socket.getOutputStream().write(0x52);socket.getOutputStream().flush();
                    if(socket.getInputStream().read()!=0x41)throw new IOException("TLS probe acknowledgment missing");
                    // Signal before close_notify: some TLS 1.3 providers wait
                    // for the other endpoint to close its socket too.
                    accepted.complete(null);
                }catch(Exception error){accepted.completeExceptionally(error);}
            },"RootsMeshTlsSelfCheck");worker.setDaemon(true);worker.start();
            try(SSLSocket socket=(SSLSocket)client.getSocketFactory().createSocket()){
                socket.setUseClientMode(true);
                socket.setEnabledProtocols(new String[]{protocol});
                socket.setSoTimeout(TIMEOUT_MS);
                socket.connect(new InetSocketAddress(loop,listener.getLocalPort()),TIMEOUT_MS);
                socket.startHandshake();
                if(!protocol.equals(socket.getSession().getProtocol()))throw new IOException("Unexpected TLS protocol");
                if(socket.getInputStream().read()!=0x52)throw new IOException("TLS probe response missing");
                socket.getOutputStream().write(0x41);socket.getOutputStream().flush();
                accepted.get(TIMEOUT_MS,TimeUnit.MILLISECONDS);
            }finally{accepted.cancel(true);worker.interrupt();}
        }
    }
}
