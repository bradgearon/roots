package com.wds.sixstars.contextoverlay;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.net.ssl.SSLException;

/** Safe, immutable connection evidence. Never put invitation credentials,
 * arbitrary peer text or exception messages into these records. */
final class MeshConnectionDiagnostics {
    static final String PASS="PASS", FAIL="FAIL", WARN="WARN", SKIP="SKIP";
    static final String[] CONNECTION_STAGES={"Subnet","TCP","TLS","Protocol","Authentication","Inventory"};
    record Check(String stage,String state,String detail,String nextStep,long elapsedMs) {
        Check { elapsedMs=Math.max(0,elapsedMs); }
    }
    record PeerReport(String endpoint,String identity,List<Check> checks) {
        PeerReport { checks=immutable(checks); }
    }
    record Report(long checkedAtMillis,List<Check> localChecks,List<PeerReport> peers) {
        Report { localChecks=immutable(localChecks);peers=immutable(peers); }
    }
    private MeshConnectionDiagnostics(){}
    private static <T> List<T> immutable(List<T> items){return Collections.unmodifiableList(new ArrayList<>(items));}
    static Check check(String stage,String state,String detail,String nextStep){return new Check(stage,state,detail,nextStep,0);}

    /** Only fixed local descriptions are used to construct a failure. Its
     * original cause is deliberately not retained in a user-facing report. */
    static final class Failure extends IOException {
        final Check check;
        Failure(String stage,String detail,String nextStep){
            super(detail);check=new Check(stage,FAIL,detail,nextStep,0);
        }
    }
    static Failure protocolFailure(String code){
        switch(code){
            case "unauthorized": return new Failure("Authentication","The peer rejected the invitation access token.",
                "Start or keep the other device listening and copy its current invitation. Stopping or restarting its listener invalidates previous tokens.");
            case "unsupported_version": return new Failure("Protocol","The peer does not support this Mesh protocol version.","Update both Roots installations to compatible versions.");
            case "unsupported_operation": return new Failure("Protocol","The peer does not support this request.","Update both Roots installations to compatible versions.");
            case "invalid_request": return new Failure("Protocol","The peer rejected the request format.","Update both Roots installations and test again.");
            case "unavailable": return new Failure("Receive","The requested published item is no longer available.","Refresh the peer inventory and receive again.");
            default: return new Failure("Protocol","The peer returned an unrecognized error response.","Update both Roots installations and test again.");
        }
    }
    static Check failure(String stage,Throwable error,long elapsedMs){
        if(error instanceof Failure){Check c=((Failure)error).check;return new Check(c.stage(),FAIL,c.detail(),c.nextStep(),elapsedMs);}
        String detail,next;
        if(has(error,CertificateExpiredException.class)){
            detail="The peer certificate has expired according to this device's clock.";
            next="Check the date and time on both devices; renew the peer certificate if it has expired, then copy a new invitation.";
        }else if(has(error,CertificateNotYetValidException.class)){
            detail="The peer certificate is not yet valid according to this device's clock.";
            next="Correct the date and time on both devices and test again.";
        }else if(has(error,CertificateException.class)){
            detail="The peer certificate could not be verified against the invitation identity.";
            next="Copy a fresh invitation directly from the intended peer. Keep certificate verification enabled.";
        }else if(has(error,SocketTimeoutException.class)){
            detail="The connection timed out during "+stage+".";
            next="Keep the other Roots app listening on the same Wi-Fi. Check guest-network isolation and retry.";
        }else if(has(error,NoRouteToHostException.class)){
            detail="No network route to the invited peer is available.";
            next="Connect both devices to the same Wi-Fi subnet and check client isolation or firewall rules.";
        }else if(has(error,ConnectException.class)){
            detail="The TCP connection was rejected or could not be opened.";
            next="Start listening on the other device and copy its current invitation. Check access to TCP port 8233.";
        }else if(has(error,SecurityException.class)){
            detail="Android denied this network operation.";
            next="Check the app's network permissions and device network restrictions, then retry.";
        }else if(has(error,EOFException.class)&&stage.equals("Authentication")){
            detail="Access unconfirmed: the peer closed after TLS without a protocol response.";
            next="An old peer may silently reject an expired invitation; a busy or stopped peer can also close. Copy a fresh invitation and update both devices.";
        }else if(has(error,SSLException.class)){
            detail=stage.equals("TLS")?"The TLS handshake did not complete; invitation-token authentication has not started. "+tlsAlert(error):"The TLS connection ended before the exchange completed.";
            next="Update Roots on the listening peer and check its Local TLS identity result. Restart listening, copy its current invitation, and rebind only if the peer identity changed.";
        }else if(stage.equals("Receive")){
            detail="Published content could not be validated or stored locally.";
            next="Refresh the peer inventory and retry. If this persists, check local storage space and Native Tree availability.";
        }else{
            detail="The connection failed during "+stage+".";
            next=stage.equals("Inventory")||stage.equals("Protocol")?"The peer response was incomplete or incompatible. Update both devices and test again.":
                "Keep the intended peer listening on the same Wi-Fi and test a freshly copied invitation.";
        }
        return new Check(stage,FAIL,detail,next,elapsedMs);
    }
    // Classify known provider alerts without exposing arbitrary exception text.
    private static String tlsAlert(Throwable error){
        for(int depth=0;error!=null&&depth<12;depth++,error=error.getCause()){
            String value=error.getMessage();if(value==null)continue;value=value.toLowerCase(java.util.Locale.ROOT);
            if(value.contains("no suitable certificate")||value.contains("no available authentication scheme")||value.contains("no certificate assigned"))return "TLS provider could not select a server certificate.";
            if(value.contains("signature")||value.contains("private key")||value.contains("keystore"))return "TLS provider reported a certificate-signing or key error.";
            if(value.contains("unknown ca")||value.contains("unknown_ca")||value.contains("bad certificate")||value.contains("bad_certificate")||value.contains("certificate unknown"))return "TLS peer reported a certificate alert.";
            if(value.contains("protocol_version")||value.contains("protocol version")||value.contains("no shared cipher")||value.contains("no cipher suites"))return "TLS provider reported a protocol or cipher negotiation error.";
            if(value.contains("handshake_failure")||value.contains("handshake failure"))return "TLS peer reported a handshake-failure alert.";
            if(value.contains("closed")||value.contains("eof"))return "The peer closed during TLS negotiation.";
        }
        return "No specific provider alert was classified.";
    }
    /** Only retry negotiation failures before token transmission. Never retry a rejected certificate. */
    static boolean canRetryTls12(Throwable error){
        return has(error,SSLException.class)&&!has(error,CertificateException.class)&&!has(error,SocketTimeoutException.class);
    }
    private static boolean has(Throwable error,Class<? extends Throwable> type){
        for(int depth=0;error!=null&&depth<12;depth++,error=error.getCause())if(type.isInstance(error))return true;
        return false;
    }

