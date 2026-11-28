package com.wds.sixstars.contextoverlay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;

/** The independent desktop intelligence contract. No translation endpoint or
 * model substitution is accepted by this client. */
final class DesktopIntelligenceProtocol {
    static final int PORT=8221,MAX_PROMPT=3000,MAX_TOKENS=384,MAX_RESPONSE=96*1024;
    static final String HEALTH="/api/intelligence/v1/health",GENERATE="/api/intelligence/v1/generate";
    private DesktopIntelligenceProtocol(){}
    static final class Failure extends IOException { Failure(String message){super(message);} }

    static String baseUrl(String value){
        if(DesktopWhirlwind.remote(value))return DesktopWhirlwind.route(value).canonical();
        try{
            URI uri=new URI(value==null?"":value.trim());
            String host=uri.getHost();
            if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||host.isEmpty()||
                uri.getPort()!=PORT||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||
                !(uri.getRawPath()==null||uri.getRawPath().isEmpty()||uri.getRawPath().equals("/")))
                throw new IllegalArgumentException();
            String bare=host.replace("[","").replace("]","").toLowerCase(Locale.ROOT);
            if(bare.equals("localhost")||bare.equals("::1")||bare.equals("::")||bare.startsWith("127.")||bare.equals("0.0.0.0"))
                throw new IllegalArgumentException();
            if(bare.contains(":")||bare.matches("[0-9.]+")){
                java.net.InetAddress numeric=java.net.InetAddress.getByName(bare);
                if(numeric.isLoopbackAddress()||numeric.isAnyLocalAddress()||numeric.isMulticastAddress())throw new IllegalArgumentException();
            }
            return new URI("https",null,host.toLowerCase(Locale.ROOT),PORT,null,null,null).toASCIIString();
        }catch(Exception error){throw new IllegalArgumentException("Enter the PC's HTTPS address on port 8221 or its roots+iroh:// address.");}
    }
    static String pairedKey(String value){
        String normalized=value==null?"":value.trim().toLowerCase(Locale.ROOT);
        if(!normalized.matches("[0-9a-f]{64}:[0-9a-f]{64}"))
            throw new IllegalArgumentException("Paste the intelligence connection key from get-intelligence-key.cmd.");
        return normalized;
    }
    static JSONObject request(IntelligenceModel model,String prompt)throws Exception{
        if(model==null||prompt==null||prompt.trim().isEmpty()||prompt.length()>MAX_PROMPT||prompt.indexOf('<')>=0)
            throw new Failure("Enter plain text (up to 3000 characters).");
        return new JSONObject().put("version",1).put("model",model.name()).put("modelSha",model.sha)
            .put("prompt",prompt).put("maxTokens",MAX_TOKENS);
    }
    static void identity(JSONObject data,IntelligenceModel model)throws Failure{
        if(data.optInt("version",0)!=1)
            throw new Failure("Desktop intelligence returned an unsupported protocol version. Update both Roots installations.");
        if(!model.name().equals(data.optString("model"))||!model.sha.equals(data.optString("modelSha")))
            throw new Failure("Desktop intelligence model does not match the selected model. Select the model running on the desktop; the default host uses Qwen3.5 0.8B.");
    }
    static void health(JSONObject data,IntelligenceModel model)throws Failure{
        if(!data.optBoolean("ok",false)||!"intelligence".equals(data.optString("capability")))
            throw new Failure("Port 8221 did not return the Roots intelligence health contract. Start launch-intelligence.cmd on the PC.");
        identity(data,model);
    }
    static String answer(JSONObject data,IntelligenceModel model)throws Failure{
        identity(data,model);
        Object value=data.opt("text");
        if(!(value instanceof String)||((String)value).trim().isEmpty()||((String)value).length()>16000)
            throw new Failure("Desktop intelligence returned empty or invalid output.");
        return ((String)value).trim();
    }
    static Failure httpFailure(int code){
        String message;
        switch(code){
            case 401:message="Desktop intelligence rejected the key. Pair using get-intelligence-key.cmd.";break;
            case 400:message="Desktop intelligence rejected the request format or size. Update both Roots installations.";break;
            case 409:message="The selected intelligence model differs from the desktop model. The default host uses Qwen3.5 0.8B.";break;
            case 429:message="Desktop intelligence is busy. Try again after the current request finishes.";break;
            case 503:message="Desktop intelligence is unavailable. Check the launch-intelligence.cmd console.";break;
            case 504:message="Desktop intelligence exceeded its generation deadline. Check the desktop model output.";break;
            default:message="Desktop intelligence returned HTTP "+code+". Check the intelligence listener on port 8221.";
        }
        return new Failure(message);
    }
    static String readLimited(InputStream input)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[4096];int n;
        while((n=input.read(bytes))!=-1){
            if(out.size()+n>MAX_RESPONSE)throw new Failure("Desktop intelligence response exceeds the allowed size.");
            out.write(bytes,0,n);
        }
        return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
}
