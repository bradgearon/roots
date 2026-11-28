package com.wds.sixstars.contextoverlay;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Read-only metadata requests. Never invokes a node action or opens a post.
 * Compose semantics IDs are UI-local integers, not remote status IDs. */
final class XPostMetadata {
    static final String SEMANTICS_ID="androidx.compose.ui.semantics.id";
    static final String TEST_TAG="androidx.compose.ui.semantics.testTag";
    private XPostMetadata(){}

    static boolean conversationCard(AccessibilityNodeInfo node){
        if(!node.isClickable()||node.isScrollable()||node.getChildCount()<2)return false;
        for(AccessibilityNodeInfo.AccessibilityAction action:node.getActionList()){
            CharSequence label=action.getLabel();
            if(action.getId()==AccessibilityNodeInfo.ACTION_CLICK&&label!=null&&
                "View full conversation".equalsIgnoreCase(label.toString().trim()))return true;
        }
        return false;
    }
    static boolean timelineHeader(String id){
        if(id==null)return false;
        return "timeline_post".equals(id.substring(id.lastIndexOf('/')+1));
    }
    static String keyRole(String key){
        if(key==null)return "";
        String role=key.toLowerCase(Locale.ROOT);
        return role.substring(Math.max(role.lastIndexOf('.'),role.lastIndexOf(':'))+1).replace("_","");
    }
    static boolean remoteKey(String key){
        String role=keyRole(key);
        return role.matches("(tweet|post|status)(id|idstr|url|uri|link|permalink)|permalink");
    }
    /** The caller must supply structural post metadata, not body text. */
    static String linkFromTag(Object value){
        if(!(value instanceof CharSequence))return "";
        String tag=value.toString().trim();
        String url=XPostLink.canonical(tag);
        if(!url.isEmpty())return url;
        java.util.regex.Matcher match=java.util.regex.Pattern.compile(
            "(?i)(?:tweet|post|status)[_-]?id[:=_-]([1-9][0-9]{14,19})").matcher(tag);
        return match.matches()?XPostLink.fromRemoteId(match.group(1)):"";
    }

    /** A small per-scan budget prevents metadata IPC on every text node. */
    static final class Probe {
        private int remaining;
        Probe(int limit){remaining=limit;}
        Bundle read(AccessibilityNodeInfo node,boolean diagnostic){
            Bundle result=new Bundle();
            if(remaining<=0)return result;
            List<String> keys=node.getAvailableExtraData();
            if(keys==null)return result;
            for(String key:keys){
                if(remaining<=0)break;
                if(!remoteKey(key)&&!TEST_TAG.equals(key)&&!(diagnostic&&SEMANTICS_ID.equals(key)))continue;
                remaining--;
                // Refresh a separate node snapshot. The live reader's geometry
                // and text must not change partway through grouping.
                AccessibilityNodeInfo copy=AccessibilityNodeInfo.obtain(node);
                try{
                    Rect before=new Rect(),after=new Rect();
                    node.getBoundsInScreen(before);
                    if(!copy.refreshWithExtraData(key,new Bundle()))continue;
                    copy.getBoundsInScreen(after);
                    if(!before.equals(after)||node.getWindowId()!=copy.getWindowId()||
                        !Objects.equals(node.getViewIdResourceName(),copy.getViewIdResourceName())||
                        !Objects.equals(string(node.getText()),string(copy.getText()))||
                        !Objects.equals(string(node.getContentDescription()),string(copy.getContentDescription())))continue;
                    Object value=copy.getExtras().get(key);
                    if(value instanceof CharSequence)result.putCharSequence(key,value.toString());
                    else if(value instanceof Long)result.putLong(key,(Long)value);
                    else if(value instanceof Integer)result.putInt(key,(Integer)value);
                    else if(value instanceof android.net.Uri)result.putString(key,value.toString());
                }catch(RuntimeException ignored){/* Optional app-provided metadata. */}
                finally{copy.recycle();}
            }
            return result;
        }
    }
    private static String string(CharSequence value){return value==null?null:value.toString();}
}
