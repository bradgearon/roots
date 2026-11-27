package com.wds.sixstars.contextoverlay;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.wds.sixstars.contextoverlay.MeshConnectionDiagnostics.*;

public class MeshTlsHandshakeTest {
    // Disposable, deliberately public EC test key/certificate; never used by Roots.
    // Certificate validity 2020–2039. No Android Keystore claims from this JVM test.
    private static final String FIXTURE="MIIEGAIBAzCCA8IGCSqGSIb3DQEHAaCCA7MEggOvMIIDqzCCATIGCSqGSIb3DQEHAaCCASMEggEfMIIBGzCCARcGCyqGSIb3DQEMCgECoIG9MIG6MGYGCSqGSIb3DQEFDTBZMDgGCSqGSIb3DQEFDDArBBQEn1c3MwSTRnk0ghAPzs3F2OP00gICJxACASAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoEEPug+m/Ww5L+7JthBOm5iTAEUEMdIflOi5hbr2+9DzHUJ7yCSLH4loww2WpQEvHB8W/Jdtv8LQFgDiOlhjG6mSr/hyiPFeremzYq84/AZCyI7FNT4RtE8KKNg5+IuomjCPfRMUgwIwYJKoZIhvcNAQkUMRYeFAByAG8AbwB0AHMALQB0AGUAcwB0MCEGCSqGSIb3DQEJFTEUBBJUaW1lIDE3OTU4MDg3NDQ4MTAwggJxBgkqhkiG9w0BBwagggJiMIICXgIBADCCAlcGCSqGSIb3DQEHATBmBgkqhkiG9w0BBQ0wWTA4BgkqhkiG9w0BBQwwKwQURYywDOJNYphD8OAhIo0OZJ9PcIQCAicQAgEgMAwGCCqGSIb3DQIJBQAwHQYJYIZIAWUDBAEqBBD/omFrELtu7nLYZRqqBzBpgIIB4FnomkILhL1j4VkD/CU4VoRsuvymI1J3SoDJ4wAc2fbXkLZjs2hXVZQKmgD/4PulLccATwWLSGybG3G/VyaHT9A5wrQ8IpKNB5YeNccws4yQ+gGB/n0lcktyRfbIz3j7wou/LYfywhjF+W8yU0g5bCKPPhW5bRiaEfFaBzd+qkq7FTjKui96SjHFY/+AZYdgYC+Qcs767dx8VLQZ0r10btQulwk04Oeq9m/PoPkfhs5nJNWrP7HaRyfYYCSesZekMRWv2l7rWUho6gZrmrxxlyWU0gpGfRTlIhLAXAb7KzsTh3yxBqo9y5Bjxj45EJYUHaXmZjweooZ+HdoSCq2+h31ayriehLKGQh8fhR2jNgQcJt8uDw2wq7n7YyP2CzVpRyPMpngPp6lPFXboZ1ZeZvOeotzojCw8vBRxzxeH7hpIcLNbdcbAroSGgD5wNsyxU0mh0yz0oSzmTCVaX2Kb+WwrStwKSMJYlAASNL6tDCaIbGncJJ4PYBzWQ44gdlCroidJO2LElFektmS2oAqVnoIPREpb1nJxD2310IVGVFjJMvMYZ4PCWY8OjauzKltj5VckzHlOhEsxQj+OPQKCxA4g9JD607sQNXXQa8O9PtVmppADL5NcRgbCHyf8IO2/djBNMDEwDQYJYIZIAWUDBAIBBQAEIGMP8U9ofhj8b9obwWQF4Fc5TDcXSvBManSWz6njDZkbBBQXzh7rVpjerF5Pa6Cnpsf2zWs4xAICJxA=";
    private record Server(SSLContext context,String pin){}
    private static Server server()throws Exception{
        KeyStore store=KeyStore.getInstance("PKCS12");
        char[] password="roots-test-only".toCharArray();
        store.load(new ByteArrayInputStream(Base64.getDecoder().decode(FIXTURE)),password);
        X509Certificate cert=(X509Certificate)store.getCertificate("roots-test");
        String pin=MeshItem.hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(store,password);
        SSLContext context=SSLContext.getInstance("TLS");context.init(km.getKeyManagers(),null,null);
        return new Server(context,pin);
    }
    private static SSLContext client(MeshTlsPin pin)throws Exception{
        SSLContext context=SSLContext.getInstance("TLS");context.init(null,new TrustManager[]{pin},null);return context;
    }
    @Test public void actualPinnedLoopbackHandshakeSucceeds()throws Exception{
        Server server=server();MeshTlsPin pin=new MeshTlsPin(server.pin());
        java.util.List<String> working=Arrays.asList(MeshTlsHandshake.workingProtocols(server.context(),client(pin)));
        assertTrue(working.contains("TLSv1.2"));
        if(Arrays.asList(server.context().getSupportedSSLParameters().getProtocols()).contains("TLSv1.3"))
            assertTrue(working.contains("TLSv1.3"));
        assertEquals(PASS,pin.observation().state());
    }
    @Test public void actualLoopbackHandshakeRejectsChangedIdentity()throws Exception{
        Server server=server();MeshTlsPin pin=new MeshTlsPin("0".repeat(64));
        assertThrows(IOException.class,()->MeshTlsHandshake.workingProtocols(server.context(),client(pin)));
        assertEquals(FAIL,pin.observation().state());
        assertTrue(pin.observation().detail().contains(server.pin().substring(0,12)));
    }
}
