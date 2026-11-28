package com.wds.sixstars.contextoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the user-enabled LAN listener independently of any activity.
 * Network callbacks repair the socket; successful listeners block in accept()
 * without a polling loop. Only a failed start uses bounded retry backoff. */
public final class MeshServingService extends Service {
    private static final String PREFS="roots_mesh_serving", ENABLED="enabled";
    private static final String CHANNEL="roots_mesh_serving", STOP="com.wds.roots.mesh.STOP_SERVING";
    private static final int NOTIFICATION=8233;
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"RootsMeshServing");t.setDaemon(true);return t;
    });
    private static final CopyOnWriteArrayList<Runnable> observers=new CopyOnWriteArrayList<>();
    private static volatile MeshServingService current;
    private static volatile String state="Waiting to restore listener";
    private final Handler main=new Handler(Looper.getMainLooper());
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback callback;
    private boolean destroyed,working,pending;
    private long retryDelay=1000;
    private final Runnable reconcileTask=this::reconcile;

    private static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    static boolean enabled(Context c){return prefs(c).getBoolean(ENABLED,false);}
    static String status(Context c){return enabled(c)?state:"Sharing off";}
    static void addObserver(Runnable observer){observers.addIfAbsent(observer);}
    static void removeObserver(Runnable observer){observers.remove(observer);}
    private static void publish(String next){
        if(next.equals(state))return;
        state=next;
        for(Runnable observer:observers)try{observer.run();}catch(RuntimeException ignored){}
    }
    static void enable(Context c){
        if(!prefs(c).edit().putBoolean(ENABLED,true).commit())
            throw new IllegalStateException("Could not save the serving setting.");
        publish("Starting listener");
        restore(c);
    }
    /** Only a previously enabled source can be restored. Call from a visible
     * activity or a system-authorized boot/package replacement receiver. */
    static void restore(Context c){
        Context app=c.getApplicationContext();
        if(!enabled(app))return;
        MeshServingService service=current;
        if(service!=null){service.signal();return;}
        try{app.startForegroundService(new Intent(app,MeshServingService.class));}
        catch(RuntimeException error){publish("Automatic start unavailable: "+error.getClass().getSimpleName()+". Reopen Roots.");}
    }
    static void disable(Context c){
        Context app=c.getApplicationContext();
        if(!prefs(app).edit().putBoolean(ENABLED,false).commit())
            throw new IllegalStateException("Could not save the serving setting.");
        publish("Sharing off");
        app.stopService(new Intent(app,MeshServingService.class));
        // Serialized with any in-progress listener start. A newer enable wins.
        WORK.execute(()->{if(!enabled(app))MeshPeer.get(app).stop();});
    }
    static void listenerLost(){
        MeshServingService service=current;if(service!=null)service.signal();
    }

    @Override public void onCreate(){
        super.onCreate();current=this;
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Roots Mesh sharing",NotificationManager.IMPORTANCE_LOW));
        // Enter the foreground before any Keystore, database or socket work.
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification("Restoring listener"),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION,notification("Restoring listener"));
        connectivity=getSystemService(ConnectivityManager.class);
        watchNetwork();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){
            disable(this);return START_NOT_STICKY;
        }
        if(!enabled(this)){stopSelf();return START_NOT_STICKY;}
        schedule(0);return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}

    private Notification notification(String message){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MeshActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,MeshServingService.class).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Roots Mesh").setContentText(message).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null,"Stop listening",stop).build())
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
    }
    private void state(String next){
        publish(next);
        NotificationManager manager=getSystemService(NotificationManager.class);
        if(manager!=null)manager.notify(NOTIFICATION,notification(next));
    }
    private void watchNetwork(){
        if(callback!=null||connectivity==null)return;
        ConnectivityManager.NetworkCallback next=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network n){signal();}
            @Override public void onLost(Network n){signal();}
            @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities caps){signal();}
            @Override public void onLinkPropertiesChanged(Network n,LinkProperties properties){signal();}
        };
        try{connectivity.registerDefaultNetworkCallback(next);callback=next;}
        catch(RuntimeException ignored){/* Retry registration after an unsuccessful reconciliation. */}
    }
    private void signal(){main.post(()->{if(!destroyed){retryDelay=1000;schedule(150);}});}
    private void schedule(long delay){
        if(destroyed)return;
        main.removeCallbacks(reconcileTask);main.postDelayed(reconcileTask,delay);
    }
    private void reconcile(){
        if(destroyed||current!=this)return;
        if(!enabled(this)){stopSelf();return;}
        if(working){pending=true;return;}
        watchNetwork();
        working=true;
        Context app=getApplicationContext();
        WORK.execute(()->{
            String result;
            boolean retry=false;
            try{
                if(current!=this||!enabled(app))return;
                String invitation=MeshPeer.get(app).restoreListener();
                result=invitation.isEmpty()?"Waiting for direct Wi-Fi":"Listening on Wi-Fi";
            }catch(Exception|LinkageError error){
                result="Listener unavailable: "+error.getClass().getSimpleName()+". Retrying automatically.";
                retry=true;
            }
            final String message=result;final boolean failed=retry;
            main.post(()->{
                working=false;
                if(destroyed||current!=this)return;
                if(!enabled(this)){stopSelf();return;}
                state(message);
                if(pending){pending=false;schedule(0);}
                else if(failed||callback==null){
                    schedule(retryDelay);retryDelay=Math.min(30000,retryDelay*2);
                }else retryDelay=1000;
            });
        });
    }
    @Override public void onDestroy(){
        destroyed=true;main.removeCallbacksAndMessages(null);
        if(callback!=null&&connectivity!=null)try{connectivity.unregisterNetworkCallback(callback);}catch(RuntimeException ignored){}
        callback=null;
        if(current==this)current=null;
        Context app=getApplicationContext();
        WORK.execute(()->{if(current==null||!enabled(app))MeshPeer.get(app).stop();});
        if(current==null)publish(enabled(app)?"Waiting to restore listener":"Sharing off");
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
