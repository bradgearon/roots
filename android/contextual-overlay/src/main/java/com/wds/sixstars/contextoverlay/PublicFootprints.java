package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.*;

/** Bounded public directory cache, independent of Tree, GGUF and authorization. */
final class PublicFootprints {
    static final int MAX_CARDS=4096, MAX_LINKS=8, MAX_PAGE=9, MAX_BYTES=2048;
    private static final String DOMAIN="roots.public.footprint.v1\n";
    record Card(String id,long revision,List<String> links,String wire){}
    private static PublicFootprints instance;
    static synchronized PublicFootprints get(Context context){
        if(instance==null)instance=new PublicFootprints(context.getApplicationContext());
        return instance;
    }
    private final File directory;
    private final EncounterBindings bindings;
    private final NavigableMap<String,Card> cards=new TreeMap<>();
    private boolean loaded;
    private PublicFootprints(Context context){
        directory=new File(context.getNoBackupFilesDir(),"public-footprints-v1");
        bindings=EncounterBindings.get(context);
    }
    private static byte[] body(String id,long revision,List<String> links){
        StringBuilder value=new StringBuilder(DOMAIN).append(id).append('\n').append(revision).append('\n');
        for(String link:links)value.append(link).append('\n');
        return value.toString().getBytes(StandardCharsets.US_ASCII);
    }
    private static byte[] unbase(String value,int limit)throws IOException{
        if(value.length()>((limit+2)/3)*4)throw new IOException("Footprint encoding too large");
        byte[] bytes=Base64.decode(value,Base64.NO_WRAP);
        if(bytes.length==0||bytes.length>limit||!Base64.encodeToString(bytes,Base64.NO_WRAP).equals(value))throw new IOException("Invalid footprint encoding");
        return bytes;
    }
    private static PublicKey publicKey(byte[] encoded)throws Exception{
        PublicKey result=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
        if(!(result instanceof ECPublicKey)||!Arrays.equals(encoded,result.getEncoded()))throw new GeneralSecurityException("Noncanonical footprint key");
        AlgorithmParameters parameters=AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected=parameters.getParameterSpec(ECParameterSpec.class),actual=((ECPublicKey)result).getParams();
        if(!actual.getCurve().equals(expected.getCurve())||!actual.getGenerator().equals(expected.getGenerator())||
            !actual.getOrder().equals(expected.getOrder())||actual.getCofactor()!=expected.getCofactor())
            throw new GeneralSecurityException("Expected P-256 footprint key");
        return result;
    }
    static Card verify(String wire,long now)throws Exception{
        if(wire.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IOException("Footprint too large");
        JSONObject json=new JSONObject(wire);
        if(json.length()!=5)throw new IOException("Invalid footprint fields");
        String id=json.getString("id");
        Object time=json.get("revision");
        if(!(time instanceof Long)&&!(time instanceof Integer))throw new IOException("Invalid footprint revision");
        long revision=json.getLong("revision");
        if(!EncounterBindings.digest(id)||revision<0||revision>now+300000L)throw new IOException("Invalid footprint identity or revision");
        JSONArray array=json.getJSONArray("links");
        if(array.length()>MAX_LINKS)throw new IOException("Too many footprint links");
        List<String> links=new ArrayList<>();String previous="";
        for(int i=0;i<array.length();i++){
            String link=array.getString(i);
            if(!EncounterBindings.digest(link)||link.equals(id)||link.compareTo(previous)<=0)throw new IOException("Invalid footprint links");
            links.add(link);previous=link;
        }
        PublicKey key=publicKey(unbase(json.getString("key"),256));
        if(!id.equals(EncounterChannel.identityId(key)))throw new GeneralSecurityException("Footprint owner mismatch");
        Signature signature=Signature.getInstance("SHA256withECDSA");
        signature.initVerify(key);signature.update(body(id,revision,links));
        if(!signature.verify(unbase(json.getString("sig"),80)))throw new GeneralSecurityException("Invalid footprint signature");
        return new Card(id,revision,Collections.unmodifiableList(links),json.toString());
    }
    static Card sign(KeyPair key,Collection<String> candidates,long now)throws Exception{
        String id=EncounterChannel.identityId(key.getPublic());
        TreeSet<String> chosen=new TreeSet<>();
        for(String link:candidates){
            if(EncounterBindings.digest(link)&&!id.equals(link))chosen.add(link);
            if(chosen.size()==MAX_LINKS)break;
        }
        List<String> links=new ArrayList<>(chosen);
        Signature signature=Signature.getInstance("SHA256withECDSA");
        signature.initSign(key.getPrivate());signature.update(body(id,now,links));
        String wire=new JSONObject().put("id",id).put("revision",now).put("links",new JSONArray(links))
            .put("key",Base64.encodeToString(key.getPublic().getEncoded(),Base64.NO_WRAP))
            .put("sig",Base64.encodeToString(signature.sign(),Base64.NO_WRAP)).toString();
        return verify(wire,now);
    }
    private File path(String id){return new File(directory,id+".json");}
    private void load(){
        if(loaded)return;
        String[] names=directory.list();long now=System.currentTimeMillis();
        if(names!=null){
            Arrays.sort(names);
            for(String name:names){
                if(cards.size()>=MAX_CARDS)break;
                if(!name.matches("[0-9a-f]{64}\\.json(?:\\.bak|\\.new)?"))continue;
                String id=name.substring(0,64);
                if(cards.containsKey(id))continue;
                try(FileInputStream input=new AtomicFile(path(id)).openRead()){
                    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[512];int n;
                    while((n=input.read(buffer))!=-1){if(out.size()+n>MAX_BYTES)throw new IOException("Oversized stored footprint");out.write(buffer,0,n);}
                    Card card=verify(new String(out.toByteArray(),StandardCharsets.UTF_8),now);
                    if(card.id().equals(id))cards.put(id,card);
                }catch(Exception ignored){}
            }
        }
        loaded=true;
    }
    private void retain(Card card)throws Exception{
        Card old=cards.get(card.id());
        if(old!=null&&old.revision()>=card.revision())return;
        if(old==null&&cards.size()>=MAX_CARDS)return; // Cache, not global census or custody.
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create footprint directory");
        AtomicFile file=new AtomicFile(path(card.id()));FileOutputStream out=file.startWrite();
        try{out.write(card.wire().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
        catch(Exception failure){file.failWrite(out);throw failure;}
        cards.put(card.id(),card);
    }
    synchronized JSONArray offer(KeyPair identity,String peer)throws Exception{
        load();
        List<String> links=new ArrayList<>();
        for(var binding:bindings.subscriptions())if(!binding.blocked())links.add(binding.binding().peer());
        Card own=sign(identity,links,System.currentTimeMillis());
        JSONArray result=new JSONArray().put(new JSONObject(own.wire()));
        List<Card> sample=new ArrayList<>(cards.values());
        Collections.shuffle(sample);
        for(Card card:sample){
            if(result.length()>=MAX_PAGE)break;
            if(!card.id().equals(peer)&&!card.id().equals(own.id())&&!bindings.blocked(card.id()))result.put(new JSONObject(card.wire()));
        }
        return result;
    }
    synchronized void receive(JSONArray page,String peer,KeyPair identity)throws Exception{
        if(page==null||page.length()<1||page.length()>MAX_PAGE||bindings.blocked(peer))return;
        long now=System.currentTimeMillis();String own=EncounterChannel.identityId(identity.getPublic());
        Card direct;
        try{direct=verify(page.getJSONObject(0).toString(),now);}catch(Exception invalid){return;}
        if(!direct.id().equals(peer)||direct.id().equals(own))return;
        load();retain(direct);
        for(int i=1;i<page.length();i++){
            Card card;
            try{card=verify(page.getJSONObject(i).toString(),now);}catch(Exception invalid){continue;}
            if(!card.id().equals(own)&&!bindings.blocked(card.id()))retain(card);
        }
    }
    synchronized List<Card> snapshot(){
        load();List<Card> result=new ArrayList<>();
        for(Card card:cards.values())if(!bindings.blocked(card.id()))result.add(card);
        return Collections.unmodifiableList(result);
    }
}
