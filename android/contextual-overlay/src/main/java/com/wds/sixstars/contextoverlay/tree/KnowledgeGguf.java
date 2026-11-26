package com.wds.sixstars.contextoverlay.tree;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict reader for Roots' own GGUF v3 snapshots, not arbitrary model GGUF.
 * Reads vector/relationship data without reading or retaining node.text. */
public final class KnowledgeGguf {
    private static final int MAX_NODES=10000, MAX_ARRAY=100000, DIM=128;
    private static final long MAX_FILE=512L*1024*1024;
    public record Node(String key,String master,String source,float[] vector){}
    public record Edge(String subject,String predicate,String object){}
    public record Snapshot(long revision,String language,String scope,List<Node> nodes,List<Edge> edges){}
    private KnowledgeGguf(){}
    private static void require(boolean condition,String reason)throws IOException{
        if(!condition)throw new IOException("Roots GGUF: "+reason);
    }
    private static int u32(RandomAccessFile f)throws IOException{return Integer.reverseBytes(f.readInt());}
    private static long u64(RandomAccessFile f)throws IOException{return Long.reverseBytes(f.readLong());}
    private static long bounded(long v,long max)throws IOException{require(v>=0&&v<=max,"invalid length");return v;}
    private static void skip(RandomAccessFile f,long n)throws IOException{
        require(n>=0&&n<=f.length()-f.getFilePointer(),"truncated metadata");f.seek(f.getFilePointer()+n);
    }
    private static String string(RandomAccessFile f,boolean keep)throws IOException{
        int n=(int)bounded(u64(f),65536);
        if(!keep){skip(f,n);return "";}
        byte[] bytes=new byte[n];f.readFully(bytes);return new String(bytes,StandardCharsets.UTF_8);
    }
    private static boolean keepArray(String name){
        return name.equals("roots.node.keys")||name.equals("roots.node.source")||name.equals("roots.node.external_id")
            ||name.equals("roots.edge.subject")||name.equals("roots.edge.predicate")||name.equals("roots.edge.object");
    }
    private static List<String> array(Map<String,List<String>> arrays,String name,int size)throws IOException{
        List<String> values=arrays.get(name);require(values!=null&&values.size()==size,"misaligned "+name);return values;
    }
    public static Snapshot read(File file)throws IOException{
        try(RandomAccessFile f=new RandomAccessFile(file,"r")){
            require(f.length()>=24&&f.length()<=MAX_FILE,"file size");
            require(f.readInt()==0x47475546&&u32(f)==3,"magic/version");
            require(u64(f)==1,"expected one embedding tensor");
            int count=(int)bounded(u64(f),64),alignment=32;
            long revision=-1;String language="",scope="",architecture="",profile="";
            Map<String,List<String>> arrays=new HashMap<>();Set<String> names=new HashSet<>();
            for(int i=0;i<count;i++){
                String name=string(f,true);require(names.add(name),"duplicate metadata key");
                int type=u32(f);
                switch(type){
                    case 8 -> {
                        boolean keep=name.equals("general.architecture")||name.equals("roots.vector.profile")||name.equals("roots.local.language")||name.equals("roots.scope");
                        String value=string(f,keep);
                        switch(name){
                            case "general.architecture" -> architecture=value;
                            case "roots.vector.profile" -> profile=value;
                            case "roots.local.language" -> language=value;
                            case "roots.scope" -> scope=value;
                        }
                    }
                    case 4 -> {int value=u32(f);if(name.equals("general.alignment"))alignment=value;}
                    case 10 -> {long value=u64(f);if(name.equals("roots.tree.revision"))revision=value;}
                    case 9 -> {
                        int element=u32(f);int n=(int)bounded(u64(f),MAX_ARRAY);
                        boolean keep=keepArray(name);
                        if(element==8){
                            List<String> values=keep?new ArrayList<>(n):null;
                            for(int j=0;j<n;j++){String value=string(f,keep);if(keep)values.add(value);}
                            if(keep)arrays.put(name,values);
                        }else if(element==10){require(!keep,"wrong array element");skip(f,8L*n);}
                        else throw new IOException("Roots GGUF: unsupported array element");
                    }
                    default -> throw new IOException("Roots GGUF: unsupported metadata type");
                }
            }
            require(architecture.equals("roots-tree")&&profile.equals("roots.lexical.utf8-trigram.f32.v1")&&revision>=0,"architecture/profile/revision");
            require(alignment==32,"alignment");
            require(string(f,true).equals("node.embedding")&&u32(f)==2&&u64(f)==DIM,"embedding shape");
            int n=(int)bounded(u64(f),MAX_NODES);require(n>0&&u32(f)==0&&u64(f)==0,"F32 tensor");
            long offset=(f.getFilePointer()+alignment-1)&~(alignment-1L);
            require(offset<=f.length()&&f.length()-offset==4L*DIM*n,"tensor length");
            List<String> keys=array(arrays,"roots.node.keys",n),sources=array(arrays,"roots.node.source",n),masters=array(arrays,"roots.node.external_id",n);
            List<String> subjects=arrays.get("roots.edge.subject");require(subjects!=null,"missing edges");
            List<String> predicates=array(arrays,"roots.edge.predicate",subjects.size()),objects=array(arrays,"roots.edge.object",subjects.size());
            List<Edge> edges=new ArrayList<>(subjects.size());for(int i=0;i<subjects.size();i++)edges.add(new Edge(subjects.get(i),predicates.get(i),objects.get(i)));
            f.seek(offset);List<Node> nodes=new ArrayList<>();Set<String> nodeKeys=new HashSet<>();
            for(int i=0;i<n;i++){
                String key=keys.get(i),source=sources.get(i),master=masters.get(i);
                require(nodeKeys.add(key)&&key.matches("fragment:[0-9]+"),"invalid node key");
                boolean include=master.startsWith("local:")&&source.startsWith("record.")&&
                    (source.endsWith(".body")||source.endsWith(".translation")||source.endsWith(".localized")||source.endsWith(".understanding"));
                if(!include){skip(f,4L*DIM);continue;}
                float[] v=new float[DIM];double norm=0;
                for(int j=0;j<DIM;j++){
                    float x=Float.intBitsToFloat(u32(f));require(Float.isFinite(x)&&x>=0&&x<=1.001f,"invalid embedding");
                    v[j]=x;norm+=x*x;
                }
                require(norm>0.99&&norm<1.01,"embedding norm");
                nodes.add(new Node(key,master,source,v));
            }
            return new Snapshot(revision,language,scope,java.util.Collections.unmodifiableList(nodes),java.util.Collections.unmodifiableList(edges));
        }
    }
}
