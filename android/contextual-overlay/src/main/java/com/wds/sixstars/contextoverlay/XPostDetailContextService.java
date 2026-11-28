package com.wds.sixstars.contextoverlay;

import java.util.Locale;

/**
 * Identifies X's single-post/thread detail header from an accessibility node.
 * X exposes different view IDs on the feed and a dedicated post screen; this
 * helper is deliberately independent of resource ID availability.
 *
 * Only a visible, non-clickable heading near the TOP of X can signal a detail
 * screen. Ordinary "Post" buttons, composer actions and posts in the feed
 * must not accidentally change the candidate ranking.
 */
final class XPostDetailContextService {
    private XPostDetailContextService() { }

    /** Strong X resource IDs can identify a detail screen without a title node. */
    static boolean isDetailContainerId(String resourceId) {
        if (resourceId == null) return false;
        String id = resourceId.toLowerCase(Locale.ROOT);
        return id.contains("tweet_detail") || id.contains("post_detail")
                || id.contains("status_detail") || id.contains("tweet_permalink");
    }

    static boolean isReplyContextId(String resourceId) {
        if(resourceId==null)return false;
        String id=resourceId.toLowerCase(Locale.ROOT);
        return id.contains("reply_composer") || id.contains("reply_context") ||
                id.contains("reply_preview") || id.contains("reply_editor") ||
                id.contains("reply_edit_text") || id.contains("reply_to_tweet");
    }

    static boolean isDetailHeading(String text, String resourceId, String className,
            boolean clickable, boolean editable, int top, int bottom, int screenHeight) {
        if (text == null || screenHeight <= 0 || editable || clickable ||
                top < 0 || top > screenHeight / 4 || bottom <= top ||
                bottom - top > screenHeight / 8) return false;
        String label = text.trim().toLowerCase(Locale.ROOT);
        if (!("post".equals(label) || "post details".equals(label) ||
                "thread".equals(label) || "conversation".equals(label) ||
                "tweet".equals(label) || "ポスト".equals(label) ||
                "投稿".equals(label) || "スレッド".equals(label))) return false;
        String id = resourceId == null ? "" : resourceId.toLowerCase(Locale.ROOT);
        String cls = className == null ? "" : className.toLowerCase(Locale.ROOT);
        // A non-clickable TextView at the very top is a plausible toolbar title
        // even on X versions that don't expose a stable resource ID.
        return id.contains("title") || id.contains("toolbar") ||
                cls.contains("textview");
    }

    /** A post detail page need not expose the opened tweet as a clickable
     * conversation card. Collect its visible nodes first, then resolve the
     * body below the account header independently of language and replies. */
    record Surface(XTimelineCardRecords.Node node,int left,int top,int right,int bottom,
                   String cardOwner) {
        int width(){return right-left;}
        int height(){return bottom-top;}
        String value(){return node.text().isBlank()?node.description():node.text();}
        boolean fromDescription(){return node.text().isBlank();}
    }
    record OpenedBody(int order,String text,String language,boolean fromDescription){}

    private static String role(String id){
        if(id==null)return "";
        String lower=id.toLowerCase(Locale.ROOT);
        return lower.substring(lower.lastIndexOf('/')+1);
    }
    private static boolean openedBodyId(String id){
        String r=role(id);
        return isDetailContainerId(r)&&PostTextHeuristics.isPostTextId(r)
            &&!isReplyContextId(r)&&!XKnowledgeRecords.secondarySourceRole(r);
    }
    private static boolean chrome(String value,String id,String cls){
        String text=value.trim().toLowerCase(Locale.ROOT),r=role(id);
        if(PostTextHeuristics.isUiLabel(value,id,cls)||XAuthorNameHeuristics.looksLikeHandle(value)
            ||XAuthorNameHeuristics.looksCompositeHeader(value)||isReplyContextId(r))return true;
        if(r.contains("poll")||r.contains("vote")||r.contains("timestamp")||r.contains("created_at")
            ||r.contains("translation_label")||r.contains("translation_attribution")
            ||r.contains("translate_button")||r.contains("engagement")||r.contains("view_count"))return true;
        return text.equals("post details")||text.equals("thread")||text.equals("conversation")
            ||text.equals("tweet")||text.equals("ポスト")||text.equals("投稿")||text.equals("スレッド")
            ||text.equals("show original")||text.equals("show translation")||text.equals("translate")
            ||text.equals("post options")||text.equals("verified")||text.equals("verified business")
            ||text.equals("image")||text.equals("video")||text.equals("gif")
            ||text.startsWith("translated from ")||text.startsWith("translated by ")
            ||text.startsWith("profile image")||text.startsWith("profile photo")
            ||text.startsWith("view profile")||text.startsWith("explain this post")
            ||text.startsWith("relevant people")||text.startsWith("discover more")
            ||text.startsWith("post your reply")||text.startsWith("replying to ")
            ||text.matches("(?i)[·•]\\s*\\d+[smhdwy]")
            ||text.matches("(?i).*\\b\\d{1,2}:\\d{2}\\s*(?:am|pm)?\\s*[·•].*")
            ||text.matches("(?i)\\d[\\d,.\\s]*[kmb]?\\s+(?:views?|likes?|reposts?|replies|bookmarks?|votes?)");
    }
    private static boolean within(String path,String parent){
        return path.equals(parent)||path.startsWith(parent+"/");
    }

