package com.wds.sixstars.contextoverlay;

import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.security.keystore.*;
import java.io.IOException;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One default-on service: advertise and scan together, with event-driven
 * radio recovery and independent inbound/outbound connection workers. */
@android.annotation.TargetApi(31)
@SuppressLint("MissingPermission")
public final class EncounterService extends Service {
    static final String ACTION_STOP="com.wds.roots.encounter.STOP";
    static final UUID SERVICE=UUID.fromString("7d6f6f74-732d-4c45-9a01-000000000001");
    static final UUID PSM=UUID.fromString("7d6f6f74-732d-4c45-9a01-000000000002");
    static final UUID COORD=UUID.fromString("7d6f6f74-732d-4c45-9a01-000000000003");
    private static final String CHANNEL="roots_encounter",KEY_ALIAS="roots.encounter.sign.v1";
    private static final int NOTIFICATION=8234;
    private static volatile String status="Waiting to start",lastResult="No encounter yet",activePeer="";
    private static volatile EncounterService instance;
    private static volatile boolean started;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile Radio radio;
    private volatile boolean running;
    private boolean receiverRegistered;
    private int retrySeconds=5;
    private final Runnable retry=()->ensureRadio();
    private final BroadcastReceiver bluetoothState=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent intent){
            if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction()))ensureRadio();
        }
    };

    static boolean isRunning(){return started;}
    static String status(){return status;}
    static String lastResult(){return lastResult;}
    static String activePeer(){return activePeer;}
    static void noteStartDeferred(){if(!started)status="Start deferred by Android · resumes when Roots opens";}
    static boolean available(Context c){return Build.VERSION.SDK_INT>=31&&c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);}
    static String requestReceive(Context context){
        if(!available(context))return "Nearby exchange requires Android 12+ and BLE.";
        if(!EncounterSettings.enabled(context))return "Nearby exchange is off. Enable it in Manage nearby exchange.";
        if(!EncounterSettings.eligible(context))return "Nearby exchange needs its one-time permission. Open Manage nearby exchange.";
        EncounterService service=instance;
        if(service==null||!started){EncounterSettings.startIfAllowed(context);return "Starting nearby exchange · saved Axons will reconnect when in range.";}
        Radio current=service.radio;
        if(current==null){service.main.post(service::ensureRadio);return "Nearby refresh requested · "+status;}
        current.clearCooldown();
        return "Nearby receive requested · reconnecting to available Axons. Check nearby status for the result.";
    }
    static void refreshDiscovery(){
        EncounterService service=instance;Radio r=service==null?null:service.radio;
        if(r!=null)r.clearCooldown();
    }
    static void disconnectPeer(String id){
        EncounterService service=instance;Radio r=service==null?null:service.radio;
        if(r!=null&&id.equals(activePeer))r.interruptSession(id);
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&ACTION_STOP.equals(intent.getAction())){
            EncounterSettings.setEnabled(this,false);running=false;status="Off";stopSelf();return START_NOT_STICKY;
        }
        if(!available(this)||!EncounterSettings.eligible(this)){stopSelf();return START_NOT_STICKY;}
        if(running)return START_STICKY;
        try{
            NotificationManager notifications=getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL,"Roots nearby exchange",NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,EncounterActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,EncounterService.class).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notice=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Roots · nearby exchange").setContentText("Automatically discovering and exchanging published knowledge")
                .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Turn off",stop).build()).build();
            startForeground(NOTIFICATION,notice,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            running=true;started=true;instance=this;
            IntentFilter filter=new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            if(Build.VERSION.SDK_INT>=33)registerReceiver(bluetoothState,filter,Context.RECEIVER_EXPORTED);
            else registerReceiver(bluetoothState,filter);
            receiverRegistered=true;
            ensureRadio();
            return START_STICKY;
        }catch(Exception failure){status="Could not start nearby exchange";stopSelf();return START_NOT_STICKY;}
    }
    private void ensureRadio(){
        main.removeCallbacks(retry);
        if(!running)return;
        if(!EncounterSettings.eligible(this)){stopSelf();return;}
        try{
            BluetoothManager manager=getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            if(adapter==null||!adapter.isEnabled()){
                closeRadio();status="On · waiting for Bluetooth";return;
            }
            if(radio!=null)return;
            Radio next=new Radio(manager,adapter);radio=next;
            status="Starting nearby discovery";next.start();
        }catch(Exception failure){scheduleRecovery("Radio unavailable");}
    }
    private void closeRadio(){Radio previous=radio;radio=null;activePeer="";if(previous!=null)previous.close();}
    private void scheduleRecovery(String reason){
        closeRadio();if(!running)return;
        status=reason+" · retrying automatically";
        main.removeCallbacks(retry);main.postDelayed(retry,retrySeconds*1000L);retrySeconds=Math.min(120,retrySeconds*2);
    }
    private void recover(Radio source,String reason){
        main.post(()->{if(radio==source&&running)scheduleRecovery(reason);});
    }
    private static synchronized KeyPair identity()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        KeyStore.Entry entry=store.getEntry(KEY_ALIAS,null);
        if(entry==null){
            KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build());
            return generator.generateKeyPair();
        }
        if(!(entry instanceof KeyStore.PrivateKeyEntry))throw new GeneralSecurityException("Invalid encounter signing identity");
        KeyStore.PrivateKeyEntry key=(KeyStore.PrivateKeyEntry)entry;
        return new KeyPair(key.getCertificate().getPublicKey(),key.getPrivateKey());
    }

    static boolean validPsm(int psm){return psm>=0x80&&psm<=0xff;}

    // Unsigned per-radio random rank, not a persistent identifier. Two fully
    // automatic phones prefer one initiator; old Posts omit COORD and work as before.

    private record Offer(int psm,byte[] rank){}
    private final class Radio {
        private final BluetoothManager manager;
        private final BluetoothAdapter adapter;
        private final ExecutorService outbound=Executors.newSingleThreadExecutor(),inbound=Executors.newSingleThreadExecutor();
        private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
        private final AtomicBoolean busy=new AtomicBoolean(),sessionGate=new AtomicBoolean();
        private final Map<String,Long> cooldown=new LinkedHashMap<>();
        private final byte[] rank=new byte[8];
        private final Random jitter=new SecureRandom();
        private volatile boolean closed,scanning,advertisingReady;
        private volatile BluetoothServerSocket listener;
        private volatile BluetoothSocket incomingSocket,outgoingSocket,sessionSocket;
        private volatile BluetoothGattServer gattServer;
        private volatile BluetoothGatt clientGatt;
        private volatile BluetoothLeAdvertiser advertiser;
        private volatile BluetoothLeScanner scanner;
        private volatile KeyPair key;
        Radio(BluetoothManager manager,BluetoothAdapter adapter){this.manager=manager;this.adapter=adapter;jitter.nextBytes(rank);}
        private boolean active(){return !closed&&running&&radio==this&&EncounterSettings.eligible(EncounterService.this);}
        void start(){
            inbound.execute(()->{
                try{
                    key=identity();
                    if(!active())return;
                    scan();
                    if(active())publish();
                }catch(Exception|LinkageError failure){if(active())recover(this,"Bluetooth transport interrupted");}
            });
        }
        private void idleStatus(){
            if(!active()||sessionGate.get())return;
            status=advertisingReady&&scanning?"On · discovering and publishing":scanning?"On · discovering (publisher unavailable)":advertisingReady?"On · publishing":"Starting nearby discovery";
        }
        private void scan()throws IOException{
            BluetoothLeScanner current=adapter.getBluetoothLeScanner();scanner=current;
            if(current==null)throw new IOException("Scanner unavailable");
            if(!active())return;
            ScanFilter filter=new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build();
            ScanSettings options=new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).setReportDelay(0).build();
            current.startScan(Collections.singletonList(filter),options,scanCallback);
            scanning=true;
            if(!active())current.stopScan(scanCallback);else idleStatus();
        }
        private final ScanCallback scanCallback=new ScanCallback(){
            @Override public void onScanResult(int type,ScanResult result){discovered(result);}
            @Override public void onBatchScanResults(List<ScanResult> results){for(ScanResult result:results)discovered(result);}
            @Override public void onScanFailed(int error){if(active())recover(Radio.this,"BLE discovery failed ("+error+")");}
        };
        private void publish()throws Exception{
            advertiser=adapter.getBluetoothLeAdvertiser();
            if(advertiser==null){idleStatus();return;}
            BluetoothServerSocket server=adapter.listenUsingInsecureL2capChannel();listener=server;
            if(!active()){closeResource(server);return;}
            int psm=server.getPsm();
            if(!validPsm(psm))throw new IOException("Invalid dynamic LE PSM");
            BluetoothGattServer gatt=manager.openGattServer(EncounterService.this,new BluetoothGattServerCallback(){
                @Override public void onServiceAdded(int result,BluetoothGattService service){
                    if(!active())return;
                    if(result!=BluetoothGatt.GATT_SUCCESS||!SERVICE.equals(service.getUuid())){recover(Radio.this,"GATT registration failed");return;}
                    try{
                        AdvertiseSettings options=new AdvertiseSettings.Builder().setConnectable(true)
                            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build();
                        AdvertiseData data=new AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
                            .addServiceUuid(new ParcelUuid(SERVICE)).build();
                        advertiser.startAdvertising(options,data,advertising);
                    }catch(Exception failure){recover(Radio.this,"BLE publishing failed");}
                }
                @Override public void onCharacteristicReadRequest(BluetoothDevice device,int requestId,int offset,BluetoothGattCharacteristic characteristic){
                    BluetoothGattServer current=gattServer;if(!active()||current==null)return;
                    try{
                        UUID id=characteristic.getUuid();
                        byte[] value=PSM.equals(id)?new byte[]{(byte)(psm>>>8),(byte)psm}:COORD.equals(id)&&scanning?rank:null;
                        if(value==null){current.sendResponse(device,requestId,BluetoothGatt.GATT_FAILURE,offset,null);return;}
                        if(offset<0||offset>value.length){current.sendResponse(device,requestId,BluetoothGatt.GATT_INVALID_OFFSET,offset,null);return;}
                        current.sendResponse(device,requestId,BluetoothGatt.GATT_SUCCESS,offset,Arrays.copyOfRange(value,offset,value.length));
                    }catch(Exception ignored){}
                }
            });
            gattServer=gatt;
            if(!active()){closeGattServer(gatt);return;}
            if(gatt==null)throw new IOException("GATT unavailable");
            BluetoothGattService service=new BluetoothGattService(SERVICE,BluetoothGattService.SERVICE_TYPE_PRIMARY);
            service.addCharacteristic(new BluetoothGattCharacteristic(PSM,BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ));
            service.addCharacteristic(new BluetoothGattCharacteristic(COORD,BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ));
            if(!gatt.addService(service))throw new IOException("GATT registration failed");
            while(active()){
                BluetoothSocket incoming=server.accept();incomingSocket=incoming;
                if(!active()){closeResource(incoming);break;}
                if(!sessionGate.compareAndSet(false,true)){closeResource(incoming);incomingSocket=null;continue;}
                try{session(incoming,false);}
                finally{closeResource(incoming);incomingSocket=null;sessionGate.set(false);idleStatus();}
            }
        }
        private final AdvertiseCallback advertising=new AdvertiseCallback(){
            @Override public void onStartSuccess(AdvertiseSettings settings){
                if(active()){advertisingReady=true;main.post(()->{if(radio==Radio.this)retrySeconds=5;});idleStatus();}
                else if(advertiser!=null)try{advertiser.stopAdvertising(this);}catch(Exception ignored){}
            }
            @Override public void onStartFailure(int error){if(active())recover(Radio.this,"BLE advertising failed ("+error+")");}
        };
        private void discovered(ScanResult result){
            if(!active()||result==null||!result.isConnectable()||sessionGate.get())return;
            BluetoothDevice device=result.getDevice();if(device==null)return;
            String address=device.getAddress();long now=SystemClock.elapsedRealtime();
            synchronized(cooldown){
                Long until=cooldown.get(address);
                if(until!=null&&now<until)return;
                if(!busy.compareAndSet(false,true))return;
                if(cooldown.size()>=128)cooldown.clear();
                cooldown.put(address,now+25000+jitter.nextInt(20000));
            }
            try{outbound.execute(()->{
                try{
                    if(active()){
                        int count=connect(device);
                        if(count>=0||count==-2)synchronized(cooldown){cooldown.put(address,SystemClock.elapsedRealtime()+EncounterArbitration.cooldownMs(count)+jitter.nextInt(15000));}
                    }
                }catch(Exception|LinkageError failure){if(active())lastResult="Nearby attempt interrupted · retrying automatically";}
                finally{busy.set(false);idleStatus();}
            });}catch(RejectedExecutionException stopped){busy.set(false);}
        }
        private int connect(BluetoothDevice device)throws Exception{
            CompletableFuture<Offer> offer=new CompletableFuture<>();
            BluetoothGattCallback callback=new BluetoothGattCallback(){
                private int psm;
                @Override public void onConnectionStateChange(BluetoothGatt gatt,int result,int state){
                    if(!active()||result!=BluetoothGatt.GATT_SUCCESS||state==BluetoothProfile.STATE_DISCONNECTED){
                        offer.completeExceptionally(new IOException("GATT disconnected"));return;
                    }
                    if(state==BluetoothProfile.STATE_CONNECTED&&!gatt.discoverServices())offer.completeExceptionally(new IOException("Could not discover service"));
                }
                @Override public void onServicesDiscovered(BluetoothGatt gatt,int result){
                    BluetoothGattService service=gatt.getService(SERVICE);
                    BluetoothGattCharacteristic characteristic=service==null?null:service.getCharacteristic(PSM);
                    if(!active()||result!=BluetoothGatt.GATT_SUCCESS||characteristic==null||!gatt.readCharacteristic(characteristic))
                        offer.completeExceptionally(new IOException("No Roots PSM"));
                }
                private void read(BluetoothGatt gatt,BluetoothGattCharacteristic characteristic,byte[] value,int result){
                    if(PSM.equals(characteristic.getUuid())){
                        if(result!=BluetoothGatt.GATT_SUCCESS||value==null||value.length!=2){offer.completeExceptionally(new IOException("Invalid PSM"));return;}
                        psm=((value[0]&255)<<8)|(value[1]&255);
                        if(!validPsm(psm)){offer.completeExceptionally(new IOException("Invalid PSM"));return;}
                        BluetoothGattService service=gatt.getService(SERVICE);
                        BluetoothGattCharacteristic coordination=service==null?null:service.getCharacteristic(COORD);
                        if(coordination==null||!gatt.readCharacteristic(coordination))offer.complete(new Offer(psm,null));
                    }else if(COORD.equals(characteristic.getUuid())&&validPsm(psm)){
                        offer.complete(new Offer(psm,result==BluetoothGatt.GATT_SUCCESS&&value!=null&&value.length==8?value.clone():null));
                    }
                }
                @Override public void onCharacteristicRead(BluetoothGatt gatt,BluetoothGattCharacteristic characteristic,int result){
                    read(gatt,characteristic,characteristic.getValue(),result);
                }
                @android.annotation.TargetApi(33)
                @Override public void onCharacteristicRead(BluetoothGatt gatt,BluetoothGattCharacteristic characteristic,byte[] value,int result){
                    read(gatt,characteristic,value,result);
                }
            };
            BluetoothGatt gatt=device.connectGatt(EncounterService.this,false,callback,BluetoothDevice.TRANSPORT_LE);clientGatt=gatt;
            if(gatt==null)throw new IOException("No GATT");
            try{
                if(!active())return -1;
                Offer remote=offer.get(8,TimeUnit.SECONDS);
                if(!active())return -1;
                if(remote.rank()!=null&&advertisingReady){
                    int comparison=EncounterArbitration.compareRank(rank,remote.rank());
                    // Equal ranks imply a self-discovery or negligible random collision.
                    // Occasionally let the other side initiate in case its scan is throttled.
                    if(comparison==0||(comparison>0&&jitter.nextInt(4)!=0))return -1;
                }
                if(!sessionGate.compareAndSet(false,true))return -1;
                try{
                    BluetoothSocket outgoing=device.createInsecureL2capChannel(remote.psm());outgoingSocket=outgoing;
                    if(!active()){closeResource(outgoing);return -1;}
                    try{
                        ScheduledFuture<?> deadline=timer.schedule(()->closeResource(outgoing),8,TimeUnit.SECONDS);
                        try{outgoing.connect();}finally{deadline.cancel(false);}
                        return active()?session(outgoing,true):-1;
                    }finally{closeResource(outgoing);outgoingSocket=null;}
                }finally{sessionGate.set(false);idleStatus();}
            }finally{closeGatt(gatt);if(clientGatt==gatt)clientGatt=null;}
        }
        private int session(BluetoothSocket connection,boolean client){
            if(!active())return -1;
            sessionSocket=connection;String peer="",result="Interrupted";EncounterSync sync=null;
            ScheduledFuture<?> deadline=null;
            try{
                deadline=timer.schedule(()->closeResource(connection),25,TimeUnit.SECONDS);
                status="Authenticating nearby Axon";
                EncounterChannel channel=EncounterChannel.open(connection.getInputStream(),connection.getOutputStream(),key,client);
                peer=channel.peerId;
                if(!active()){result="Blocked or stopped";return -1;}
                if(EncounterBindings.get(EncounterService.this).blocked(peer)){result="Blocked or stopped";return -2;}
                activePeer=peer;
                status="Exchanging published Axons";
                sync=new EncounterSync(EncounterService.this,channel,this::active);
                sync.exchange(client);result=(sync.firstEncounter()?"New binding":"Binding refreshed")+" · "+sync.summary();return Math.max(sync.sent()+sync.received(),sync.pending()?1:0);
            }catch(Exception|LinkageError failure){result="Interrupted · "+(sync==null?"authentication or catalog setup":sync.stage())+" ("+failure.getClass().getSimpleName()+")";return -1;}
            finally{
                if(deadline!=null)deadline.cancel(false);
                int sent=sync==null?0:sync.sent(),received=sync==null?0:sync.received();
                // Don't build a persistent tracking record of repeatedly rejected blocked encounters.
                if(!peer.isEmpty()&&!"Blocked or stopped".equals(result)){
                    try{EncounterBindings.get(EncounterService.this).outcome(peer,result,sent,received);}
                    catch(Exception failure){result+=" · history not saved";}
                }
                if(radio==this){lastResult=result+" · "+sent+" sent · "+received+" received";activePeer="";}
                sessionSocket=null;
            }
        }
        void clearCooldown(){synchronized(cooldown){cooldown.clear();}}
        void interruptSession(String peer){if(peer.equals(activePeer))closeResource(sessionSocket);}
        void close(){
            closed=true;scanning=false;advertisingReady=false;
            if(scanner!=null)try{scanner.stopScan(scanCallback);}catch(Exception ignored){}
            if(advertiser!=null)try{advertiser.stopAdvertising(advertising);}catch(Exception ignored){}
            closeResource(listener);closeResource(incomingSocket);closeResource(outgoingSocket);closeResource(sessionSocket);
            closeGatt(clientGatt);closeGattServer(gattServer);
            inbound.shutdownNow();outbound.shutdownNow();timer.shutdownNow();
        }
    }
    private static void closeResource(java.io.Closeable value){if(value!=null)try{value.close();}catch(Exception ignored){}}
    private static void closeGatt(BluetoothGatt value){if(value!=null){try{value.disconnect();}catch(Exception ignored){}try{value.close();}catch(Exception ignored){}}}
    private static void closeGattServer(BluetoothGattServer value){if(value!=null)try{value.close();}catch(Exception ignored){}}
    @Override public void onDestroy(){
        running=false;started=false;activePeer="";if(instance==this)instance=null;
        main.removeCallbacks(retry);closeRadio();
        if(receiverRegistered)try{unregisterReceiver(bluetoothState);}catch(Exception ignored){}
        stopForeground(STOP_FOREGROUND_REMOVE);
        status=EncounterSettings.enabled(this)?"On · waiting for Android to resume service":"Off";
        super.onDestroy();
    }
}
