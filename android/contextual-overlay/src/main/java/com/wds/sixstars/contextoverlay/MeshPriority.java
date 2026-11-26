package com.wds.sixstars.contextoverlay;

/** Deterministic local-interest-first, rarest-first selection of missing items. */
final class MeshPriority {
    record Score(String id,int interest,int replicas,int demand,boolean localLanguage){}
    static int compare(Score a,Score b){
        int c=Integer.compare(b.interest(),a.interest());if(c!=0)return c;
        c=Integer.compare(a.replicas(),b.replicas());if(c!=0)return c;
        c=Integer.compare(b.demand(),a.demand());if(c!=0)return c;
        c=Boolean.compare(b.localLanguage(),a.localLanguage());if(c!=0)return c;
        return a.id().compareTo(b.id());
    }
}
