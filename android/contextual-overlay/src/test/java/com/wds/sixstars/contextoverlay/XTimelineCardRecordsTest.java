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
    @Test public void japanesePollQuestionWinsEvenWithClippedHandle(){
        String question="ふと気になったのでアンケート。ハンドルネームの部分に「@〇〇」みたいに「主に何をしてる人なのか」というのを書いてあったほうがいいですか？";
        List<XTimelineCardRecords.Node> nodes=new ArrayList<>();
        nodes.add(node(1,"0/1","","",true,false,false,List.of()));
        nodes.add(node(2,"0/1/1","","",false,true,false,List.of()));
        nodes.add(node(3,"0/1/1/0","メイド","",false,false,false,List.of()));
        nodes.add(node(4,"0/1/1/1","@rebe…","",false,false,false,List.of()));
        nodes.add(node(5,"0/1/4",question,"",false,false,true,List.of()));
        nodes.add(new XTimelineCardRecords.Node(6,"0/1/5","poll","android.view.View","","",false,false,false,false,true,List.of(),""));
        nodes.add(node(7,"0/1/5/0","できればあったほうがいい","",false,false,true,List.of()));
        nodes.add(node(8,"0/1/5/1","あったほうがいい","",false,false,true,List.of()));
        nodes.add(node(9,"0/1/5/2","特に気にしてないのでどちらでも","",false,false,true,List.of()));
        var result=XTimelineCardRecords.assemble(nodes);
        assertTrue(result.posts().isEmpty()); // no fabricated identity
        assertEquals(question,result.primaryBodies().get("0/1").text());
        assertEquals("ja",PostLanguage.resolve(question,"en","ja",.98,true));
    }

    @Test public void genericComposeQuestionSpanWorksWithoutVisibleHeader(){
        var card=node(1,"0/1","","",true,false,false,List.of());
        var body=new XTimelineCardRecords.Node(2,"0/1/4","","android.view.View",
            "こんにちは。今日はどうですか？","",false,false,false,true,true,List.of(),"");
        var result=XTimelineCardRecords.assemble(List.of(card,body));
        assertEquals(2,result.primaryBodies().get("0/1").order());
        assertTrue(result.posts().isEmpty());
    }

    @Test public void selectsOuterAndNestedQuotesWithoutMergingTheirBodies(){
        String outer="気をつけます!! 忘れずつけます!!";
        String quote="生成AIテキストにも自動でつかないので注意が必要ですよ←";
        String inner="Crap, I haven't mastered how to add AI-generated labels.";
        List<XTimelineCardRecords.Node> nodes=new ArrayList<>();
        nodes.add(node(1,"0/1","","",true,false,false,List.of()));
        nodes.add(node(2,"0/1/1","","",false,true,false,List.of()));
        nodes.add(node(3,"0/1/1/0","Shachi","",false,false,false,List.of()));
        nodes.add(node(4,"0/1/1/1","@shachi","",false,false,false,List.of()));
        nodes.add(node(5,"0/1/4",outer,"",false,false,true,List.of()));
        nodes.add(node(6,"0/1/5/0","","",false,true,false,List.of()));
        nodes.add(node(7,"0/1/5/0/0","AkiWeb","",false,false,false,List.of()));
        nodes.add(node(8,"0/1/5/1",quote,"",false,false,true,List.of()));
        nodes.add(node(9,"0/1/5/2/0","","",false,true,false,List.of()));
        nodes.add(node(10,"0/1/5/2/0/0","Shachi","",false,false,false,List.of()));
        nodes.add(node(11,"0/1/5/2/1",inner,"",false,false,true,List.of()));
        var result=XTimelineCardRecords.assemble(nodes);
        assertEquals(outer,result.primaryBodies().get("0/1").text());
        assertEquals(quote,result.quotedBodies().get("0/1/5/1").text());
        assertEquals(inner,result.quotedBodies().get("0/1/5/2/1").text());
        assertEquals(1,result.posts().size());
        assertEquals(outer,result.posts().get(0).body());
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
