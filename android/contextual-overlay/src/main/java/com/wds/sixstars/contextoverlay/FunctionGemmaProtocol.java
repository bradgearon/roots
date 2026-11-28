package com.wds.sixstars.contextoverlay;

import java.util.Collections;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Bounded FunctionGemma protocol. A parsed result is a proposal, never authority. */
public final class FunctionGemmaProtocol {
    private FunctionGemmaProtocol() {}
    private static final String START="<start_function_call>", END="<end_function_call>", ESC="<escape>";
    public static final int MAX_INPUT=1200;
    private static Set<String> set(String... values){return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));}
    private static final Map<String,Set<String>> REQUIRED;
    static {
        Map<String,Set<String>> r=new LinkedHashMap<>();
        r.put("search_knowledge",set("query"));
        r.put("compare_meanings",set("left","right"));
        r.put("propose_displacement",set("source","target","property"));
        r.put("abstain",set("reason"));
        REQUIRED=Collections.unmodifiableMap(r);
    }
    private static String literal(String value){return ESC+value+ESC;}
    static String tool(String name,String description,String[] names,String[] descriptions){
        StringBuilder b=new StringBuilder("<start_function_declaration>declaration:").append(name)
            .append("{description:").append(literal(description)).append(",parameters:{properties:{");
        for(int i=0;i<names.length;i++){
            if(i>0)b.append(',');
            b.append(names[i]).append(":{description:").append(literal(descriptions[i]))
                .append(",type:").append(literal("STRING")).append('}');
        }
        b.append("},required:[");
        for(int i=0;i<names.length;i++){if(i>0)b.append(',');b.append(literal(names[i]));}
        return b.append("],type:").append(literal("OBJECT")).append("}}<end_function_declaration>").toString();
    }
    private static final String TOOLS=
        tool("search_knowledge","Find stored evidence or knowledge about a concept or question.",
            new String[]{"query"},new String[]{"Knowledge or evidence to look up"})+
        tool("compare_meanings","Compare two concepts, words, symbols or their meanings.",
            new String[]{"left","right"},new String[]{"First concept","Second concept"})+
        tool("propose_displacement","Examine a metaphor or semantic displacement; propose a transfer of a property between two domains. This is a hypothesis, not a fact.",
            new String[]{"source","target","property"},new String[]{"Domain supplying the property","Domain receiving the property","Property, motion or relationship being transferred"})+
        tool("abstain","Use when none of the offered operations is appropriate or the request lacks information.",
            new String[]{"reason"},new String[]{"Short reason"});
    public static String prompt(String request){
        if(request==null||request.trim().isEmpty()||request.length()>MAX_INPUT||request.indexOf('<')>=0||request.indexOf('>')>=0||request.indexOf(0)>=0)
            throw new IllegalArgumentException("Enter 1–1200 characters without angle brackets or NUL.");
        // BOS is added by llama_tokenize; use the model's native developer/user/model turns.
        return "<start_of_turn>developer\nYou are a model that can do function calling with the following functions"+
            TOOLS+"<end_of_turn>\n<start_of_turn>user\n"+request.trim()+"<end_of_turn>\n<start_of_turn>model\n";
    }
    public static final class Proposal {
        public final String operation;
        public final Map<String,String> arguments;
        private Proposal(String operation,Map<String,String> args){
            this.operation=operation;
            this.arguments=Collections.unmodifiableMap(new LinkedHashMap<>(args));
        }
        @Override public String toString(){
            StringBuilder b=new StringBuilder(operation);
            arguments.forEach((k,v)->b.append('\n').append(k).append(": ").append(v));
            return b.toString();
        }
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("No valid single allowed function call returned.");}
    private static final class Cursor{
        final String s; int i=0;
        Cursor(String s){this.s=s;}
        void ws(){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;}
        boolean take(String t){ws();if(!s.startsWith(t,i))return false;i+=t.length();return true;}
        void expect(String t){if(!take(t))throw invalid();}
        String name(){
            ws();int a=i;
            while(i<s.length()){char ch=s.charAt(i);if(!(ch>='a'&&ch<='z'||ch=='_'))break;i++;}
            if(i==a||i-a>64)throw invalid();
            return s.substring(a,i);
        }
        String value(int max){
            expect(ESC);
            int end=s.indexOf(ESC,i);
            if(end<0||end-i>max)throw invalid();
            String v=s.substring(i,end).trim();i=end+ESC.length();
            if(v.isEmpty()||v.indexOf('<')>=0||v.indexOf('>')>=0)throw invalid();
            for(int j=0;j<v.length();j++)if(Character.isISOControl(v.charAt(j)))throw invalid();
            return v;
        }
    }
    /** Only the exact native function syntax, exact argument set, and a single call are accepted. */
    public static Proposal parse(String raw){return parse(raw,REQUIRED);}
    static Proposal parseOne(String raw,String operation,String... arguments){
        return parse(raw,Collections.singletonMap(operation,set(arguments)));
    }
    private static Proposal parse(String raw,Map<String,Set<String>> allowed){
        if(raw==null||raw.length()>4096)throw invalid();
        Cursor c=new Cursor(raw);
        c.expect(START);c.expect("call:");String operation=c.name();
        Set<String> required=allowed.get(operation);if(required==null)throw invalid();
        c.expect("{");
        Map<String,String> args=new LinkedHashMap<>();
        for(int n=0;n<required.size();n++){
            if(n!=0)c.expect(",");
            String k=c.name();if(!required.contains(k)||args.containsKey(k))throw invalid();
            c.expect(":");args.put(k,c.value(operation.equals("resolve_movements")?1600:400));
        }
        c.expect("}");c.expect(END);c.ws();
        if(c.i!=raw.length()||!args.keySet().equals(required))throw invalid();
        return new Proposal(operation,args);
    }
}