    static OpenedBody selectOpenedBody(java.util.List<Surface> visible,int width,int height,int headingBottom){
        if(width<=0||height<=0)return null;
        int firstReplyTop=height;
        java.util.List<String> excluded=new java.util.ArrayList<>();
        int headerBottom=-1;
        for(Surface surface:visible){
            XTimelineCardRecords.Node n=surface.node();
            String r=role(n.id()),cls=n.className().toLowerCase(Locale.ROOT);
            if(n.header()||XAuthorNameHeuristics.isNameId(r)||isReplyContextId(r)
                ||r.contains("poll")||r.contains("vote")||cls.endsWith(".button")
                ||cls.endsWith(".radiobutton")||cls.endsWith(".checkbox")){
                excluded.add(n.path());
            }
            if(!surface.cardOwner().isEmpty()&&n.card()&&surface.top()>headingBottom)
                firstReplyTop=Math.min(firstReplyTop,surface.top());
            String label=surface.value().trim();
            // The opened tweet normally has a stacked account header; a
            // separate @handle is especially useful when the text has no ID.
            if(n.primary()&&surface.cardOwner().isEmpty()&&surface.top()>=headingBottom&&
                surface.top()<firstReplyTop&&(XAuthorNameHeuristics.looksLikeHandle(label)
                    ||XAuthorNameHeuristics.looksCompositeHeader(label))){
                if(headerBottom<0)headerBottom=surface.bottom();
                else if(surface.top()<=headerBottom+width/8)
                    headerBottom=Math.max(headerBottom,surface.bottom());
            }
        }

        Surface best=null;int strength=0;
        for(Surface surface:visible){
            XTimelineCardRecords.Node n=surface.node();
            String value=surface.value(),id=role(n.id());
            String text=TextFingerprint.normalized(value);
            if(!n.primary()||text.isEmpty()||text.length()>1100||surface.width()<Math.max(20,width/30)
                ||surface.height()<8||surface.bottom()<=headingBottom||surface.top()<0||
                surface.top()>=height||chrome(text,id,n.className()))continue;
            boolean excludedPath=false;
            for(String path:excluded)if(within(n.path(),path)){excludedPath=true;break;}
            if(excludedPath)continue;

            boolean explicit=openedBodyId(id);
            // A verified body role/span outside reply cards is sufficient,
            // even if the author or the main text's left edge is clipped.
            boolean outsideCards=surface.cardOwner().isEmpty();
            boolean semantic=outsideCards&&(n.bodyRole()||n.bodySpan())&&surface.top()<firstReplyTop;
            boolean wide=surface.left()<=width/8&&surface.width()>=width/2;
            boolean afterHeader=headerBottom>=0&&surface.top()>=headerBottom-width/80;
            boolean direct=!surface.fromDescription()&&(n.className().endsWith("TextView")
                ||n.className().equals("android.view.View"));
            boolean fallback=outsideCards&&wide&&afterHeader&&surface.top()<firstReplyTop&&direct;

            // Description-only nodes can contain an entire post card. Do not
            // cover its header/actions: require a real body marker, or an
            // isolated full-width description with no readable descendants.
            if(surface.fromDescription()&&!explicit&&!semantic&&outsideCards&&wide&&afterHeader
                &&surface.top()<firstReplyTop&&surface.height()<height/2){
                boolean descendants=false;
                for(Surface other:visible)if(other!=surface&&within(other.node().path(),n.path())&&
                    !other.node().path().equals(n.path())&&!other.value().isBlank()){descendants=true;break;}
                fallback=!descendants;
            }
            int candidate=explicit?4:semantic?3:fallback?1:0;
            if(candidate==0)continue;
            if(best==null||candidate>strength||candidate==strength&&
                (surface.top()<best.top()||surface.top()==best.top()&&
                    !surface.fromDescription()&&best.fromDescription())){
                best=surface;strength=candidate;
            }
        }
        return best==null?null:new OpenedBody(best.node().order(),TextFingerprint.normalized(best.value()),
            PostLanguage.code(best.node().language()),best.fromDescription());
    }

    static int rank(int bodyTop, int bodyCenterY, int bodyScore,
            boolean knownBodyId, boolean fromDescription, boolean detailScreen,
            int viewportCenterY) {
        // On a detail page the opened post is usually the first large text
        // beneath the title, above replies and recommendations. Prioritize its
        // screen position without needing a stable resource ID.
        int score = bodyScore;
        if (knownBodyId) score += 150;
        if (fromDescription) score -= 25;
        if (detailScreen) {
            score += Math.max(0, 220 - Math.abs(bodyTop) / 3);
        } else {
            score += Math.max(0, 70 - Math.abs(bodyCenterY - viewportCenterY) / 15);
        }
        return score;
    }

}
