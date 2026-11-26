import com.wds.sixstars.contextoverlay.tree.NativeTree;
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
            }
            try(NativeTree tree=new NativeTree(db.toString())){
                need(tree.count()==3&&tree.query("",agent,0,10).size()==2,"persistence");
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
