package com.wds.sixstars.contextoverlay;

import android.content.Context;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/** Small, shared plain-text template loader. Asset names are app-owned constants;
 * callers pass maps, so rendering does not depend on reflected model members. */
final class TextTemplates {
    private static final ConcurrentHashMap<String,Template> CACHE=new ConcurrentHashMap<>();

    private TextTemplates(){}

    static Template load(Context context,String name)throws IOException{
        if(name==null||!name.matches("[a-z][a-z0-9_]*"))throw new IllegalArgumentException("Invalid template name.");
        Template cached=CACHE.get(name);
        if(cached!=null)return cached;
        try(Reader reader=new InputStreamReader(context.getAssets().open("templates/"+name+".mustache"),StandardCharsets.UTF_8)){
            Template compiled=compile(reader);
            Template existing=CACHE.putIfAbsent(name,compiled);
            return existing==null?compiled:existing;
        }catch(IOException e){
            throw new IOException("Could not load text template: "+name,e);
        }
    }

    /** Reader overload keeps template contracts testable on the host JVM. */
    static Template compile(Reader reader){
        return Mustache.compiler().escapeHTML(false).standardsMode(true).strictSections(true).emptyStringIsFalse(true).compile(reader);
    }
}
