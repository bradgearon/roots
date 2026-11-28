import com.wds.sixstars.contextoverlay.tree.NativeTree;
import com.wds.sixstars.contextoverlay.tree.KnowledgeGguf;
import com.wds.sixstars.contextoverlay.tree.KnowledgeWave;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class NativeTreeJniChecks {
    static void need(boolean b,String s){if(!b)throw new AssertionError(s);}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("roots-jni-");
        Path db=dir.resolve("tree.db");
        try{
            need(NativeTree.available(),"JNI unavailable");
            long agent;
            try(NativeTree tree=new NativeTree(db.toString())){
                var rows=List.of(new NativeTree.Observation("fixture","1","音楽 🎼 music","ja",1),
                                 new NativeTree.Observation("fixture","2","Software engineering","en",2));
                need(tree.observe(rows)==2&&tree.observe(rows)==0,"batch/dedup");
                need(tree.query("音楽",0,0,10).get(0).body().equals("音楽 🎼 music"),"UTF-8");
                agent=tree.createAgent("Music","music");
                need(tree.agents().get(0).id()==agent&&tree.query("",agent,0,10).size()==1,"agent");
                need(tree.observe(List.of(new NativeTree.Observation("fixture","3","New music","en",3)))==1,"append");
                need(tree.query("",agent,0,10).size()==2,"growth");
                long before=tree.query("",0,0,1).get(0).sequence();
                need(tree.query("",0,before,10).size()==2,"cursor");
                Path gguf=dir.resolve("tree.gguf");
                need(tree.compile(gguf.toString())==tree.revision(),"revision");
                byte[] magic=Files.readAllBytes(gguf);
                need(magic.length>4&&magic[0]=='G'&&magic[1]=='G'&&magic[2]=='U'&&magic[3]=='F',"GGUF");
                need(tree.observe(List.of(new NativeTree.Observation("record.x.body","local:x:translation","PRIVATE_SOURCE_SENTINEL","ja",4),
                    new NativeTree.Observation("record.x.en.translation","local:x:translation","LOCAL_TRANSLATION_SENTINEL","en",5)))==2,"localized insert");
                var record=tree.records("",0,0,10).get(0);
                need(record.localized("en").body().equals("LOCAL_TRANSLATION_SENTINEL"),"localized master");
                Path localized=dir.resolve("localized.gguf");
                need(tree.compileLocalized(localized.toString(),"en")==tree.revision(),"localized revision");
                String projection=new String(Files.readAllBytes(localized),java.nio.charset.StandardCharsets.ISO_8859_1);
                need(projection.contains("LOCAL_TRANSLATION_SENTINEL")&&!projection.contains("PRIVATE_SOURCE_SENTINEL"),"localized JNI projection");
                var graph=KnowledgeGguf.read(localized.toFile());
                need(graph.language().equals("en")&&graph.nodes().size()==1&&graph.nodes().get(0).master().equals("local:x:translation"),"read compiled localized graph");
                var wave=new KnowledgeWave();
                var field=wave.update(graph,1000);
                need(field.weights().size()==1&&field.weight("local:x:translation",1000)!=null,"GGUF to wave");
                need(wave.update(graph,2000)==field,"same revision does not stimulate again");
            }
            try(NativeTree tree=new NativeTree(db.toString())){
                need(tree.count()==5&&tree.query("",agent,0,10).size()==2,"persistence");
                tree.clear();need(tree.count()==0,"clear");
            }
            System.out.println("Host JNI Tree binding checks passed.");
        }finally{
            try(var paths=Files.walk(dir)){
                for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);
            }
        }
    }
}
