package com.wds.sixstars.contextoverlay;

import com.wds.sixstars.contextoverlay.tree.NativeTree;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class KnowledgeRankTest {
    private static NativeTree.Master record(long seq,long at,boolean wanted,boolean translated){
        String key="local:test:"+seq;
        List<NativeTree.Fragment> members=new java.util.ArrayList<>();
        members.add(new NativeTree.Fragment(seq,"record.x.body",key,"Some knowledge","en",at));
        if(wanted)members.add(new NativeTree.Fragment(seq+100,"record.x.want",key,"Wanted","en",at));
        if(translated)members.add(new NativeTree.Fragment(seq+200,"record.x.translation",key,"Translated knowledge","en",at+100000));
        return new NativeTree.Master(seq,key,members);
    }
    private static List<KnowledgeRank.Ranked> rank(NativeTree.Master... records){
        return KnowledgeRank.rank(Arrays.asList(records),java.util.Collections.emptyList(),"en",200000L,id->0,id->0);
    }
    @Test public void newestOriginalObservationWinsEvenAgainstOlderHigherWeight(){
        NativeTree.Master older=record(9,1000,true,true),newer=record(1,2000,false,false);
        List<KnowledgeRank.Ranked> results=rank(older,newer);
        assertEquals(newer,results.get(0).record());
        assertTrue(results.get(1).score()>results.get(0).score());
    }
    @Test public void equalTimestampsAreOrderedByExistingWeight(){
        NativeTree.Master ordinary=record(2,1000,false,false),wanted=record(1,1000,true,false);
        List<KnowledgeRank.Ranked> results=rank(ordinary,wanted);
        assertEquals(wanted,results.get(0).record());
        assertTrue(results.get(0).score()>results.get(1).score());
    }
}
