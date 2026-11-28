package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.*;

/** Dedicated post/thread detection must not change ordinary timeline scoring. */
public final class XPostDetailContextServiceTest {
    @Test public void detectsDedicatedDetailViewResources() {
        assertTrue(XPostDetailContextService.isDetailContainerId(
                "com.twitter.android:id/tweet_detail_container"));
        assertTrue(XPostDetailContextService.isDetailContainerId(
                "com.twitter.android:id/post_detail"));
        assertFalse(XPostDetailContextService.isDetailContainerId(
                "com.twitter.android:id/timeline_item"));
        assertFalse(XPostDetailContextService.isDetailContainerId(null));
    }

    @Test public void detectsPostDetailToolbarWithAndWithoutStableId() {
        assertTrue(XPostDetailContextService.isDetailHeading(
                "Post", "com.twitter.android:id/toolbar_title",
                "android.widget.TextView", false, false,
                70, 120, 1600));
        assertTrue(XPostDetailContextService.isDetailHeading(
                "Thread", null, "android.widget.TextView",
                false, false, 70, 120, 1600));
        assertTrue(XPostDetailContextService.isDetailHeading(
                "ポスト", null, "android.widget.TextView",
                false, false, 70, 120, 1600));
    }

    @Test public void ignoresPostActionsAndLabelsOutsideTheToolbar() {
        assertFalse(XPostDetailContextService.isDetailHeading(
                "Post", null, "android.widget.Button",
                true, false, 70, 120, 1600));
        assertFalse(XPostDetailContextService.isDetailHeading(
                "Post", null, "android.widget.TextView",
                false, false, 900, 950, 1600));
        assertFalse(XPostDetailContextService.isDetailHeading(
                "Reply", "com.twitter.android:id/toolbar_title",
                "android.widget.TextView", false, false, 70, 120, 1600));
    }

    private static XPostDetailContextService.Surface surface(int order,String path,String id,String text,
            String description,boolean card,boolean body,boolean span,int left,int top,int right,int bottom,String owner){
        var node=new XTimelineCardRecords.Node(order,path,id,"android.widget.TextView",text,description,
            card,false,body,span,true,java.util.List.of(),"");
        return new XPostDetailContextService.Surface(node,left,top,right,bottom,owner);
    }

    @Test public void openedTweetWithoutLanguageAttributeWinsOverReply(){
        var reply=surface(1,"0/2","","返事です","",false,true,true,150,900,1060,960,"0/1");
        var card=surface(2,"0/1","","","",true,false,false,0,850,1080,1200,"0/1");
        var handle=surface(3,"0/0/1","","@example","",false,false,false,155,190,480,230,"");
        var body=surface(4,"0/0/2","","ありがとう","",false,false,false,30,280,1050,350,"");
        var chosen=XPostDetailContextService.selectOpenedBody(java.util.List.of(reply,card,body,handle),1080,2400,140);
        assertNotNull(chosen);
        assertEquals(4,chosen.order());
        assertEquals("ありがとう",chosen.text());
        assertEquals("",chosen.language());
    }

    @Test public void acceptsDescriptionOnlyExplicitDetailBody(){
        var body=surface(1,"0/0/2","tweet_detail_text","","今日もよろしくお願いします",false,true,false,25,300,1040,480,"");
        var chosen=XPostDetailContextService.selectOpenedBody(java.util.List.of(body),1080,2400,140);
        assertNotNull(chosen);
        assertTrue(chosen.fromDescription());
        assertEquals(1,chosen.order());
    }

    @Test public void detailRankingPrefersOpenedPostAboveReplies() {
        int primary = XPostDetailContextService.rank(
                120, 250, 95, false, false, true, 850);
        int reply = XPostDetailContextService.rank(
                980, 1080, 100, false, false, true, 850);
        assertTrue(primary > reply);
        int explicitBody = XPostDetailContextService.rank(
                250, 350, 120, true, false, true, 850);
        assertTrue(explicitBody > reply);
    }
}
