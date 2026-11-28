package com.wds.sixstars.contextoverlay.tree;

import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import com.wds.sixstars.contextoverlay.XVisibleTextReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Independent accessibility intake lane. No translation, disk writes, model work
 * or retained accessibility nodes on the UI callback. Positive home/list/body
 * markers required; unknown X layouts fail closed. Not a public-post verifier.
 */
public final class XTreeCapture implements AutoCloseable {
    private static final int MAX_NODES=450, MAX_CHILDREN=40, MAX_OBSERVATIONS=64;
    private static final long MAX_SCAN_MS=100, MIN_GAP_MS=20;
    private final TreeRuntime tree;
    private final Supplier<AccessibilityNodeInfo> root;
    private final BooleanSupplier overlayConsent;
    private final HandlerThread thread=new HandlerThread("RootsTreeCapture",Process.THREAD_PRIORITY_DEFAULT);
    private final Handler handler;
    private final AtomicBoolean scheduled=new AtomicBoolean(), dirty=new AtomicBoolean();
    private final AtomicLong surfaceEpoch=new AtomicLong();
    private volatile boolean closed;
    private volatile int width,height;
    public XTreeCapture(TreeRuntime tree,Supplier<AccessibilityNodeInfo> root,BooleanSupplier overlayConsent) {
        this.tree=tree;this.root=root;this.overlayConsent=overlayConsent;thread.start();handler=new Handler(thread.getLooper());
    }
    public void signal(int width,int height) {
        if(closed||!overlayConsent.getAsBoolean()||!tree.enabled())return;
        this.width=width;this.height=height;dirty.set(true);schedule(0);
    }
    private void schedule(long delay) {
        if(!closed&&scheduled.compareAndSet(false,true)) {
            if(!handler.postDelayed(this::run,delay))scheduled.set(false);
        }
    }
    public void invalidate(){surfaceEpoch.incrementAndGet();dirty.set(false);}
    private void run() {
        dirty.set(false);
        try {
            long surface=surfaceEpoch.get(), consent=tree.captureEpoch();
            if(closed||!tree.enabled()||!overlayConsent.getAsBoolean())return;
            Frame frame=read(root.get(),width,height);
            tree.reportScan(frame.eligible,frame.incomplete,frame.discovered,frame.home,frame.privateSurface);
            if(!closed&&surface==surfaceEpoch.get()&&frame.eligible&&overlayConsent.getAsBoolean())
                tree.accept(frame.observations,consent);
        }catch(RuntimeException ignored){tree.reportScan(false,true);}
        finally {scheduled.set(false);if(dirty.get())schedule(MIN_GAP_MS);}
    }
    @Override public void close(){closed=true;invalidate();handler.removeCallbacksAndMessages(null);thread.quitSafely();}

    private static final class Entry {
        final AccessibilityNodeInfo node; final boolean timeline;
        Entry(AccessibilityNodeInfo node,boolean timeline){this.node=node;this.timeline=timeline;}
    }
    private static final class Frame {
        final ArrayList<NativeTree.Observation> observations=new ArrayList<>();
        boolean eligible,incomplete,home,privateSurface;
        int discovered;
    }
    private static String low(CharSequence s){return s==null?"":s.toString().toLowerCase(Locale.ROOT);}
    static boolean homeMarker(String id,String label,boolean selected) {
        return (selected&&(label.equals("home")||id.endsWith(":id/home_tab")||id.endsWith(":id/home_button")||id.endsWith(":id/navigation_home")))
                || label.equals("home, selected") || label.equals("home tab, selected");
    }
    static boolean timelineMarker(String id) {
        return id.endsWith(":id/timeline")||id.contains("home_timeline")||id.contains("timeline_recycler")||id.contains("timeline_list");
    }
    static boolean bodyMarker(String id) {
        return id.contains("tweet_text")||id.contains("tweettext")||id.contains("tweet_body")||id.contains("tweetbody")||id.contains("status_text")||id.contains("post_text")||id.contains("post_body")||id.contains("text_tweet");
    }
    static boolean privateMarker(String id,String label,boolean editable,boolean password) {
        return editable||password||id.contains("direct_message")||id.contains("dm_")||id.contains("message_composer")||id.contains("conversation")||id.contains("protected_account")
                ||label.equals("messages, selected")||label.equals("protected account")||label.equals("protected posts");
    }
    private static void release(AccessibilityNodeInfo n){if(n!=null)n.recycle();}
    static Frame read(AccessibilityNodeInfo root,int width,int height) {
        Frame result=new Frame();
        if(root==null)return result;
        ArrayDeque<Entry> queue=new ArrayDeque<>();
        queue.add(new Entry(root,false));
        boolean home=false,privateSurface=false;
        int visited=0;
        long deadline=SystemClock.uptimeMillis()+MAX_SCAN_MS;
        long at=System.currentTimeMillis();
        try {
            if(width<=0||height<=0||!XVisibleTextReader.X_PACKAGE.equals(String.valueOf(root.getPackageName())))return result;
            while(!queue.isEmpty()&&visited<MAX_NODES&&SystemClock.uptimeMillis()<deadline) {
                Entry entry=queue.removeFirst();AccessibilityNodeInfo node=entry.node;visited++;
                try {
                    if(!XVisibleTextReader.X_PACKAGE.equals(String.valueOf(node.getPackageName())))continue;
                    String id=low(node.getViewIdResourceName()), desc=low(node.getContentDescription());
                    if(privateMarker(id,desc,node.isEditable(),node.isPassword())){privateSurface=true;break;}
                    home|=homeMarker(id,desc,node.isSelected())||homeMarker(id,low(node.getText()),node.isSelected());
                    boolean timeline=entry.timeline||timelineMarker(id);
                    if(timeline&&bodyMarker(id)&&node.getText()!=null&&result.observations.size()<MAX_OBSERVATIONS) {
                        Rect box=new Rect();node.getBoundsInScreen(box);
                        boolean intersects=box.bottom>0&&box.top<height;
                        boolean lookahead=box.bottom>=-height/2&&box.top<=height+height/2&&!intersects;
                        if(!box.isEmpty()&&box.right>0&&box.left<width&&((intersects&&node.isVisibleToUser())||lookahead)) {
                            String text=node.getText().toString().trim();
                            if(text.length()>=3&&text.length()<=4000)result.observations.add(new NativeTree.Observation(
                                    "x.android.home.fragment","",text,XVisibleTextReader.languageHint(text),at));
                        }
                    }
                    int count=node.getChildCount();
                    if(count>MAX_CHILDREN)result.incomplete=true;
                    for(int i=0;i<Math.min(count,MAX_CHILDREN);i++) {
                        if(visited+queue.size()>=MAX_NODES||SystemClock.uptimeMillis()>=deadline){result.incomplete=true;break;}
                        AccessibilityNodeInfo child=node.getChild(i);if(child!=null)queue.add(new Entry(child,timeline));
                    }
                }finally{release(node);}
            }
            result.discovered=result.observations.size();
            result.home=home;result.privateSurface=privateSurface;
            result.incomplete|=!queue.isEmpty()||result.observations.size()>=MAX_OBSERVATIONS;
            // A scan whose safety context was truncated cannot establish a
            // trusted route. Prefer a missed observation over a private capture.
            result.eligible=home&&!privateSurface&&!result.incomplete;
            if(!result.eligible)result.observations.clear();
            return result;
        }finally{while(!queue.isEmpty())release(queue.removeFirst().node);}
    }
}
