package com.wds.sixstars.contextoverlay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Local weighted fair receive order. Each item is assigned once; scarcity
 * and observed demand order a lane, while its share determines service rate. */
final class AxonPlanner {
    record Lane(String topic,double mass){}
    record Offer(String id,String topic,int replicas,int demand,boolean localLanguage){}
    record Share(String topic,double fraction){}
    record Need(String topic,double knowledge,double observedElement){}
    /** Knowledge prior remains half the budget when actual mapped flow is present. */
    static List<Share> allocate(List<Need> needs){
        double knowledge=0,observed=0;
        for(Need n:needs){if(Double.isFinite(n.knowledge())&&n.knowledge()>0)knowledge+=n.knowledge();if(Double.isFinite(n.observedElement())&&n.observedElement()>0)observed+=n.observedElement();}
        if(!(knowledge>0)||!Double.isFinite(knowledge)||!Double.isFinite(observed))return Collections.emptyList();
        List<Lane> mixed=new ArrayList<>();
        for(Need n:needs){
            double local=Double.isFinite(n.knowledge())?Math.max(0,n.knowledge())/knowledge:0;
            double flow=observed>0&&Double.isFinite(n.observedElement())?Math.max(0,n.observedElement())/observed:0;
            mixed.add(new Lane(n.topic(),observed>0?.5*local+.5*flow:local));
        }
        return shares(mixed);
    }
    static List<Share> shares(List<Lane> lanes){
        double total=0;for(Lane lane:lanes)if(Double.isFinite(lane.mass())&&lane.mass()>0)total+=lane.mass();
        if(!(total>0)||!Double.isFinite(total))return Collections.emptyList();
        List<Share> result=new ArrayList<>();
        for(Lane lane:lanes)if(Double.isFinite(lane.mass())&&lane.mass()>0)result.add(new Share(lane.topic(),lane.mass()/total));
        return result;
    }
    static String lane(String topic,List<Share> shares){
        String text=topic.toLowerCase(Locale.ROOT),best="";
        for(Share share:shares){
            String term=share.topic().toLowerCase(Locale.ROOT);
            if(term.equals("*")){if(best.isEmpty())best="*";}
            else if(text.contains(term)&&(best.equals("*")||term.length()>best.length()))best=share.topic();
        }
        return best;
    }
    static List<String> order(List<Offer> offers,List<Share> shares,int max){
        if(max<=0)return Collections.emptyList();
        Map<String,List<Offer>> queues=new HashMap<>();
        java.util.Set<String> seen=new java.util.HashSet<>();
        for(Offer o:offers){
            if(!seen.add(o.id()))continue;
            String lane=lane(o.topic(),shares);
            if(!lane.isEmpty())queues.computeIfAbsent(lane,k->new ArrayList<>()).add(o);
        }
        Comparator<Offer> priority=Comparator.comparingInt(Offer::replicas)
            .thenComparing(Comparator.comparingInt(Offer::demand).reversed())
            .thenComparingInt(o->o.localLanguage()?0:1).thenComparing(Offer::id);
        for(List<Offer> lane:queues.values())lane.sort(priority);
        Map<String,Integer> positions=new HashMap<>();
        List<String> result=new ArrayList<>();
        while(result.size()<max){
            Share chosen=null;double finish=Double.POSITIVE_INFINITY;
            for(Share share:shares){
                List<Offer> queue=queues.get(share.topic());
                int position=positions.getOrDefault(share.topic(),0);
                if(queue==null||position>=queue.size()||share.fraction()<=0)continue;
                double score=(position+1)/share.fraction();
                if(score<finish||(score==finish&&chosen!=null&&share.topic().compareTo(chosen.topic())<0)){
                    chosen=share;finish=score;
                }
            }
            if(chosen==null)break;
            int position=positions.getOrDefault(chosen.topic(),0);
            result.add(queues.get(chosen.topic()).get(position).id());
            positions.put(chosen.topic(),position+1);
        }
        return result;
    }
}
