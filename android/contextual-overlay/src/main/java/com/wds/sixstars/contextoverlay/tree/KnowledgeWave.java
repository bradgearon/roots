package com.wds.sixstars.contextoverlay.tree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Event-driven, bounded coordination derived from a committed GGUF revision.
 * No text, LLM inference, timers, peer transmission or durable private history.
 * Sparse normalized edges conserve the mass of each propagating pulse. */
public final class KnowledgeWave {
    private static final int DIM=128, NEIGHBORS=8, EXACT_LIMIT=1024, STEPS=4;
    private static final double HALF_LIFE_MS=20*60*1000.0, PASS=0.64;
    public record Weight(double affinity,double novelty,double convergence,double activation,int neighbors,boolean wanted,int understanding){
        public double priority(){return 8*affinity+5*novelty+10*convergence+22*activation;}
        Weight decay(double factor){return new Weight(affinity,novelty,convergence,activation*factor,neighbors,wanted,understanding);}
    }
    public record Field(long revision,String language,long updatedMs,Map<String,Weight> weights,int edges,int stimulated){
        public Weight weight(String key,long now){
            Weight w=weights.get(key);return w==null?null:w.decay(Math.pow(0.5,Math.max(0,now-updatedMs)/HALF_LIFE_MS));
        }
        public static Field empty(){return new Field(-1,"",0,Collections.emptyMap(),0,0);}
    }
    private static final class Master {
        final String key;final float[] vector=new float[DIM];final Set<String> branches=new HashSet<>();
        final Set<String> nodeKeys=new HashSet<>();
        int count,understanding;boolean wanted;long signature;
        Master(String key){this.key=key;}
        void add(KnowledgeGguf.Node node){
            if(!nodeKeys.add(node.key()))return;
            float scale=node.source().endsWith(".understanding")?0.7f:1f;
            if(scale<1)understanding++;
            for(int d=0;d<DIM;d++)vector[d]+=scale*node.vector()[d];
            count++;
            signature+=31L*node.key().hashCode()+Arrays.hashCode(node.vector());
        }
        void finish(){
            double norm=0;for(float x:vector)norm+=x*x;
            if(norm>0){double inv=1/Math.sqrt(norm);for(int d=0;d<DIM;d++)vector[d]*=inv;}
            signature+=31L*branches.hashCode()+(wanted?1231:1237);
        }
    }
    private record Neighbor(int index,double strength){}
    private record Memory(long signature,Set<String> neighbors){}
    private Map<String,Memory> previous=Collections.emptyMap();
    private Field field=Field.empty();
    public synchronized Field field(){return field;}
    public synchronized void clear(){previous=Collections.emptyMap();field=Field.empty();}
    private static double clamp(double n){return Math.max(0,Math.min(1,n));}
    private static double cosine(float[] a,float[] b){double dot=0;for(int i=0;i<DIM;i++)dot+=a[i]*b[i];return clamp(dot);}
    private static boolean intersects(Set<String> a,Set<String> b){for(String s:a)if(b.contains(s))return true;return false;}
    private static int[] prominent(float[] vector,double[] idf){
        int[] best=new int[6];Arrays.fill(best,-1);
        for(int d=0;d<DIM;d++){
            double value=vector[d]*idf[d];if(value<=0)continue;
            for(int i=0;i<best.length;i++)if(best[i]<0||value>vector[best[i]]*idf[best[i]]){
                System.arraycopy(best,i,best,i+1,best.length-i-1);best[i]=d;break;
            }
        }
        return best;
    }
    private static void boundedSample(Set<Integer> out,List<Integer> source,int max,int own){
        int n=source.size();if(n==0)return;
        int offset=Math.floorMod(own*31,n);
        if(n<=max){out.addAll(source);return;}
        for(int i=0;i<max;i++)out.add(source.get((offset+(int)((long)i*n/max))%n));
    }
    public synchronized Field update(KnowledgeGguf.Snapshot snapshot,long now){
        if(snapshot.revision()==field.revision()&&snapshot.language().equals(field.language()))return field;
        if(field.revision()>=0&&(!snapshot.language().equals(field.language())||snapshot.revision()<field.revision()))clear();
        Map<String,Master> masters=new TreeMap<>();Map<String,Master> byNode=new HashMap<>();
        for(KnowledgeGguf.Node node:snapshot.nodes()){
            Master m=masters.computeIfAbsent(node.master(),Master::new);m.add(node);byNode.put(node.key(),m);
        }
        for(KnowledgeGguf.Edge edge:snapshot.edges()){
            Master m=byNode.get(edge.subject());
            if(m==null&&edge.subject().startsWith("master:"))m=masters.get(edge.subject().substring(7));
            if(m==null)continue;
            if(edge.predicate().equals("is.in")&&edge.object().startsWith("branch:"))m.branches.add(edge.object());
            if(edge.predicate().equals("has.state")&&edge.object().equals("state:wanted"))m.wanted=true;
        }
        List<Master> nodes=new ArrayList<>(masters.values());int n=nodes.size();
        if(n==0){clear();field=new Field(snapshot.revision(),snapshot.language(),now,Collections.emptyMap(),0,0);return field;}
        int[] documentFrequency=new int[DIM];Map<String,List<Integer>> branchIndex=new HashMap<>();
        for(int i=0;i<n;i++){
            Master m=nodes.get(i);m.finish();
            for(int d=0;d<DIM;d++)if(m.vector[d]>0)documentFrequency[d]++;
            for(String branch:m.branches)branchIndex.computeIfAbsent(branch,k->new ArrayList<>()).add(i);
        }
        double[] idf=new double[DIM];for(int d=0;d<DIM;d++)idf[d]=1+Math.log((n+1.0)/(documentFrequency[d]+1.0));
        List<List<Integer>> buckets=new ArrayList<>(DIM);for(int d=0;d<DIM;d++)buckets.add(new ArrayList<>());
        int[][] features=new int[n][];
        for(int i=0;i<n;i++){features[i]=prominent(nodes.get(i).vector,idf);for(int d:features[i])if(d>=0)buckets.get(d).add(i);}
        List<List<Neighbor>> graph=new ArrayList<>(n);double[] affinity=new double[n],novelty=new double[n],convergence=new double[n],seed=new double[n],activation=new double[n];
        Map<String,Memory> nextMemory=new HashMap<>();int edgeCount=0,stimulated=0;
        for(int i=0;i<n;i++){
            Master m=nodes.get(i);Set<Integer> candidates=new HashSet<>();
            if(n<=EXACT_LIMIT){for(int j=0;j<n;j++)candidates.add(j);}
            else{
                for(int d:features[i])if(d>=0)boundedSample(candidates,buckets.get(d),48,i);
                // Cap branch candidates even for a broad branch or adversarial graph.
                List<String> branches=new ArrayList<>(m.branches);Collections.sort(branches);
                for(int j=0;j<Math.min(8,branches.size());j++)boundedSample(candidates,branchIndex.get(branches.get(j)),24,i);
            }
            candidates.remove(i);
            List<Neighbor> scored=new ArrayList<>();double max=0,mean=0;int compared=0;
            for(int j:candidates){double similarity=cosine(m.vector,nodes.get(j).vector);mean+=similarity;compared++;max=Math.max(max,similarity);}
            mean=compared==0?0:mean/compared;
            // Compare against the observed local background, not raw cosine alone.
            double floor=Math.max(0.48,mean+0.16);
            for(int j:candidates){
                Master other=nodes.get(j);double sim=cosine(m.vector,other.vector);
                double excess=clamp((sim-floor)/Math.max(0.08,1-floor));
                boolean shared=intersects(m.branches,other.branches);
                double strength=clamp(excess*0.85+(shared?0.35:0));
                if(strength>=0.10)scored.add(new Neighbor(j,strength));
            }
            scored.sort(Comparator.comparingDouble(Neighbor::strength).reversed().thenComparingInt(Neighbor::index));
            if(scored.size()>NEIGHBORS)scored=new ArrayList<>(scored.subList(0,NEIGHBORS));
            graph.add(scored);edgeCount+=scored.size();
            Set<String> links=new HashSet<>();double total=0;int cross=0;
            for(Neighbor neighbor:scored){
                Master other=nodes.get(neighbor.index());links.add(other.key);total+=neighbor.strength();
                if(!m.branches.isEmpty()&&!other.branches.isEmpty()&&!m.branches.equals(other.branches))cross++;
            }
            affinity[i]=clamp((m.wanted?0.7:0)+0.12*Math.min(4,m.branches.size()));
            novelty[i]=clamp(1-max);
            convergence[i]=clamp(0.65*total/NEIGHBORS+0.2*cross/NEIGHBORS+0.15*Math.min(2,m.understanding)/2.0);
            Memory old=previous.get(m.key);nextMemory.put(m.key,new Memory(m.signature,links));
            boolean fresh=old==null,changed=!fresh&&old.signature()!=m.signature;
            int gained=0;if(old!=null)for(String link:links)if(!old.neighbors().contains(link))gained++;
            Weight prior=field.weight(m.key,now);activation[i]=prior==null?0:prior.activation();
            double source=(fresh?0.40:changed?0.45:0)+0.25*gained/NEIGHBORS;
            if(source>0){source=clamp(source+0.2*affinity[i]+0.15*novelty[i]);stimulated++;}
            // Bootstrap from a snapshot without pretending every old record just arrived.
            if(field.revision()<0)source*=0.25;
            seed[i]=source;
        }
        // A finite cascade per revision. Only the new pulse propagates; old
        // activation decays lazily and is not retransmitted at each revision.
        double[] pulse=seed;
        for(int step=0;step<=STEPS;step++){
            double[] next=new double[n];
            for(int i=0;i<n;i++){
                double sum=0;for(Neighbor edge:graph.get(i))sum+=edge.strength();
                activation[i]=clamp(activation[i]+(sum<=0?1:1-PASS)*pulse[i]);
                if(step==STEPS||sum<=0)continue;
                for(Neighbor edge:graph.get(i))next[edge.index()]+=PASS*pulse[i]*edge.strength()/sum;
            }
            pulse=next;
        }
        Map<String,Weight> weights=new HashMap<>();
        for(int i=0;i<n;i++){
            Master m=nodes.get(i);
            weights.put(m.key,new Weight(affinity[i],novelty[i],convergence[i],activation[i],graph.get(i).size(),m.wanted,m.understanding));
        }
        previous=nextMemory;
        field=new Field(snapshot.revision(),snapshot.language(),now,Collections.unmodifiableMap(weights),edgeCount,stimulated);
        return field;
    }
}
