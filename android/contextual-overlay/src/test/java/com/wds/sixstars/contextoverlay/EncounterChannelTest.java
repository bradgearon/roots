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
    @Test public void existingBleConnectionAcceptsLargeArchiveWithoutAnExtraCipher()throws Exception{
        KeyPair client=EncounterChannel.newEcKey(),server=EncounterChannel.newEcKey();
        byte[] archive=new byte[2*1024*1024];new java.util.Random(42).nextBytes(archive);
        ExecutorService workers=Executors.newSingleThreadExecutor();
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            Future<Boolean> answer=workers.submit(()->{
                try(Socket socket=listener.accept()){
                    socket.setSoTimeout(10000);
                    EncounterChannel channel=EncounterChannel.open(socket.getInputStream(),socket.getOutputStream(),server,false);
                    byte[] actual=channel.receivePublicBytes(archive.length,8*1024*1024);
                    channel.send(new byte[]{42});return java.util.Arrays.equals(archive,actual);
                }
            });
            try(Socket socket=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort())){
                socket.setSoTimeout(10000);
                EncounterChannel channel=EncounterChannel.open(socket.getInputStream(),socket.getOutputStream(),client,true);
                channel.sendPublicBytes(archive,8*1024*1024);
                assertArrayEquals(new byte[]{42},channel.receive());
                assertTrue(answer.get(10,TimeUnit.SECONDS));
            }
        }finally{workers.shutdownNow();}
    }
    @Test public void rejectsSelfBinding()throws Exception{
        KeyPair key=EncounterChannel.newEcKey();
        ExecutorService workers=Executors.newSingleThreadExecutor();
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            Future<Boolean> answer=workers.submit(()->{
                try(Socket socket=listener.accept()){
                    socket.setSoTimeout(5000);
                    try{EncounterChannel.open(socket.getInputStream(),socket.getOutputStream(),key,false);return false;}
                    catch(java.security.GeneralSecurityException expected){return true;}
                }
            });
            try(Socket socket=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort())){
                socket.setSoTimeout(5000);
                try{EncounterChannel.open(socket.getInputStream(),socket.getOutputStream(),key,true);fail("Self binding accepted");}
                catch(java.security.GeneralSecurityException expected){}
                assertTrue(answer.get(5,TimeUnit.SECONDS));
            }
        }finally{workers.shutdownNow();}
    }
    @Test public void rejectsTamperedEncryptedRecord()throws Exception{
        KeyPair client=EncounterChannel.newEcKey(),server=EncounterChannel.newEcKey();
        ExecutorService workers=Executors.newSingleThreadExecutor();
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            Future<Boolean> answer=workers.submit(()->{
                try(Socket socket=listener.accept()){
                    socket.setSoTimeout(5000);
                    EncounterChannel channel=EncounterChannel.open(socket.getInputStream(),socket.getOutputStream(),server,false);
                    try{channel.receive();return false;}
                    catch(javax.crypto.AEADBadTagException expected){return true;}
                }
            });
            try(Socket socket=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort())){
                socket.setSoTimeout(5000);
                class Tamper extends FilterOutputStream {
                    boolean active;
                    Tamper(OutputStream output){super(output);}
                    @Override public void write(byte[] data,int offset,int length)throws IOException{
                        byte[] copy=java.util.Arrays.copyOfRange(data,offset,offset+length);
                        if(active&&length>4)copy[length-1]^=1;
                        out.write(copy);
                    }
                    @Override public void write(int value)throws IOException{out.write(value);}
                }
                Tamper output=new Tamper(socket.getOutputStream());
                EncounterChannel channel=EncounterChannel.open(socket.getInputStream(),output,client,true);
                output.active=true;channel.send(new byte[]{1,2,3});
                assertTrue(answer.get(5,TimeUnit.SECONDS));
            }
        }finally{workers.shutdownNow();}
    }
    @Test public void rejectsOversizedHello()throws Exception{
        byte[] invalid={0,0,8,0};
        try{EncounterChannel.open(new ByteArrayInputStream(invalid),new ByteArrayOutputStream(),EncounterChannel.newEcKey(),false);fail();}
        catch(IOException expected){}
    }
}
