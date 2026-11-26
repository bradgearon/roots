package com.wds.sixstars.contextoverlay;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Local cryptographic word generator. Entropy is provided by the platform
 * CSPRNG; no application seed, hashing, or model call. Sample without replacement. */
public final class CryptoWordGenerator {
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final int COUNT=12;
    private CryptoWordGenerator(){}
    private static final String[] NOUNS=("sea lantern stone garden memory river flame mountain rain mirror window door " +
        "clock feather bridge seed root wind cloud shadow thread star bell book ocean shore silence " +
        "forest sky dust glass flower road tide heart hand leaf moon sun song echo snow bird " +
        "bread ink wave island horizon well needle vessel candle").split(" ");
    private static final String[] VERBS=("remember forgive shelter awaken dissolve carry gather scatter conceal reveal " +
        "listen breathe mend weave release cradle resist bloom wander return tremble mourn " +
        "question heal guide break bind illuminate refuse endure wait sing taste dream " +
        "measure tend welcome forget nourish chase protect anchor soften carve embrace").split(" ");
    public record Words(List<String> nouns,List<String> verbs){
        public Words{nouns=Collections.unmodifiableList(new ArrayList<>(nouns));verbs=Collections.unmodifiableList(new ArrayList<>(verbs));}
    }
    public static Words generate(){return new Words(pick(NOUNS),pick(VERBS));}
    private static List<String> pick(String[] words){
        List<String> pool=new ArrayList<>(Arrays.asList(words));
        for(int i=0;i<COUNT;i++){
            int j=i+RANDOM.nextInt(pool.size()-i);
            Collections.swap(pool,i,j);
        }
        return Collections.unmodifiableList(new ArrayList<>(pool.subList(0,COUNT)));
    }
}
