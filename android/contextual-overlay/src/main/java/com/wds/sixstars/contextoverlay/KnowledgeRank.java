package com.wds.sixstars.contextoverlay;
import com.wds.sixstars.contextoverlay.tree.NativeTree;
import com.wds.sixstars.contextoverlay.tree.KnowledgeWave;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** Transparent ranking using only observed relations/metrics and local preferences.
 * No claim of an inferred truth, understanding or credibility score. */
final class KnowledgeRank {
    record Ranked(NativeTree.Master record,double score,List<String> branches,int relations,int replicas,int demand,KnowledgeWave.Weight wave){}
    private static String fold(String value){return value.toLowerCase(Locale.ROOT);}
    private static Set<String> terms(String value){
        Set<String> result=new HashSet<>();
        for(String word:fold(value).split("[^\\p{L}\\p{N}]+"))if(word.length()>2&&result.size()<96)result.add(word);
        return result;
    }
    private static double metric(String text){
        try{
            String s=text.trim().toLowerCase(Locale.ROOT).replace(",","").replace(" ","");
            if(!s.matches("[0-9]+(?:\\.[0-9]+)?[kmb]?"))return 0;
            double scale=1;char end=s.charAt(s.length()-1);
            if(end=='k'||end=='m'||end=='b'){scale=end=='k'?1000:end=='m'?1000000:1000000000;s=s.substring(0,s.length()-1);}
            return Math.min(4,Math.log10(1+Double.parseDouble(s)*scale));
        }catch(Exception e){return 0;}
    }
    static List<Ranked> rank(List<NativeTree.Master> records,List<NativeTree.Agent> agents,String language,long now,MeshPeer mesh){
        return rank(records,agents,language,now,mesh::replicas,mesh::demand);
    }
    static List<Ranked> rank(List<NativeTree.Master> records,List<NativeTree.Agent> agents,String language,long now,java.util.function.ToIntFunction<String> replicasOf,java.util.function.ToIntFunction<String> demandOf){
        return rank(records,agents,language,now,replicasOf,demandOf,KnowledgeWave.Field.empty(),0);
    }
    static List<Ranked> rank(List<NativeTree.Master> records,List<NativeTree.Agent> agents,String language,long now,
        java.util.function.ToIntFunction<String> replicasOf,java.util.function.ToIntFunction<String> demandOf,KnowledgeWave.Field field,long elapsed){
        List<Ranked> results=new ArrayList<>();
        HashMap<String,Integer> frequency=new HashMap<>();
        HashMap<String,Set<String>> vocabulary=new HashMap<>();
        for(NativeTree.Master record:records){
            Set<String> words=terms(record.localized(language).body());
            vocabulary.put(record.key(),words);for(String word:words)frequency.merge(word,1,Integer::sum);
        }
        for(NativeTree.Master record:records){
            NativeTree.Fragment body=record.localized(language);
            List<String> branches=new ArrayList<>();Set<String> roles=new HashSet<>();
            StringBuilder relationText=new StringBuilder();
            for(NativeTree.Fragment f:record.members()){
                String role=f.source().substring(f.source().lastIndexOf('.')+1);
                if(!role.equals("offer")){roles.add(role);relationText.append(' ').append(f.body());}
            }
            String hay=fold(relationText.toString());
            for(NativeTree.Agent agent:agents)if(!agent.term().isEmpty()&&hay.contains(fold(agent.term())))branches.add(agent.name());
            Set<String> words=vocabulary.get(record.key());double connection=0;
            for(String word:words)connection+=Math.log1p(Math.max(0,frequency.getOrDefault(word,0)-1));
            if(!words.isEmpty())connection/=words.size();
            boolean linked=!XPostLink.forRecord(record).isEmpty();
            boolean localized=language.equals(body.language());
            double age=Math.max(0,now-body.at())/86400000.0;
            String id=record.key().startsWith("local:mesh:")?record.key().substring(11):"";
            int replicas=id.isEmpty()?0:replicasOf.applyAsInt(id),demand=id.isEmpty()?0:demandOf.applyAsInt(id);
            double score=12*Math.min(4,branches.size())+3*Math.min(6,roles.size())+7*Math.min(3,connection)
                +(linked?8:0)+(localized?10:0)+12/(1+age/3)+(roles.contains("understanding")?14:0)+(roles.contains("want")?18:0)
                +metric(record.latest("like-count"))+metric(record.latest("repost-count"))
                +metric(record.latest("reply-count"))+.5*metric(record.latest("view-count"))
                +Math.min(8,Math.log1p(demand)*2)+(replicas>0?8.0/replicas:0);
            KnowledgeWave.Weight wave=language.equals(field.language())?field.weight(record.key(),elapsed):null;
            if(wave!=null)score+=wave.priority();
            results.add(new Ranked(record,score,java.util.Collections.unmodifiableList(new ArrayList<>(branches)),roles.size(),replicas,demand,wave));
        }
        // Use the original observation timestamp shown on each card, not the
        // time a translation was generated or the local insertion sequence.
        // Existing knowledge weights break ties without promoting older items.
        results.sort(Comparator.comparingLong((Ranked r)->r.record().body().at()).reversed()
            .thenComparing(Comparator.comparingDouble(Ranked::score).reversed())
            .thenComparing(Comparator.comparingLong((Ranked r)->r.record().sequence()).reversed()));
        return results;
    }
}
