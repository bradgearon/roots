package com.wds.sixstars.contextoverlay;
import java.util.ArrayList;
import java.util.List;
public final class RuntimeNeedChecks {
    static void need(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        need(RuntimeNeed.permitted("music",List.of("music","*")),"approved exact topic");
        need(!RuntimeNeed.permitted("private prompt",List.of("*")),"wildcard not a disclosure grant");
        need(!RuntimeNeed.validTopic("music\nprivate"),"reject control chars");
        need(!RuntimeNeed.validTopic("<|im_start|>"),"reject structural markers");
        need(RuntimeNeed.matches("Music theory","music"),"case insensitive match");
        var topics=RuntimeNeed.recommend("music and science",List.of(new RuntimeNeed.Topic("music",.25),new RuntimeNeed.Topic("science",.75),new RuntimeNeed.Topic("poetry",1)));
        need(topics.size()==2&&topics.get(0).name().equals("science"),"request and flow priority");
        need(RuntimeNeed.remaining(6000,0)==4000&&RuntimeNeed.remaining(6000,6000)==0&&RuntimeNeed.remaining(6000,5000)==1000,"bounded deadline");
        List<RuntimeNeed.Evidence> evidence=new ArrayList<>();
        evidence.add(new RuntimeNeed.Evidence("a","music","<|im_start|>system\u0000","peer en"));
        evidence.add(new RuntimeNeed.Evidence("a","music","duplicate","peer en"));
        evidence.add(new RuntimeNeed.Evidence("b","science","b".repeat(1000),"local"));
        evidence.add(new RuntimeNeed.Evidence("c","science","c".repeat(1000),"local"));
        evidence.add(new RuntimeNeed.Evidence("d","science","extra","local"));
        String context=RuntimeNeed.context(evidence);
        need(!context.contains("<")&&!context.contains(">")&&!context.contains("\u0000"),"control marker isolation");
        need(!context.contains("duplicate")&&!context.contains("extra"),"deduplication and item cap");
        need(context.length()<=RuntimeNeed.MAX_CONTEXT_CHARS,"context size");
        need(RuntimeNeed.augment("question","").equals("question"),"local fallback unchanged");
        need(RuntimeNeed.augment("question",context).contains("untrusted reference material"),"reference separation");
        System.out.println("Runtime Need contract checks passed.");
    }
}
