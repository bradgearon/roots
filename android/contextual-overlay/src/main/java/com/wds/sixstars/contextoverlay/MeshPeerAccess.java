package com.wds.sixstars.contextoverlay;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static com.wds.sixstars.contextoverlay.MeshConnectionDiagnostics.*;

/** Resolves explicit invitations or saved, bound peer access before any network
 * operation. Explicit input is authoritative: invalid input never falls back to
 * a previously saved grant. Only selected, bound invitations are persisted. */
final class MeshPeerAccess {
    private MeshPeerAccess(){}

    record Resolution(AxonSubscription policy,List<MeshPeer.Invite> peers,List<Check> checks,boolean fromSaved,boolean ready){
        Resolution {
            peers=Collections.unmodifiableList(new ArrayList<>(peers));
            checks=Collections.unmodifiableList(new ArrayList<>(checks));
        }
        Resolution requireReady(){
            if(!ready){
                for(Check check:checks)if(FAIL.equals(check.state()))
                    throw new IllegalArgumentException(check.detail()+(check.nextStep().isEmpty()?"":" "+check.nextStep()));
                throw new IllegalArgumentException("No peer connection is ready. Check the saved Axon and peer invitations.");
            }
            return this;
        }
    }

    /** Injectable boundary keeps resolution decisions testable without an
     * Android device, while production uses the platform-backed secret store. */
    interface Source {
        AxonSubscription loadPolicy()throws Exception;
        List<MeshPeer.Invite> loadPeers(AxonSubscription policy)throws Exception;
        void savePeers(AxonSubscription policy,List<MeshPeer.Invite> peers)throws Exception;
        MeshPeer.Invite parse(String value)throws Exception;
    }

    static Resolution resolve(Context context,String raw,boolean useSavedAxon){
        return resolve(raw,useSavedAxon,new Source(){
            @Override public AxonSubscription loadPolicy()throws Exception{return AxonSubscription.load(context);}
            @Override public List<MeshPeer.Invite> loadPeers(AxonSubscription policy)throws Exception{return AxonPeerStore.load(context,policy);}
            @Override public void savePeers(AxonSubscription policy,List<MeshPeer.Invite> peers)throws Exception{AxonPeerStore.save(context,policy,peers);}
            @Override public MeshPeer.Invite parse(String value)throws Exception{return MeshPeer.Invite.parse(value);}
        });
    }

    static Resolution resolve(String raw,boolean useSavedAxon,Source source){
        List<Check> checks=new ArrayList<>();
        AxonSubscription policy=null;
        if(useSavedAxon){
            try{policy=source.loadPolicy();}
            catch(Exception|LinkageError error){
                return fail(null,checks,"Saved Axon","The saved .axon subscription could not be loaded.",
                    "Open Bind / edit .axon to repair the saved subscription. No peer connection was attempted.",false);
            }
            if(policy==null)return fail(null,checks,"Saved Axon","No saved .axon subscription was found.",
                "Bind the intended peers, or turn off Use saved .axon subscription to use entered invitations directly.",false);
            checks.add(check("Saved Axon",PASS,"Saved .axon loaded: "+policy.pins.size()+" bound peer identity/identities.",""));
        }else checks.add(check("Saved Axon",SKIP,"Saved .axon is not selected; only entered invitations will be used.",""));

        String input=raw==null?"":raw;
        if(input.length()>8192)return fail(policy,checks,"Invitations","Invitation input is too large.",
            "Enter at most eight complete peer invitations, one per line.",false);
        boolean fromSaved=input.trim().isEmpty()&&useSavedAxon;
        List<MeshPeer.Invite> peers=new ArrayList<>();
        if(fromSaved){
            try{
                List<MeshPeer.Invite> stored=source.loadPeers(policy);
                if(stored==null||stored.size()>8)throw new IllegalArgumentException();
                for(MeshPeer.Invite peer:stored){
                    if(peer==null)throw new IllegalArgumentException();
                    // Defense in depth: grants cannot expand the selected policy.
                    if(policy.pins.contains(peer.pin()))peers.add(peer);
                }
            }catch(Exception|LinkageError error){
                return fail(policy,checks,"Peer access","Saved peer connection details could not be opened.",
                    "Paste the bound peer's current invitation and test again to replace the saved access. Keep the existing identity binding.",true);
            }
            if(peers.isEmpty())return fail(policy,checks,"Peer access","The saved Axon is bound, but no connection details are available for its peers.",
                "Paste a current invitation once, then Test connection to save it. The existing identity binding can be kept.",true);
        }else{
            List<String> lines=new ArrayList<>();
            for(String line:input.split("\\r?\\n"))if(!line.trim().isEmpty())lines.add(line.trim());
            if(lines.isEmpty()||lines.size()>8)return fail(policy,checks,"Invitations","Enter one to eight peer invitations.",
                "Paste one complete invitation from each intended peer, one per line.",false);
            for(int i=0;i<lines.size();i++){
                try{
                    MeshPeer.Invite peer=source.parse(lines.get(i));
                    if(peer==null)throw new IllegalArgumentException();
                    peers.add(peer);
                }catch(Exception|LinkageError error){
                    return fail(policy,checks,"Invitations","Invitation "+(i+1)+" is not a valid Mesh invitation.",
                        "Copy the complete current invitation from the intended peer. Entered input does not fall back to saved access.",false);
                }
            }
        }

        Set<String> hosts=new HashSet<>(),pins=new HashSet<>();
        for(MeshPeer.Invite peer:peers){
            if(!hosts.add(peer.host()))return fail(policy,checks,"Peer access","More than one invitation targets the same endpoint.",
                "Keep one current invitation for each peer; do not mix old and current listener sessions.",fromSaved);
            if(!pins.add(peer.pin()))return fail(policy,checks,"Peer access","More than one endpoint uses the same peer identity.",
                "Keep the intended peer's current invitation once.",fromSaved);
        }
        if(policy!=null&&!policy.accepts(peers))return fail(policy,checks,"Axon binding","An entered invitation does not match a peer identity in the saved Axon.",
            "Verify the intended peer, then use Bind / edit .axon to approve a changed identity. The saved binding was not changed.",fromSaved);

        if(!fromSaved&&policy!=null){
            try{source.savePeers(policy,Collections.unmodifiableList(new ArrayList<>(peers)));}
            catch(Exception|LinkageError error){
                return fail(policy,checks,"Saved peer access","The entered connection details could not be saved securely.",
                    "Check device storage and retry. No network request was sent, and the existing identity binding was kept.",false);
            }
        }
        checks.add(check("Peer access",PASS,fromSaved?
            "Using saved connection details for "+peers.size()+" bound peer(s).":
            policy!=null?"Using entered invitations for "+peers.size()+" bound peer(s); connection details saved encrypted on this device.":
                "Using "+peers.size()+" entered peer invitation(s) for this operation.",""));
        if(policy!=null&&pins.size()<policy.pins.size())checks.add(check("Peer coverage",WARN,
            peers.size()+" of "+policy.pins.size()+" bound peer(s) have connection details selected; other bound peers will not be checked.",
            "Paste current invitations for all desired bound peers to update the saved connection set."));
        return new Resolution(policy,peers,checks,fromSaved,true);
    }

    private static Resolution fail(AxonSubscription policy,List<Check> checks,String stage,String detail,String next,boolean fromSaved){
        checks.add(check(stage,FAIL,detail,next));
        // An unusable resolution never supplies grants that a caller could
        // accidentally send after ignoring the diagnostic status.
        return new Resolution(policy,Collections.emptyList(),checks,fromSaved,false);
    }
}
