package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private model-specific activation baselines, trained only from observed
 * dense inference. No prompt, token, raw activation, output or model weight
 * values are written. Model hash and operation fence incompatible profiles. */
final class ElementFlow {
    private static final String PREFS="roots_element_flow";
    private static final int MAX_ROWS=32768, PREDICT=16;
    static final String[] OPERATIONS={"Intelligence sample","Poetic selection","Poetic resolution"};
    record Key(int phase,String family,int layer,int group){}
    record Group(Key key,double mass,int samples){}
    record Profile(int requests,List<Group> groups,int predicted,int hits,long advised,int skipped){
        static Profile empty(){return new Profile(0,Collections.emptyList(),0,0,0,0);}
    }
    static boolean enabled(Context c){return c.getSharedPreferences(PREFS,0).getBoolean("enabled",false);}
    static void setEnabled(Context c,boolean value){c.getSharedPreferences(PREFS,0).edit().putBoolean("enabled",value).apply();}
    private static AtomicFile file(Context c,IntelligenceModel model,String operation){
        int op=java.util.Arrays.asList(OPERATIONS).indexOf(operation);
        if(op<0)throw new IllegalArgumentException("Unknown intelligence operation");
        File dir=new File(c.getNoBackupFilesDir(),"element-flow");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("Flow directory unavailable");
        return new AtomicFile(new File(dir,model.sha+"-"+op+".json"));
    }
    private static boolean valid(Key key,double mass,int samples){
        return key.phase()>=0&&key.phase()<=1&&(key.family().equals("ffn")||key.family().equals("attention")||key.family().equals("residual"))
            &&key.layer()>=0&&key.layer()<128&&key.group()>=0&&key.group()<1024
            &&Double.isFinite(mass)&&mass>=0&&mass<=1.001&&samples>0&&samples<=1000000;
    }
    static synchronized Profile read(Context c,IntelligenceModel model,String operation){
        try{
            AtomicFile f=file(c,model,operation);
            if(!f.getBaseFile().isFile()||f.getBaseFile().length()>4*1024*1024)return Profile.empty();
            JSONObject json=new JSONObject(new String(f.readFully(),StandardCharsets.UTF_8));
            if(json.getInt("version")!=1||!model.sha.equals(json.getString("model"))||!operation.equals(json.getString("operation")))return Profile.empty();
            JSONArray rows=json.getJSONArray("groups");if(rows.length()>MAX_ROWS)return Profile.empty();
            List<Group> groups=new ArrayList<>();
            for(int i=0;i<rows.length();i++){
                JSONArray row=rows.getJSONArray(i);
                Key key=new Key(row.getInt(0),row.getString(1),row.getInt(2),row.getInt(3));
                double mass=row.getDouble(4);int samples=row.getInt(5);
                if(valid(key,mass,samples))groups.add(new Group(key,mass,samples));
            }
            return new Profile(Math.max(0,json.optInt("requests")),groups,json.optInt("predicted"),json.optInt("hits"),json.optLong("advised"),json.optInt("skipped"));
        }catch(Exception e){return Profile.empty();}
    }
    private static List<Group> hot(List<Group> groups,String family){
        List<Group> rows=new ArrayList<>();
        boolean decode=false;for(Group g:groups)if(g.key.phase()==1&&g.key.family().equals(family)){decode=true;break;}
        for(Group g:groups)if(g.key.family().equals(family)&&g.key.phase()==(decode?1:0)&&g.mass()>0)rows.add(g);
        rows.sort(Comparator.comparingDouble(Group::mass).reversed()
            .thenComparingInt(g->g.key.layer()).thenComparingInt(g->g.key.group()));
        return rows;
    }
    static int[] prediction(Context c,IntelligenceModel model,String operation){
        List<Group> rows=hot(read(c,model,operation).groups(),"ffn");
        int[] result=new int[2*Math.min(PREDICT,rows.size())];
        for(int i=0;i<result.length/2;i++){result[i*2]=rows.get(i).key.layer();result[i*2+1]=rows.get(i).key.group();}
        return result;
    }
    static synchronized void record(Context c,IntelligenceModel model,String operation,int[] prediction,String report)throws Exception{
        if(report==null||report.length()>4*1024*1024)return;
        String[] lines=report.split("\n");if(lines.length<2||lines.length>MAX_ROWS+1)return;
        String[] header=lines[0].split("\t");if(header.length!=3||!header[0].equals("v1"))return;
        long advised=Long.parseLong(header[1]);int skipped=Integer.parseInt(header[2]);
        List<Group> measured=new ArrayList<>();
        for(int i=1;i<lines.length;i++){
            String[] f=lines[i].split("\t");if(f.length!=6)continue;
            Key key=new Key(Integer.parseInt(f[0]),f[1],Integer.parseInt(f[2]),Integer.parseInt(f[3]));
            double mass=Double.parseDouble(f[4]);int samples=Integer.parseInt(f[5]);
            if(valid(key,mass,samples))measured.add(new Group(key,mass,samples));
        }
        if(measured.isEmpty())return;
        Profile old=read(c,model,operation);
        Map<Key,Group> merged=new HashMap<>();
        for(Group group:old.groups())merged.put(group.key,group);
        for(Group group:measured){
            Group prior=merged.get(group.key);
            // An exponential moving average gives recent requests influence.
            double mass=prior==null?group.mass():.8*prior.mass()+.2*group.mass();
            int count=Math.min(1000000,group.samples()+(prior==null?0:prior.samples()));
            merged.put(group.key,new Group(group.key,mass,count));
        }
        if(merged.size()>MAX_ROWS)return;
        List<Group> actual=hot(measured,"ffn");
        java.util.Set<String> strongest=new java.util.HashSet<>();
        for(int i=0;i<Math.min(PREDICT,actual.size());i++)strongest.add(actual.get(i).key.layer()+":"+actual.get(i).key.group());
        int hits=0,predicted=actual.isEmpty()?0:prediction.length/2;
        for(int i=0;i<predicted;i++)if(strongest.contains(prediction[2*i]+":"+prediction[2*i+1]))hits++;
        List<Group> groups=new ArrayList<>(merged.values());
        groups.sort(Comparator.comparingInt((Group g)->g.key.phase()).thenComparing(g->g.key.family()).thenComparingInt(g->g.key.layer()).thenComparingInt(g->g.key.group()));
        JSONArray array=new JSONArray();
        for(Group group:groups){
            Key k=group.key;
            array.put(new JSONArray().put(k.phase()).put(k.family()).put(k.layer()).put(k.group()).put(group.mass()).put(group.samples()));
        }
        JSONObject json=new JSONObject().put("version",1).put("model",model.sha).put("operation",operation)
            .put("requests",Math.min(1000000,old.requests()+1)).put("groups",array)
            .put("predicted",predicted).put("hits",hits).put("advised",advised).put("skipped",skipped);
        AtomicFile f=file(c,model,operation);FileOutputStream out=f.startWrite();
        try{out.write(json.toString().getBytes(StandardCharsets.UTF_8));f.finishWrite(out);}
        catch(Exception e){f.failWrite(out);throw e;}
    }
    /** Share of measured FFN energy across observed layers, equal mass per
     * observed layer. Decode preferred, prefill fallback. Not causal importance. */
    static Map<String,Double> ffnShares(Profile profile){
        List<Group> groups=hot(profile.groups(),"ffn");
        Map<String,Double> result=new HashMap<>();
        double total=0;for(Group g:groups)total+=g.mass();
        if(total>0)for(Group g:groups)result.put(g.key.layer()+":"+g.key.group(),g.mass()/total);
        return result;
    }
    static String report(Context c,IntelligenceModel model){
        StringBuilder out=new StringBuilder(model.label+"\n32-channel groups · relative observed energy, not necessity\n");
        for(String operation:OPERATIONS){
            Profile p=read(c,model,operation);
            out.append("\n").append(operation).append(" · ").append(p.requests()).append(" observed requests\n");
            if(p.groups().isEmpty()){out.append("No samples yet. Enable observer and run this operation.\n");continue;}
            out.append("Last predicted top-group overlap: ").append(p.hits()).append('/').append(p.predicted())
                .append(" · cache hint ").append(p.advised()/1024).append(" KiB · skipped tensors ").append(p.skipped()).append('\n');
            for(String family:new String[]{"ffn","attention","residual"}){
                List<Group> hot=hot(p.groups(),family);double total=0;for(Group g:hot)total+=g.mass();
                if(total<=0)continue;
                out.append(family).append(" · ").append(hot.get(0).key.phase()==1?"decode":"prefill").append('\n');
                for(int i=0;i<Math.min(family.equals("ffn")?12:4,hot.size());i++){
                    Group g=hot.get(i);
                    out.append(String.format(Locale.ROOT,"  L%d:%d  %.2f%% · %d rows\n",g.key.layer(),g.key.group(),100*g.mass()/total,g.samples()));
                }
            }
        }
        return out.toString();
    }
    static synchronized void clear(Context c,IntelligenceModel model){
        for(String op:OPERATIONS)file(c,model,op).delete();
    }
}
