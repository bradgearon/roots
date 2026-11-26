package com.wds.sixstars.contextoverlay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Two-request Forge-style movement experiment. No tool execution or storage. */
public final class PoeticMovement {
    private PoeticMovement(){}
    private static final int COUNT=5;
    public record Input(List<String> nouns,List<String> verbs,String profile,boolean rate){}
    public record Pair(String noun,String ordinary,String replacement){}
    public record Resolution(int index,String expression,Integer rating){}
    public record Run(Input input,List<Pair> pairs,List<Resolution> resolutions,long[] firstStats,long[] secondStats,long elapsedMs){}
    public static Input input(CryptoWordGenerator.Words words,String profile,boolean rate){
        String clean=profile==null?"":profile.trim();
        if(clean.length()>160||clean.matches("(?s).*[<>\\p{Cntrl}].*"))throw new IllegalArgumentException("Profile must be at most 160 plain-text characters.");
        if(words==null||words.nouns().size()!=12||words.verbs().size()!=12)throw new IllegalArgumentException("Generate word lists first.");
        return new Input(words.nouns(),words.verbs(),clean,rate);
    }
    private static String list(List<String> words){
        StringBuilder b=new StringBuilder();for(int i=0;i<words.size();i++){if(i>0)b.append(", ");b.append(i+1).append('=').append(words.get(i));}return b.toString();
    }
    private static String prompt(String name,String description,String argument,String argumentDescription,String user){
        return "<start_of_turn>developer\nYou are a model that can do function calling with the following functions"+
            FunctionGemmaProtocol.tool(name,description,new String[]{argument},new String[]{argumentDescription})+
            "<end_of_turn>\n<start_of_turn>user\n"+user+"<end_of_turn>\n<start_of_turn>model\n";
    }
    public static String selectionPrompt(Input in){
        return prompt("select_movements","Choose five unusual meaningful noun and verb pairs.","pairs",
            "Exactly five rows separated by semicolons. Each row noun index|verb index|ordinary action (one verb).",
            "Nouns: "+list(in.nouns())+"\nVerbs: "+list(in.verbs())+"\nProfile: "+in.profile()+
            "\nChoose five distinct pairs. For each, identify the noun's usual action (one base-form verb, not the chosen verb). Return noun index|verb index|usual verb; repeat five times.");
    }
    public static List<Pair> parseSelection(Input in,String raw){
        return parseSelectionRows(in,FunctionGemmaProtocol.parseOne(raw,"select_movements","pairs").arguments.get("pairs"));
    }
    static List<Pair> parseSelectionRows(Input in,String value){
        String[] rows=value.split(";",-1);if(rows.length!=COUNT)throw new IllegalArgumentException("Expected exactly five pairs.");
        List<Pair> result=new ArrayList<>();Set<String> used=new HashSet<>();
        for(String row:rows){
            String[] fields=row.trim().split("\\|",-1);if(fields.length!=3)throw new IllegalArgumentException("Expected noun|verb|ordinary action.");
            int n=index(fields[0],in.nouns().size()),v=index(fields[1],in.verbs().size());
            String usual=fields[2].trim().toLowerCase(Locale.ROOT);
            if(!usual.matches("[a-z]{1,24}")||usual.equals(in.verbs().get(v)))throw new IllegalArgumentException("Ordinary action must be a different single verb.");
            if(!used.add(n+":"+v))throw new IllegalArgumentException("Duplicate pair.");
            result.add(new Pair(in.nouns().get(n),usual,in.verbs().get(v)));
        }
        return Collections.unmodifiableList(result);
    }
    static String intelligenceSelection(Input in){
        return "Choose exactly five distinct unusual noun/verb combinations from the indexed lists. For each identify the ordinary action of the noun (one English base verb different from its chosen replacement). Use this exact format: noun index|verb index|ordinary verb. Separate five rows by semicolons. No headings or commentary. Example format only: 1|2|grow;3|4|shine;5|6|flow;7|8|fall;9|10|rise.\nNouns: "+list(in.nouns())+"\nVerbs: "+list(in.verbs())+"\nProfile: "+in.profile();
    }
    static String intelligenceResolution(Input in,List<Pair> pairs){
        return "For each numbered noun, replace its ordinary action (left of arrow) with the new verb (right of arrow). Discover a meaningful relationship explaining the displacement in a poetic sentence of at most 15 words. Return exactly five semicolon-separated rows, each "+(in.rate()?"index|sentence|rating 1-10":"index|sentence")+". No headings, extra commentary, or semicolons within sentences.\nProfile: "+in.profile()+"\n"+describe(pairs);
    }
    private static int index(String s,int max){
        String v=s.trim();if(!v.matches("[0-9]{1,2}"))throw new IllegalArgumentException("Invalid index.");
        int i=Integer.parseInt(v)-1;if(i<0||i>=max)throw new IllegalArgumentException("Index outside supplied list.");return i;
    }
    public static String describe(List<Pair> pairs){
        StringBuilder b=new StringBuilder();int i=0;
        for(Pair p:pairs)b.append(++i).append(". ").append(p.noun()).append(": ").append(p.ordinary()).append(" → ").append(p.replacement()).append('\n');
        return b.toString();
    }
    public static String resolutionPrompt(Input in,List<Pair> pairs){
        String schema=in.rate()?"index|poetic sentence|rating 1-10":"index|poetic sentence";
        return prompt("resolve_movements","Resolve each displaced action as a meaningful poetic sentence.","resolutions",
            "Five rows separated by semicolons. Format "+schema+". No semicolons inside sentences.",
            "Profile: "+in.profile()+"\n"+describe(pairs)+
            "For each subject, remove its ordinary action and replace it with the selected verb. Resolve the difference in one short poetic sentence (max 15 words). "+
            (in.rate()?"Rate each resolution 1-10 for depth and coherence. ":"")+
            "Return all five rows as "+schema+".");
    }
    public static List<Resolution> parseResolution(Input in,String raw){
        return parseResolutionRows(in,FunctionGemmaProtocol.parseOne(raw,"resolve_movements","resolutions").arguments.get("resolutions"));
    }
    static List<Resolution> parseResolutionRows(Input in,String value){
        String[] rows=value.split(";",-1);if(rows.length!=COUNT)throw new IllegalArgumentException("Expected five resolutions.");
        Resolution[] result=new Resolution[COUNT];
        for(String row:rows){
            String[] fields=row.trim().split("\\|",-1);
            if(fields.length!=(in.rate()?3:2))throw new IllegalArgumentException("Wrong resolution fields.");
            int i=index(fields[0],COUNT);String expression=fields[1].trim();
            if(expression.isEmpty()||expression.length()>240||result[i]!=null)throw new IllegalArgumentException("Invalid or duplicate resolution.");
            Integer rating=in.rate()?index(fields[2],10)+1:null;
            result[i]=new Resolution(i+1,expression,rating);
        }
        return Collections.unmodifiableList(Arrays.asList(result));
    }
}