    /** Mutable only inside one request. Snapshots contain no network objects or
     * credentials; passed elapsed values use a monotonic clock. */
    static final class Trace {
        private final String endpoint,identity;
        private final Map<String,Check> checks=new LinkedHashMap<>();
        private final Consumer<String> progress;
        private String current="Invitation";
        private long started;
        Trace(String endpoint,String identity,Consumer<String> progress){this.endpoint=endpoint;this.identity=identity;this.progress=progress;}
        void add(Check check){checks.put(check.stage(),check);}
        void prepare(){for(String stage:CONNECTION_STAGES)checks.putIfAbsent(stage,check(stage,SKIP,"Not attempted.",""));}
        void begin(String stage,long now){current=stage;started=now;if(progress!=null)progress.accept(endpoint+" · "+stage+"…");}
        void pass(String stage,String detail,long now){
            Check prior=checks.get(stage);long elapsed=(stage.equals(current)?Math.max(0,now-started):0)+(prior==null?0:prior.elapsedMs());
            if(prior!=null&&prior.state().equals(FAIL)){
                add(new Check(stage,WARN,"A later attempt succeeded after: "+prior.detail(),"An earlier failure was observed during this receive. Test again if problems continue.",elapsed));return;
            }
            if(prior!=null&&prior.state().equals(WARN)){add(new Check(stage,WARN,prior.detail(),prior.nextStep(),elapsed));return;}
            add(new Check(stage,PASS,detail,"",elapsed));
        }
        void fail(Throwable error,long now){
            Check c=failure(current,error,Math.max(0,now-started));add(c);
            for(String stage:CONNECTION_STAGES){Check existing=checks.get(stage);if(existing!=null&&existing.state().equals(SKIP))add(check(stage,SKIP,"Not attempted after an earlier failure.",""));}
        }
        boolean failed(){for(Check check:checks.values())if(check.state().equals(FAIL))return true;return false;}
        String currentStage(){return current;}
        PeerReport snapshot(){return new PeerReport(endpoint,identity,new ArrayList<>(checks.values()));}
    }
    /** A saved binding or an open TCP socket alone is not a live Axon. */
    static int connectedPeers(Report report){
        int count=0;
        for(PeerReport peer:report.peers()){
            boolean inventory=false,failed=false;
            for(Check check:peer.checks()){
                if(FAIL.equals(check.state()))failed=true;
                if("Inventory".equals(check.stage())&&PASS.equals(check.state()))inventory=true;
            }
            if(inventory&&!failed)count++;
        }
        return count;
    }
    static String failureSummary(Report report){
        for(Check c:report.localChecks())if(c.state().equals(FAIL))return c.stage()+": "+c.detail();
        for(PeerReport p:report.peers())for(Check c:p.checks())if(c.state().equals(FAIL))return p.endpoint()+" · "+c.stage()+": "+c.detail();
        return "No peer inventory was reachable. Run Test connections for details.";
    }
}
