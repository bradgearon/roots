import com.wds.sixstars.contextoverlay.tree.KnowledgeGguf;
import com.wds.sixstars.contextoverlay.tree.KnowledgeWave;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/** Pure deterministic graph checks, no Android device or network required. */
public final class KnowledgeWaveChecks {
    static void need(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static float[] v(int a,int b){
        float[] vector=new float[128];
        if(a==b)vector[a]=1;
        else {vector[a]=(float)Math.sqrt(.8);vector[b]=(float)Math.sqrt(.2);}
        return vector;
    }
    static KnowledgeGguf.Node node(int id,String master,float[] v,String role){
        return new KnowledgeGguf.Node("fragment:"+id,"local:x:"+master,"record.x."+role,v);
    }
    static KnowledgeGguf.Snapshot graph(long rev,List<KnowledgeGguf.Node> nodes,List<KnowledgeGguf.Edge> edges){
        return new KnowledgeGguf.Snapshot(rev,"en","localized-private",nodes,edges);
    }
    public static void main(String[] args){
        var a=node(1,"a",v(1,2),"body");
        var b=node(2,"b",v(1,3),"body");
        var c=node(3,"c",v(9,9),"body");
        var base=graph(1,List.of(a,b,c),List.of());
        var engine=new KnowledgeWave();
        var first=engine.update(base,1000);
        need(first.weights().size()==3,"master count");
        need(first.weight("local:x:a",1000).neighbors()==1,"related pair");
        need(first.weight("local:x:c",1000).neighbors()==0,"isolated remains isolated");
        need(first.weight("local:x:c",1000).novelty()>.9,"isolated novelty");
        need(first.weight("local:x:a",1000).activation()>0,"bootstrap activation");
        double old=first.weight("local:x:a",1000).activation();
        need(Math.abs(first.weight("local:x:a",1201000).activation()-old*.5)<1e-8,"lazy half life");
        need(engine.update(base,2000)==first,"no repeated stimulation");
        var understood=node(4,"a",v(1,2),"en.understanding");
        var wanted=new KnowledgeGguf.Edge("master:local:x:a","has.state","state:wanted");
        var changed=graph(2,List.of(a,b,c,understood),List.of(wanted));
        long now=1201000;
        var second=engine.update(changed,now);
        need(second.stimulated()==1,"only changed master stimulated");
        need(second.weight("local:x:a",now).understanding()==1&&second.weight("local:x:a",now).wanted(),"state edges");
        need(second.weight("local:x:a",now).affinity()>.6,"wanted affinity");
        need(second.weight("local:x:b",now).activation()>first.weight("local:x:b",now).activation(),"pulse reaches neighbor");
        need(Math.abs(second.weight("local:x:c",now).activation()-first.weight("local:x:c",now).activation())<1e-8,"no isolated propagation");
        // Every value is bounded and a revision with no changes adds no pulse.
        var third=engine.update(graph(3,changed.nodes(),changed.edges()),now);
        need(third.stimulated()==0,"unrelated revision no stimulation");
        for(var weight:third.weights().values())need(weight.activation()>=0&&weight.activation()<=1&&weight.neighbors()<=8,"bounded field");
        var reverse=new ArrayList<>(changed.nodes());Collections.reverse(reverse);
        var other=new KnowledgeWave();other.update(base,1000);
        need(other.update(graph(2,reverse,changed.edges()),now).weights().equals(second.weights()),"deterministic node order");
        var reset=engine.update(new KnowledgeGguf.Snapshot(4,"ja","localized-private",List.of(c),List.of()),now);
        need(reset.weights().size()==1&&!reset.weights().containsKey("local:x:a"),"language reset");
        engine.clear();need(engine.field().weights().isEmpty(),"clear");
        System.out.println("Knowledge Wave contract checks passed.");
    }
}
