package com.wds.sixstars.contextoverlay;

import com.wds.sixstars.contextoverlay.tree.NativeTree;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;

/** Full Root data uses existing Mesh graph envelopes. Public receipt material
 * is stored under a separate local namespace, never under a peer-chosen native
 * key or as a live branch/agent command. */
final class RootGraphExchange {
    private RootGraphExchange(){}
    static boolean localOffer(MeshItem item){
        MeshItem.Graph g=item.graph();if(g==null)return false;
        if("branch".equals(g.kind()))return true;
        // Re-export received envelopes from the receipt store, not the
        // local imported projection, private transport secrets or metadata.
        if(g.key().startsWith("local:mesh:graph:"))return false;
        if(g.source().matches("(?i).*(credential|password|private-key|access-token|api-key).*"))return false;
        if(g.source().startsWith("record.mesh.")){
            // Original published body/topic/link is already in its explicit
            // envelope; do not turn receipt metadata into new graph items.
            return g.source().matches("record\\.mesh\\.[a-z]{2,3}\\.(translation|localized|understanding)");
        }
        return true;
    }
    static boolean fitsFrame(MeshItem item)throws Exception{
        return new JSONObject().put("op","data").put("item",item.json()).toString().getBytes(StandardCharsets.UTF_8).length<=EncounterChannel.MAX_FRAME;
    }
    static String externalKey(MeshItem item)throws Exception{
        MeshItem.Graph g=item.graph();
        // For grouped records, use the original group identifier; for raw
        // observations and branch definitions use the content ID. No local
        // native identity can be overwritten by imported content.
        String identity=g.kind().equals("fragment")&&!g.key().isEmpty()?"group:"+g.key():"item:"+item.id();
        return "local:mesh:graph:"+MeshItem.hex(MessageDigest.getInstance("SHA-256").digest(
            ("roots.external.group.v1\n"+identity).getBytes(StandardCharsets.UTF_8)));
    }
    static List<NativeTree.Observation> rows(MeshItem item,String peer,long receivedAt)throws Exception{
        MeshItem.Graph g=item.graph();if(g==null||!EncounterBindings.digest(peer)||receivedAt<0)throw new IllegalArgumentException("Invalid graph receipt");
        String key=externalKey(item);
        String source=g.source(),role=source.substring(source.lastIndexOf('.')+1);
        // Only map known presentation roles. Imported peer-supplied role
        // strings cannot become trust, permission, offer, or executable fields.
        String mapped;
        if(g.kind().equals("branch")||g.key().isEmpty())mapped="record.mesh.body";
        else if(source.matches("record\\.(x|mesh)\\.[a-z]{2,3}\\.(translation|localized)")&&g.language().matches("[a-z]{2,3}"))mapped="record.mesh."+g.language()+"."+role;
        else if(source.startsWith("record.")&&role.equals("body"))mapped="record.mesh.body";
        else if((role.equals("link")||role.equals("link-confirmed"))&&!XPostLink.canonical(item.text()).isEmpty())mapped="record.mesh.link";
        else mapped="record.mesh.graph-reference";
        // Main Tree receives the original text and its original graph envelope
        // identity; the full exact wire envelope stays in external receipt DB.
        List<NativeTree.Observation> out=new ArrayList<>();
        out.add(new NativeTree.Observation(mapped,key,item.text(),g.language(),g.at()));
        if(g.kind().equals("branch"))out.add(new NativeTree.Observation("record.mesh.topic",key,"External branch · "+g.key(),"",receivedAt));
        out.add(new NativeTree.Observation("record.mesh.external-peer",key,peer,"",receivedAt));
        out.add(new NativeTree.Observation("record.mesh.trust",key,"External reference · unverified claims","",receivedAt));
        out.add(new NativeTree.Observation("record.mesh.graph-id",key,item.id(),"",receivedAt));
        out.add(new NativeTree.Observation("record.mesh.graph-source",key,g.source(),"",receivedAt));
        return out;
    }
}
