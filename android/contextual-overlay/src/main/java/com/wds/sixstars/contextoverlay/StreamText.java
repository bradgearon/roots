package com.wds.sixstars.contextoverlay;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.TextView;

final class StreamText {
    private StreamText(){}
    static String utf8Prefix(byte[] bytes){
        java.nio.charset.CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        CharBuffer chars=CharBuffer.allocate(bytes.length);
        decoder.decode(ByteBuffer.wrap(bytes),chars,false);chars.flip();
        return chars.toString();
    }
    static String translationPreview(byte[] bytes){
        String text=utf8Prefix(bytes),marker="<|plamo:";
        int at=text.indexOf(marker);if(at>=0)return text.substring(0,at);
        for(int n=Math.min(marker.length()-1,text.length());n>0;n--)
            if(text.endsWith(marker.substring(0,n)))return text.substring(0,text.length()-n);
        return text;
    }
    static String functionPreview(byte[] bytes){
        String prefix=utf8Prefix(bytes),marker="<escape>";
        int start=prefix.indexOf(marker);if(start<0)return "";
        String value=prefix.substring(start+marker.length());
        int end=value.indexOf(marker);
        if(end>=0)value=value.substring(0,end);
        else{int partial=value.lastIndexOf('<');if(partial>=0)value=value.substring(0,partial);}
        return value.replace(';','\n').replace("|","  ·  ").trim();
    }
    static void reveal(TextView view,String prefix){
        if(prefix==null||prefix.isEmpty())return;
        view.setText(accent(prefix));
    }
    static CharSequence accent(String prefix){
        SpannableString text=new SpannableString(prefix);
        int end=prefix.length(),start=end;
        while(start>0&&end-start<18&&!Character.isWhitespace(prefix.charAt(start-1)))start--;
        if(start>0&&start<end&&Character.isLowSurrogate(prefix.charAt(start)))start--;
        if(start<end)text.setSpan(new ForegroundColorSpan(0xFF7BC4BB),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }
}
