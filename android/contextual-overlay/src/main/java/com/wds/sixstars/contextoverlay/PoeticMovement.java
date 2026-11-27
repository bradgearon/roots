package com.wds.sixstars.contextoverlay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Two-request Forge-style movement experiment. No tool execution or storage. */
public final class PoeticMovement {
    private PoeticMovement(){}
    private static final int COUNT=5;
    public record Input(List<String> nouns,List<String> verbs,String profile,boolean rate){}
    public record Pair(String noun,String ordinary,String replacement){}
    // The ordinary action and original candidate lists cannot cross this boundary.
    public record Displacement(String noun,String replacement){}
    public record ResolutionInput(List<Displacement> movements,String profile,boolean rate){
        public ResolutionInput{movements=Collections.unmodifiableList(new ArrayList<>(movements));}
    }
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
    // Formatting tolerance is not semantic repair: never invent missing rows,
    // replace a model-selected word or accept an out-of-list word.
    private static List<String[]> fields(String raw){
        if(raw==null||raw.length()>4096)throw new IllegalArgumentException("Missing or oversized model response.");
        String value=raw.trim();
        if(value.startsWith("\u0060\u0060\u0060")){
            int start=value.indexOf('\n'),end=value.lastIndexOf("\u0060\u0060\u0060");
            if(start<0||end<=start||!value.substring(end+3).trim().isEmpty())throw new IllegalArgumentException("Incomplete code fence.");
            value=value.substring(start+1,end).trim();
        }
        List<String[]> result=new ArrayList<>();
        for(String part:value.split("[;\\r\\n]+")){
            String row=part.trim();if(row.isEmpty())continue;
            row=row.replaceFirst("^\\s*(?:[-*+]\\s+|\\d{1,2}[.)]\\s*)","");
            if(row.startsWith("|"))row=row.substring(1);
            if(row.endsWith("|"))row=row.substring(0,row.length()-1);
            String[] cols=row.split("\\|",-1);
            for(int i=0;i<cols.length;i++)cols[i]=cols[i].trim();
            // Ignore a Markdown table separator, but not arbitrary prose.
            boolean separator=cols.length>=2;
            for(String col:cols)separator&=col.matches(":?-{2,}:?");
            if(separator)continue;
            String first=cols[0].toLowerCase(Locale.ROOT);
            if(result.isEmpty()&&cols.length>=2&&(first.equals("noun")||first.equals("noun index")||first.equals("subject")||first.equals("index"))){
                String second=cols[1].toLowerCase(Locale.ROOT);
                if(second.matches("(verb( index)?|replacement( action)?|selected (verb|action)|sentence|expression|resolution|poetic sentence)"))continue;
            }
            result.add(cols);
        }
        return result;
    }
    private static int wordIndex(String field,List<String> options){
        String word=field.trim();
        if(word.matches("[0-9]{1,2}"))return index(word,options.size());
        for(int i=0;i<options.size();i++)if(options.get(i).equalsIgnoreCase(word))return i;
        throw new IllegalArgumentException("Selected word is not in the supplied list.");
    }
    static List<Pair> parseSelectionRows(Input in,String value){
        List<String[]> rows=fields(value);if(rows.size()!=COUNT)throw new IllegalArgumentException("Expected five pairs, received "+rows.size()+".");
        List<Pair> result=new ArrayList<>();Set<String> used=new HashSet<>();
        for(String[] row:rows){
            if(row.length!=3)throw new IllegalArgumentException("Expected noun|selected verb|default verb.");
            int n=wordIndex(row[0],in.nouns()),v=wordIndex(row[1],in.verbs());
            String usual=row[2].trim().toLowerCase(Locale.ROOT);
            if(!usual.matches("[a-z]{1,24}")||usual.equalsIgnoreCase(in.verbs().get(v)))throw new IllegalArgumentException("Default action must be a different single verb.");
            if(!used.add(n+":"+v))throw new IllegalArgumentException("Duplicate pair.");
            result.add(new Pair(in.nouns().get(n),usual,in.verbs().get(v)));
        }
        return Collections.unmodifiableList(result);
    }
    static String intelligenceSelection(Input in){
        return "Choose exactly five different noun and replacement-verb pairs from these lists. For each noun give its usual action as one English verb, different from the selected verb.\n"+
            "Nouns: "+String.join(", ",in.nouns())+"\nVerbs: "+String.join(", ",in.verbs())+"\nProfile: "+in.profile()+
            "\nOutput exactly five lines: noun|selected verb|default verb. Copy the noun and selected verb from the lists. No explanation.";
    }
    public static ResolutionInput resolutionInput(Input in,List<Pair> pairs){
        if(pairs.size()!=COUNT)throw new IllegalArgumentException("Five validated movements are required.");
        List<Displacement> movements=new ArrayList<>();
        for(Pair p:pairs)movements.add(new Displacement(p.noun(),p.replacement()));
        return new ResolutionInput(movements,in.profile(),in.rate());
    }
    public static String describeDisplacements(ResolutionInput in){
        StringBuilder b=new StringBuilder();int i=0;
        for(Displacement p:in.movements())b.append(++i).append(". ").append(p.noun()).append(" | ").append(p.replacement()).append('\n');
        return b.toString();
    }
    private static String resolutionTask(ResolutionInput in){
        return "Find a meaningful connection in each subject and action below. Express the relationship in one short sentence using mirth, depth, or poetic imagery. Keep its subject and action meaningful. At most 15 words per sentence.\n"+
            "Profile: "+in.profile()+"\n"+describeDisplacements(in)+
            (in.rate()?"Rate each expression from 1 to 10 for the profile and coherence.\n":"");
    }
    static String intelligenceResolution(ResolutionInput in){
        return resolutionTask(in)+"Return exactly five lines: "+(in.rate()?"index|sentence|rating 1-10":"index|sentence")+". No explanation.";
    }
    static String intelligenceResolution(Input in,List<Pair> pairs){return intelligenceResolution(resolutionInput(in,pairs));}
    private static int index(String s,int max){
        String v=s.trim();if(!v.matches("[0-9]{1,2}"))throw new IllegalArgumentException("Invalid index.");
        int i=Integer.parseInt(v)-1;if(i<0||i>=max)throw new IllegalArgumentException("Index outside supplied list.");return i;
    }
    public static String describe(List<Pair> pairs){
        StringBuilder b=new StringBuilder();int i=0;
        for(Pair p:pairs)b.append(++i).append(". ").append(p.noun()).append(": ").append(p.ordinary()).append(" → ").append(p.replacement()).append('\n');
        return b.toString();
    }
    public static String resolutionPrompt(ResolutionInput in){
        String schema=in.rate()?"index|poetic sentence|rating 1-10":"index|poetic sentence";
        return prompt("resolve_movements","Find a meaningful connection between each subject and its action.","resolutions",
            "Five rows separated by semicolons. Format "+schema+". No semicolons inside sentences.",
            resolutionTask(in)+"Return all five rows as "+schema+".");
    }
    // Compatibility entry point, still projects away all stage-one information.
    public static String resolutionPrompt(Input in,List<Pair> pairs){return resolutionPrompt(resolutionInput(in,pairs));}
    public static List<Resolution> parseResolution(Input in,String raw){
        return parseResolutionRows(in,FunctionGemmaProtocol.parseOne(raw,"resolve_movements","resolutions").arguments.get("resolutions"));
    }
    static List<Resolution> parseResolutionRows(Input in,String value){return parseResolutionRows(in.rate(),value);}
    static List<Resolution> parseResolutionRows(boolean rate,String value){
        List<String[]> rows=fields(value);if(rows.size()!=COUNT)throw new IllegalArgumentException("Expected five resolutions, received "+rows.size()+".");
        Resolution[] result=new Resolution[COUNT];
        for(String[] row:rows){
            if(row.length!=(rate?3:2))throw new IllegalArgumentException("Wrong resolution fields.");
            int i=index(row[0],COUNT);String expression=row[1].trim();
            if(expression.isEmpty()||expression.length()>240||result[i]!=null)throw new IllegalArgumentException("Invalid or duplicate resolution.");
            Integer rating=rate?index(row[2].replaceFirst("\\s*/\\s*10$",""),10)+1:null;
            result[i]=new Resolution(i+1,expression,rating);
        }
        return Collections.unmodifiableList(Arrays.asList(result));
    }

