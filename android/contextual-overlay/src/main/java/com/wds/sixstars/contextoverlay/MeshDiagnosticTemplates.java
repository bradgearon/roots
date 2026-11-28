package com.wds.sixstars.contextoverlay;

import android.content.Context;
import com.samskivert.mustache.Template;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Presentation of the typed, credential-free report. Never accepts invitations,
 * peer JSON, knowledge text, or Throwable messages as an intelligence request. */
final class MeshDiagnosticTemplates {
    static final int MAX_REQUEST_CHARS=2900;
    private static final Pattern INVITATION=Pattern.compile("(?i)roots://\\S+");
    private static final Pattern SECRET=Pattern.compile("(?i)\\b(?:token|authorization)\\s*[:=]\\s*[^\\s;,]+");
    private static final Pattern HEX_SECRET=Pattern.compile("(?i)\\b[0-9a-f]{64}\\b");
    private static final Pattern CONTROL=Pattern.compile("[\\p{Cntrl}]+");

    private MeshDiagnosticTemplates(){}

    static String report(Context context,MeshConnectionDiagnostics.Report report)throws IOException{
        return report(TextTemplates.load(context,"mesh_connection_report"),report);
    }

    static String analysis(Context context,MeshConnectionDiagnostics.Report report)throws IOException{
        return analysis(TextTemplates.load(context,"mesh_connection_analysis"),report);
    }

    static String report(Template template,MeshConnectionDiagnostics.Report report){
        List<Map<String,Object>> peers=new ArrayList<>();
        int index=0;
        for(MeshConnectionDiagnostics.PeerReport peer:report.peers()){
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("number",++index);
            data.put("endpoint",clean(peer.endpoint(),100));
            data.put("identity",clean(peer.identity(),80));
            data.put("checks",viewChecks(peer.checks()));
            peers.add(data);
        }
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("checked_at",Instant.ofEpochMilli(report.checkedAtMillis()).toString());
        data.put("peer_count",peers.size());
        data.put("local_checks",viewChecks(report.localChecks()));
        data.put("peers",peers);
        return template.execute(data).trim();
    }

    static String analysis(Template template,MeshConnectionDiagnostics.Report report){
        // Shorten every peer fairly, never cut off the tail of the final prompt.
        // All FAIL/WARN stage names survive; pass/skip counts survive even at
        // the smallest budget. The detailed report remains visible in the UI.
        for(int detailLimit:new int[]{180,120,80,40,0}){
            for(boolean includePassedStages:new boolean[]{true,false}){
                Map<String,Object> data=new LinkedHashMap<>();
                data.put("checked_at",Instant.ofEpochMilli(report.checkedAtMillis()).toString());
                List<Map<String,Object>> scopes=new ArrayList<>();
                scopes.add(evidence("This device",report.localChecks(),detailLimit,includePassedStages));
                int index=0;
                for(MeshConnectionDiagnostics.PeerReport peer:report.peers()){
                    String name="Peer "+(++index)+" "+clean(peer.endpoint(),60);
                    scopes.add(evidence(name,peer.checks(),detailLimit,includePassedStages));
                }
                data.put("scopes",scopes);
                String request=template.execute(data).trim();
                if(request.length()<=MAX_REQUEST_CHARS&&request.indexOf('<')<0)return request;
            }
        }
        // Do not silently omit a failing peer to satisfy the model's limit.
        throw new IllegalArgumentException("Connection report is too large to analyze. Test fewer peer invitations.");
    }

    private static List<Map<String,Object>> viewChecks(List<MeshConnectionDiagnostics.Check> checks){
        List<Map<String,Object>> result=new ArrayList<>();
        for(MeshConnectionDiagnostics.Check check:checks){
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("stage",clean(check.stage(),80));
            data.put("state",clean(check.state(),12));
            data.put("detail",clean(check.detail(),600));
            data.put("next_step",clean(check.nextStep(),400));
            data.put("elapsed_ms",Math.max(0,check.elapsedMs()));
            result.add(data);
        }
        return result;
    }

    private static Map<String,Object> evidence(String name,List<MeshConnectionDiagnostics.Check> checks,int detailLimit,boolean includePassedStages){
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("name",name);
        List<String> passed=new ArrayList<>();
        List<Map<String,Object>> findings=new ArrayList<>();
        int skipped=0;
        for(MeshConnectionDiagnostics.Check check:checks){
            boolean inventoryPassed="PASS".equals(check.state())&&"Inventory".equals(check.stage());
            if("PASS".equals(check.state())){
                passed.add(clean(check.stage(),40));
                // Include measured local facts (addresses, listener/policy
                // readiness) when there is room. Large multi-peer reports
                // compress these before omitting any failure or item count.
                boolean localDetail=name.equals("This device")&&includePassedStages&&detailLimit>0;
                if(!inventoryPassed&&!localDetail)continue;
            }
            boolean actionableSkip="SKIP".equals(check.state())&&check.nextStep()!=null&&!check.nextStep().trim().isEmpty();
            if("SKIP".equals(check.state())){
                skipped++;
                if(!actionableSkip)continue;
            }
            Map<String,Object> finding=new LinkedHashMap<>();
            finding.put("stage",clean(check.stage(),40));
            finding.put("state",clean(check.state(),12));
            // Successful empty inventory is an actionable result, not a
            // transport failure. Keep its measured count in every budget.
            String detail=clean(check.detail(),inventoryPassed||actionableSkip?Math.max(100,detailLimit):detailLimit);
            String action=clean(check.nextStep(),actionableSkip?Math.max(60,detailLimit/2):detailLimit/2);
            finding.put("detail",detail);
            finding.put("action",action);
            findings.add(finding);
        }
        result.put("passed",passed.size());
        result.put("skipped",skipped);
        result.put("passed_stages",includePassedStages?String.join(", ",passed):"");
        result.put("findings",findings);
        return result;
    }

    private static String clean(String value,int limit){
        if(value==null||limit<=0)return "";
        // Defense in depth: the report producers already emit fixed, safe
        // diagnostics. Redact before truncation so no token prefix survives.
        String safe=INVITATION.matcher(value).replaceAll("[invitation redacted]");
        safe=SECRET.matcher(safe).replaceAll("[credential redacted]");
        safe=HEX_SECRET.matcher(safe).replaceAll("[credential redacted]");
        safe=CONTROL.matcher(safe).replaceAll(" ").replace('<','(').replace('>',')').trim();
        return safe.length()<=limit?safe:safe.substring(0,Math.max(0,limit-1))+"…";
    }
}
