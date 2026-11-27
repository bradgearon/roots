package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.security.KeyPair;
import java.util.concurrent.*;

public class EncounterChannelTest {
    @Test public void mutualProofAndBidirectionalEncryptedRecords()throws Exception{
        KeyPair client=EncounterChannel.newEcKey(),server=EncounterChannel.newEcKey();
        ExecutorService workers=Executors.newSingleThreadExecutor();
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            Future<String> answer=workers.submit(()->{
                try(Socket socket=listener.accept()){
                    socket.setSoTimeout(5000);
                    EncounterChannel channel=EncounterChannel.open(socket.getInputStream(),socket.getOutputStream(),server,false);
                    assertArrayEquals(new byte[]{1,2,3},channel.receive());
                    channel.send(new byte[]{4,5,6});return channel.peerId;
                }
            });
            try(Socket socket=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort())){
                socket.setSoTimeout(5000);
                EncounterChannel channel=EncounterChannel.open(socket.getInputStream(),socket.getOutputStream(),client,true);
                assertEquals(EncounterChannel.identityId(server.getPublic()),channel.peerId);
                channel.send(new byte[]{1,2,3});assertArrayEquals(new byte[]{4,5,6},channel.receive());
                assertEquals(EncounterChannel.identityId(client.getPublic()),answer.get(5,TimeUnit.SECONDS));
            }
        }finally{workers.shutdownNow();}
    }
    @Test public void rejectsOversizedHello()throws Exception{
        byte[] invalid={0,0,8,0};
        try{EncounterChannel.open(new ByteArrayInputStream(invalid),new ByteArrayOutputStream(),EncounterChannel.newEcKey(),false);fail();}
        catch(IOException expected){}
    }
}