    interface Generator{
        String generate(String operation,String prompt,long[] stats,Consumer<String> partial)throws Exception;
    }
    interface Progress{void report(String stage,String message);}
    record Outcome(List<Pair> pairs,List<Resolution> resolutions,long[] firstStats,long[] secondStats){}
    /** Exactly two independent requests after a valid selection. No retries, invented pairs or hidden context. */
    static Outcome executeGeneral(Input input,Generator generator,Progress progress)throws Exception{
        long[] firstStats=new long[8],secondStats=new long[8];
        progress.report("select","Request 1 / 2: selecting words and default actions…");
        String first;
        try{first=generator.generate("Poetic selection",intelligenceSelection(input),firstStats,
            prefix->progress.report("selection_stream",prefix));}
        catch(Exception e){throw new IllegalStateException("Request 1 generation failed: "+e.getMessage(),e);}
        List<Pair> pairs;
        try{pairs=parseSelectionRows(input,first);}
        catch(IllegalArgumentException e){
            progress.report("invalid_selection",first);
            throw new IllegalArgumentException("Request 1 invalid; request 2 not started: "+e.getMessage(),e);
        }
        progress.report("selected",describe(pairs));
        ResolutionInput displaced=resolutionInput(input,pairs);
        String secondPrompt=intelligenceResolution(displaced);
        progress.report("resolution_input",secondPrompt);
        progress.report("resolve","Request 2 / 2: finding meaningful connections…");
        String second;
        try{second=generator.generate("Poetic resolution",secondPrompt,secondStats,
            prefix->progress.report("resolution_stream",prefix));}
        catch(Exception e){throw new IllegalStateException("Request 2 generation failed: "+e.getMessage(),e);}
        List<Resolution> resolutions;
        try{resolutions=parseResolutionRows(displaced.rate(),second);}
        catch(IllegalArgumentException e){
            progress.report("invalid_resolution",second);
            throw new IllegalArgumentException("Request 2 ran but returned invalid output: "+e.getMessage(),e);
        }
        return new Outcome(pairs,resolutions,firstStats,secondStats);
    }
}
