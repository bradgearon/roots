package com.wds.sixstars.contextoverlay;

import java.util.ArrayList;
import java.util.List;

/** Preserve raw readable text without language, relevance or length-minimum filters.
 * Split long values to fit the native UTF-8 bound without breaking surrogate pairs. */
final class KnowledgeText {
    static List<String> fragments(CharSequence input){
        List<String> result=new ArrayList<>();
        if(input==null)return result;
        String text=input.toString().replace("\u0000","").trim();
        for(int start=0;start<text.length();){
            int end=Math.min(start+4000,text.length());
            if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1))
                    && Character.isLowSurrogate(text.charAt(end)))end--;
            String part=text.substring(start,end);
            if(!part.trim().isEmpty())result.add(part);
            start=end;
        }
        return result;
    }
}
