package com.wds.sixstars.contextoverlay;

/** Verify the translation between Android's screen and window coordinates.
 * The visible window rectangle is NOT necessarily the surface's origin.
 * Reject scaled, clipped or contradictory coordinate pairs rather than
 * manufacturing a correction from the status bar or ad height. */
final class XOverlayOrigin {
    record Offset(int x,int y){}
    private Offset measured;
    private boolean invalid;

    void observe(int sl,int st,int sr,int sb,int wl,int wt,int wr,int wb){
        if(sr<=sl||sb<=st||wr<=wl||wb<=wt||sr-sl!=wr-wl||sb-st!=wb-wt){
            invalid=true;return;
        }
        Offset next=new Offset(sl-wl,st-wt);
        if(measured==null)measured=next;
        else if(!measured.equals(next))invalid=true;
    }
    void missing(){invalid=true;}
    Offset result(){return invalid?null:measured;}
}
