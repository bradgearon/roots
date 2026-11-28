package com.wds.sixstars.contextoverlay;
import org.junit.Test;
import static org.junit.Assert.*;
import com.wds.sixstars.contextoverlay.tree.NativeTree;
import java.util.ArrayList;
import java.util.List;
public class MeshKnowledgeTest {
    @Test public void exactStatusUrls(){
        assertEquals("https://x.com/example/status/123",XPostLink.canonical("https://twitter.com/example/status/123?s=20"));
        assertEquals("https://x.com/i/web/status/99",XPostLink.canonical("https://x.com/i/web/status/99"));
        assertEquals("",XPostLink.canonical("https://x.com.evil.test/example/status/123"));
        assertEquals("",XPostLink.canonical("https://x.com/search?q=post"));
        assertEquals("",XPostLink.canonical("http://x.com/example/status/123"));
    }
    @Test public void immutableContentIds()throws Exception{
        MeshItem a=MeshItem.create("en","music","A relationship","https://twitter.com/example/status/123?s=20");
        MeshItem b=MeshItem.create("en","music","A relationship","https://x.com/example/status/123");
        assertEquals(a.id(),b.id());assertEquals(64,a.id().length());
        assertNotEquals(a.id(),MeshItem.create("en","music","Changed","https://x.com/example/status/123").id());
        assertNotEquals(a.id(),MeshItem.create("fr","music","A relationship","https://x.com/example/status/123").id());
    }
    @Test public void wantedThenRareThenDemand(){
        List<MeshPriority.Score> rows=new ArrayList<>(List.of(new MeshPriority.Score("common",1,3,50,true),
            new MeshPriority.Score("rare",1,1,2,true),new MeshPriority.Score("rare-demand",1,1,7,false),
            new MeshPriority.Score("unwanted",0,1,100,true)));
        rows.sort(MeshPriority::compare);
        assertEquals(List.of("rare-demand","rare","common","unwanted"),rows.stream().map(MeshPriority.Score::id).toList());
    }
    @Test public void knowledgeUsesLocalizedAndUnderstanding(){
        NativeTree.Master a=new NativeTree.Master(1,"local:x:a",List.of(
            new NativeTree.Fragment(1,"record.x.body","local:x:a","日本語","ja",1),
            new NativeTree.Fragment(2,"record.x.en.translation","local:x:a","Music and relationships","en",2),
            new NativeTree.Fragment(3,"record.x.en.understanding","local:x:a","Music connects ideas","en",3),
            new NativeTree.Fragment(4,"record.x.want","local:x:a","wanted","",4)));
        NativeTree.Master b=new NativeTree.Master(5,"local:x:b",List.of(new NativeTree.Fragment(5,"record.x.body","local:x:b","Newer post","en",6)));
        var ranked=KnowledgeRank.rank(List.of(b,a),List.of(new NativeTree.Agent(1,"Music","music")),"en",7,id->0,id->0);
        assertEquals(a.key(),ranked.get(0).record().key());
        assertEquals("Music and relationships",a.localized("en").body());
        assertEquals("日本語",a.body().body());
    }
}
