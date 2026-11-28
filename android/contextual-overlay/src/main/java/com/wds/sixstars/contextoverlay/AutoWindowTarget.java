package com.wds.sixstars.contextoverlay;

import android.graphics.Rect;

/** Verified topmost X TYPE_APPLICATION window and its measured text origin. */
final class AutoWindowTarget {
    final int windowId;
    final Rect screenBounds;
    final XOverlayOrigin.Offset origin;

    AutoWindowTarget(int windowId, Rect bounds) {
        this(windowId,bounds,null);
    }

    AutoWindowTarget(int windowId,Rect bounds,XOverlayOrigin.Offset origin){
        this.windowId=windowId;
        this.screenBounds=new Rect(bounds);
        this.origin=origin;
    }

    boolean isValid(){
        return windowId>=0&&screenBounds.width()>0&&screenBounds.height()>0;
    }

    // The visible bounds can be clipped by insets. Size the buffer to
    // include the visible window-local extent without guessing a fixed inset.
    int bufferWidth(){return origin==null?screenBounds.width():Math.max(screenBounds.width(),screenBounds.right-origin.x());}
    int bufferHeight(){return origin==null?screenBounds.height():Math.max(screenBounds.height(),screenBounds.bottom-origin.y());}

    boolean hasVerifiedOrigin(){
        return origin!=null&&isValid()&&Math.abs((long)screenBounds.left-origin.x())<screenBounds.width()
            &&Math.abs((long)screenBounds.top-origin.y())<screenBounds.height()
            &&bufferWidth()>0&&bufferHeight()>0;
    }

    boolean sameSurface(AutoWindowTarget other){
        return other!=null&&windowId==other.windowId&&hasVerifiedOrigin()==other.hasVerifiedOrigin()
            &&bufferWidth()==other.bufferWidth()&&bufferHeight()==other.bufferHeight();
    }

    boolean samePosition(AutoWindowTarget other){
        return sameSurface(other)&&screenBounds.equals(other.screenBounds)
            &&java.util.Objects.equals(origin,other.origin);
    }
}
