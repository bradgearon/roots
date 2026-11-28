package com.wds.sixstars.contextoverlay;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class XTimelineCardRecordsTest {
    private static XTimelineCardRecords.Node node(int order,String path,String value,String desc,
            boolean card,boolean header,boolean body,List<String> links){
        return new XTimelineCardRecords.Node(order,path,header?"timeline_post":"",
            value.isEmpty()?"android.view.View":"android.widget.TextView",
            value,desc,card,header,false,body,true,links,"");
    }
    private static void addCard(List<XTimelineCardRecords.Node> nodes,String path,String name,String handle,String count){
        int n=nodes.size()+1;
        nodes.add(node(n++,path,"","",true,false,false,List.of()));
        nodes.add(node(n++,path+"/1","","",false,true,false,List.of()));
        nodes.add(node(n++,path+"/1/0",name,"",false,false,false,List.of()));
        nodes.add(node(n++,path+"/1/1",handle,"",false,false,false,List.of()));
        nodes.add(node(n++,path+"/1/2","· 3m","",false,false,false,List.of()));
        nodes.add(node(n++,path+"/4","Amen","",false,false,true,List.of()));
        nodes.add(node(n++,path+"/10/0","","Like",false,false,false,List.of()));
        nodes.add(node(n++,path+"/10/1/0",count,"",false,false,false,List.of()));
    }
    @Test public void joinsSameBodyToDifferentAuthorsUsingAncestry(){
        List<XTimelineCardRecords.Node> nodes=new ArrayList<>();
        addCard(nodes,"0/1","Alice","@alice","24");
        addCard(nodes,"0/10","Bob","@bob","7");
        var result=XTimelineCardRecords.assemble(nodes);
        assertEquals(2,result.posts().size());
        var a=result.posts().get(0);var b=result.posts().get(1);
        assertNotEquals(a.key(),b.key());
        assertTrue(a.fields().contains(new XTimelineCardRecords.Field("author","Alice")));
        assertTrue(a.fields().contains(new XTimelineCardRecords.Field("like-count","24")));
        assertFalse(a.fields().contains(new XTimelineCardRecords.Field("like-count","7")));
        assertTrue(b.fields().contains(new XTimelineCardRecords.Field("handle","@bob")));
        assertTrue(b.fields().contains(new XTimelineCardRecords.Field("like-count","7")));
    }
    @Test public void partialCardNeverCreatesAnAuthorOnlyRecord(){
        List<XTimelineCardRecords.Node> nodes=new ArrayList<>();
        addCard(nodes,"0/1","Alice","@alice","24");
        nodes.removeIf(n->n.text().equals("@alice"));
        var result=XTimelineCardRecords.assemble(nodes);
        assertTrue(result.posts().isEmpty());
        assertTrue(result.cardByOrder().containsValue("0/1"));
    }
    @Test public void quotedPostDoesNotSupplyParentPermalink(){
        List<XTimelineCardRecords.Node> nodes=new ArrayList<>();
        addCard(nodes,"0/1","Alice","@alice","24");
        nodes.add(node(90,"0/1/5/0","","Quote",false,false,false,List.of()));
        nodes.add(node(91,"0/1/5/1","quoted text","",false,false,true,
            List.of("https://x.com/other/status/1234567890123456789")));
        var post=XTimelineCardRecords.assemble(nodes).posts().get(0);
        assertTrue(post.fields().stream().anyMatch(f->f.role().equals("context")&&f.value().equals("quoted text")));
        assertFalse(post.fields().stream().anyMatch(f->f.role().equals("link")));
    }
}
