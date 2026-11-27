package com.wds.sixstars.contextoverlay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MeshInventoryProtocolTest {
    private static final String ID="a".repeat(64);
    private static JSONObject entry(String id)throws Exception{return new JSONObject().put("id",id).put("language","en").put("topic","music");}
    private static JSONObject response(JSONArray items,String next)throws Exception{return new JSONObject().put("version",1).put("items",items).put("next",next);}
    @Test public void emptyInventoryIsAValidConnectedResponse()throws Exception{
        MeshPeer.InventoryPage page=MeshPeer.validateInventory(response(new JSONArray(),""),"");
        assertEquals(0,page.items().length());assertEquals("",page.next());
    }
    @Test public void acceptsBoundedInventoryAndContinuation()throws Exception{
        MeshPeer.InventoryPage page=MeshPeer.validateInventory(response(new JSONArray().put(entry(ID)),ID),"");
        assertEquals(1,page.items().length());assertEquals(ID,page.next());
    }
    @Test public void malformedEntryIsRejectedBeforeSyncCanTreatPeerAsReachable()throws Exception{
        assertThrows(Exception.class,()->MeshPeer.validateInventory(response(new JSONArray().put(entry("not-a-content-id")),""),""));
        JSONObject missingLanguage=new JSONObject().put("id",ID).put("topic","music");
        assertThrows(Exception.class,()->MeshPeer.validateInventory(response(new JSONArray().put(missingLanguage),""),""));
        assertThrows(Exception.class,()->MeshPeer.validateInventory(new JSONObject().put("version",1),""));
    }
    @Test public void rejectsDuplicateContentIds()throws Exception{
        assertThrows(Exception.class,()->MeshPeer.validateInventory(response(new JSONArray().put(entry(ID)).put(entry(ID)),""),""));
    }
    @Test public void rejectsOversizedInventoryBeforeProcessingRows()throws Exception{
        JSONArray rows=new JSONArray();for(int i=0;i<=MeshPeer.MAX_INVENTORY;i++)rows.put(entry(ID));
        assertThrows(Exception.class,()->MeshPeer.validateInventory(response(rows,""),""));
    }
    @Test public void cursorMustAdvanceAndCannotAdvertiseEmptyPage()throws Exception{
        assertThrows(Exception.class,()->MeshPeer.validateInventory(response(new JSONArray().put(entry(ID)),ID),ID));
        assertThrows(Exception.class,()->MeshPeer.validateInventory(response(new JSONArray(),ID),""));
        assertThrows(Exception.class,()->MeshPeer.validateInventory(response(new JSONArray().put(entry(ID)),"peer-secret-untrusted-cursor"),""));
    }
    @Test public void inviteToStringNeverExposesCredentials(){
        MeshPeer.Invite invite=new MeshPeer.Invite("192.168.1.2","b".repeat(64),"c".repeat(64));
        assertFalse(invite.toString().contains(invite.pin()));assertFalse(invite.toString().contains(invite.token()));
        assertTrue(invite.toString().contains("192.168.1.2"));
    }
    @Test public void invalidFrameAndJsonAreProtocolFailuresWithoutPeerText()throws Exception{
        MeshConnectionDiagnostics.Failure length=assertThrows(MeshConnectionDiagnostics.Failure.class,()->MeshPeer.read(frame(MeshPeer.MAX_FRAME+1,new byte[0])));
        assertEquals("Protocol",length.check.stage());
        byte[] malformed="not JSON SECRET_TOKEN_AND_PRIVATE_CONTENT".getBytes(StandardCharsets.UTF_8);
        MeshConnectionDiagnostics.Failure json=assertThrows(MeshConnectionDiagnostics.Failure.class,()->MeshPeer.read(frame(malformed.length,malformed)));
        assertEquals("Protocol",json.check.stage());assertFalse(json.getMessage().contains("SECRET_TOKEN"));
    }
    @Test public void truncatedFramePreservesAmbiguousTransportClosure()throws Exception{
        assertThrows(EOFException.class,()->MeshPeer.read(frame(20,new byte[]{'{','}'})));
    }
    private static Socket frame(int size,byte[] body)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(size);out.write(body);
        return new Socket(){@Override public InputStream getInputStream(){return new ByteArrayInputStream(bytes.toByteArray());}};
    }
}
