package com.wds.sixstars.contextoverlay;

import android.Manifest;
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

/** User-started, offline BLE encounter engine. Android 12+ avoids location
 * permission on the passing device. GATT advertises only a dynamic CoC PSM;
 * the authenticated application protocol runs on the LE stream, not IP. */
@SuppressLint("MissingPermission")
public final class EncounterService extends Service {
    static final String PUBLISHER="publisher",PASSERBY="passerby";
    static final String ACTION_STOP="com.wds.roots.encounter.STOP",EXTRA_MODE="mode";
    static final UUID SERVICE=UUID.fromString("7d6f6f74-732d-4c45-9a01-000000000001");
    static final UUID PSM=UUID.fromString("7d6f6f74-732d-4c45-9a01-000000000002");
    private static final String CHANNEL="roots_encounter",KEY_ALIAS="roots.encounter.sign.v1";
    private static final int NOTIFICATION=8234;
    private static volatile String status="Stopped",lastResult="No encounter yet";
    private static volatile boolean started;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,Long> cooldown=new LinkedHashMap<>();
    private volatile boolean running;
    private volatile BluetoothServerSocket listener;
    private volatile BluetoothSocket socket;
    private volatile BluetoothGattServer gattServer;
    private volatile BluetoothGatt clientGatt;
    private volatile BluetoothLeAdvertiser advertiser;
    private volatile BluetoothLeScanner scanner;
    private volatile BluetoothAdapter adapter;
    private volatile KeyPair identity;
    private String mode;

    static boolean isRunning(){return started;}
    static String status(){return status;}
    static String lastResult(){return lastResult;}
    static boolean available(Context context){
        return Build.VERSION.SDK_INT>=31&&context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }
    static String[] permissions(String mode){
        if(PUBLISHER.equals(mode))return new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE};
        return new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN};
    }
    private boolean granted(String role){
        for(String permission:permissions(role))if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)return false;
        return true;
    }
    private android.content.SharedPreferences settings(){return getSharedPreferences("roots_encounter_service",MODE_PRIVATE);}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&ACTION_STOP.equals(intent.getAction())){
            settings().edit().remove(EXTRA_MODE).commit();running=false;status="Stopped";stopSelf();return START_NOT_STICKY;
        }
        if(running)return START_STICKY;
        String requested=intent==null?settings().getString(EXTRA_MODE,""):intent.getStringExtra(EXTRA_MODE);
        if(!available(this)||(!PUBLISHER.equals(requested)&&!PASSERBY.equals(requested))||!granted(requested)){
            settings().edit().remove(EXTRA_MODE).apply();status="Bluetooth permission or Android 12+ required";stopSelf();return START_NOT_STICKY;
        }
        mode=requested;
        try{
            NotificationManager notifications=getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL,"Roots nearby exchange",NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,EncounterActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,EncounterService.class).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notice=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Roots · nearby exchange").setContentText(PUBLISHER.equals(mode)?"Publishing by Bluetooth":"Listening for Roots Posts")
                .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
            startForeground(NOTIFICATION,notice,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            settings().edit().putString(EXTRA_MODE,mode).commit();
            running=true;started=true;status="Starting Bluetooth";
            worker.execute(this::startRadio);
            return START_STICKY;
        }catch(Exception failure){fail("Could not start nearby exchange");return START_NOT_STICKY;}
    }
    private void startRadio(){
        try{
            BluetoothManager manager=getSystemService(BluetoothManager.class);
            adapter=manager==null?null:manager.getAdapter();
            if(adapter==null||!adapter.isEnabled())throw new IOException("Bluetooth is off");
            identity=identity();
            if(!running)return;
            if(PUBLISHER.equals(mode))publish(manager);else scan();
        }catch(Exception|LinkageError failure){if(running)fail("Bluetooth unavailable · turn Bluetooth on and retry");}
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
    private void publish(BluetoothManager manager)throws Exception{
        BluetoothServerSocket server=adapter.listenUsingInsecureL2capChannel();listener=server;
        if(!running){close(server);return;}
        int psm=server.getPsm();
        if(!validPsm(psm))throw new IOException("Invalid dynamic LE PSM");
        advertiser=adapter.getBluetoothLeAdvertiser();
        if(advertiser==null)throw new IOException("BLE advertising unavailable");
        BluetoothGattServer gatt=manager.openGattServer(this,new BluetoothGattServerCallback(){
            @Override public void onServiceAdded(int result,BluetoothGattService service){
                if(!running)return;
                if(result!=BluetoothGatt.GATT_SUCCESS||!SERVICE.equals(service.getUuid())){fail("Could not register Roots BLE service");return;}
                try{
                    AdvertiseSettings options=new AdvertiseSettings.Builder().setConnectable(true)
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build();
                    AdvertiseData data=new AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
                        .addServiceUuid(new ParcelUuid(SERVICE)).build();
                    advertiser.startAdvertising(options,data,advertising);
                }catch(Exception error){fail("Could not advertise Roots Post");}
            }
            @Override public void onCharacteristicReadRequest(BluetoothDevice device,int requestId,int offset,BluetoothGattCharacteristic characteristic){
                BluetoothGattServer current=gattServer;
                if(!running||current==null)return;
                try{
                    if(!PSM.equals(characteristic.getUuid())){current.sendResponse(device,requestId,BluetoothGatt.GATT_FAILURE,offset,null);return;}
                    byte[] value={(byte)(psm>>>8),(byte)psm};
                    if(offset<0||offset>value.length){current.sendResponse(device,requestId,BluetoothGatt.GATT_INVALID_OFFSET,offset,null);return;}
                    current.sendResponse(device,requestId,BluetoothGatt.GATT_SUCCESS,offset,Arrays.copyOfRange(value,offset,value.length));
                }catch(Exception ignored){}
            }
        });
        gattServer=gatt;
        if(!running){close(gatt);return;}
        if(gatt==null)throw new IOException("GATT server unavailable");
        BluetoothGattService service=new BluetoothGattService(SERVICE,BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(new BluetoothGattCharacteristic(PSM,BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ));
        if(!gatt.addService(service))throw new IOException("Could not register GATT service");
        while(running){
            BluetoothSocket incoming=server.accept();
            socket=incoming;
            if(!running){close(incoming);break;}
            try{session(incoming,false);}
            catch(Exception|LinkageError rejected){if(running)lastResult="Encounter interrupted or rejected; saved progress retained";}
            finally{close(incoming);if(socket==incoming)socket=null;}
            if(running)status="Publisher · waiting for a passerby";
        }
    }
    private final AdvertiseCallback advertising=new AdvertiseCallback(){
        @Override public void onStartSuccess(AdvertiseSettings settings){if(running)status="Publisher · waiting for a passerby";}
        @Override public void onStartFailure(int error){if(running)fail("BLE advertising failed ("+error+")");}
    };

    private void scan()throws IOException{
        BluetoothLeScanner current=adapter.getBluetoothLeScanner();scanner=current;
        if(current==null)throw new IOException("BLE scanner unavailable");
        if(!running)return;
        ScanFilter filter=new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build();
        ScanSettings options=new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).setReportDelay(0).build();
        current.startScan(Collections.singletonList(filter),options,scanCallback);
        if(running)status="Passerby · listening for Roots Posts";
        else current.stopScan(scanCallback);
    }
    private final ScanCallback scanCallback=new ScanCallback(){
        @Override public void onScanResult(int callbackType,ScanResult result){discovered(result);}
        @Override public void onBatchScanResults(List<ScanResult> results){for(ScanResult result:results)discovered(result);}
        @Override public void onScanFailed(int error){if(running)fail("BLE discovery failed ("+error+")");}
    };
    private void discovered(ScanResult result){
        if(!running||result==null||!result.isConnectable())return;
        BluetoothDevice device=result.getDevice();if(device==null)return;
        // Volatile radio addresses are used only for short retry backoff, never as a binding.
        String address=device.getAddress();long now=SystemClock.elapsedRealtime();
        synchronized(cooldown){
            Long previous=cooldown.get(address);
            if(previous!=null&&now-previous<30000)return;
            if(!busy.compareAndSet(false,true))return;
            if(cooldown.size()>=128)cooldown.clear();
            cooldown.put(address,now);
        }
        try{worker.execute(()->{
            try{if(running)connect(device);}
            catch(Exception|LinkageError rejected){if(running)lastResult="Encounter interrupted or rejected; saved progress retained";}
            finally{busy.set(false);if(running)status="Passerby · listening for Roots Posts";}
        });}catch(RejectedExecutionException stopped){busy.set(false);}
    }
    private void connect(BluetoothDevice device)throws Exception{
        status="Reading Roots Post invitation";
        CompletableFuture<Integer> invitation=new CompletableFuture<>();
        BluetoothGattCallback callback=new BluetoothGattCallback(){
            @Override public void onConnectionStateChange(BluetoothGatt gatt,int result,int state){
                if(!running||result!=BluetoothGatt.GATT_SUCCESS||state==BluetoothProfile.STATE_DISCONNECTED){
                    invitation.completeExceptionally(new IOException("GATT disconnected"));return;
                }
                if(state==BluetoothProfile.STATE_CONNECTED&&!gatt.discoverServices())
                    invitation.completeExceptionally(new IOException("Could not discover service"));
            }
            @Override public void onServicesDiscovered(BluetoothGatt gatt,int result){
                BluetoothGattService service=gatt.getService(SERVICE);
                BluetoothGattCharacteristic characteristic=service==null?null:service.getCharacteristic(PSM);
                if(!running||result!=BluetoothGatt.GATT_SUCCESS||characteristic==null||!gatt.readCharacteristic(characteristic))
                    invitation.completeExceptionally(new IOException("No Roots PSM characteristic"));
            }
            private void read(BluetoothGattCharacteristic characteristic,byte[] bytes,int result){
                if(!PSM.equals(characteristic.getUuid())||result!=BluetoothGatt.GATT_SUCCESS||bytes==null||bytes.length!=2){
                    invitation.completeExceptionally(new IOException("Invalid Roots PSM response"));return;
                }
                int psm=((bytes[0]&255)<<8)|(bytes[1]&255);
                if(!validPsm(psm))invitation.completeExceptionally(new IOException("Invalid Roots PSM"));
                else invitation.complete(psm);
            }
            @Override public void onCharacteristicRead(BluetoothGatt gatt,BluetoothGattCharacteristic characteristic,int result){
                read(characteristic,characteristic.getValue(),result);
            }
            @Override public void onCharacteristicRead(BluetoothGatt gatt,BluetoothGattCharacteristic characteristic,byte[] value,int result){
                read(characteristic,value,result);
            }
        };
        BluetoothGatt gatt=device.connectGatt(this,false,callback,BluetoothDevice.TRANSPORT_LE);clientGatt=gatt;
        if(gatt==null)throw new IOException("Could not open GATT");
        try{
            if(!running)return;
            int psm=invitation.get(7,TimeUnit.SECONDS);
            if(!running)return;
            BluetoothSocket outgoing=device.createInsecureL2capChannel(psm);socket=outgoing;
            if(!running){close(outgoing);return;}
            try{
                ScheduledFuture<?> deadline=timer.schedule(()->close(outgoing),8,TimeUnit.SECONDS);
                try{outgoing.connect();}finally{deadline.cancel(false);}
                if(running)session(outgoing,true);
            }finally{close(outgoing);if(socket==outgoing)socket=null;}
        }finally{close(gatt);if(clientGatt==gatt)clientGatt=null;}
    }
    private void session(BluetoothSocket connection,boolean client)throws Exception{
        status="Binding and exchanging published Axons";
        ScheduledFuture<?> deadline=timer.schedule(()->close(connection),25,TimeUnit.SECONDS);
        try{
            if(!running)return;
            EncounterChannel channel=EncounterChannel.open(connection.getInputStream(),connection.getOutputStream(),identity,client);
            if(!running)return;
            EncounterSync sync=new EncounterSync(this,channel,()->running);
            int received=sync.exchange(client);
            lastResult="Exchange complete · "+received+" received · binding "+channel.peerId.substring(0,12);
        }finally{deadline.cancel(false);}
    }
    private void fail(String message){
        running=false;status=message;
        main.post(()->{settings().edit().remove(EXTRA_MODE).apply();stopSelf();});
    }
    private static void close(java.io.Closeable closeable){if(closeable!=null)try{closeable.close();}catch(Exception ignored){}}
    private static void close(BluetoothGatt gatt){if(gatt!=null)try{gatt.disconnect();gatt.close();}catch(Exception ignored){}}
    private static void close(BluetoothGattServer gatt){if(gatt!=null)try{gatt.close();}catch(Exception ignored){}}
    @Override public void onDestroy(){
        running=false;started=false;
        if(scanner!=null)try{scanner.stopScan(scanCallback);}catch(Exception ignored){}
        if(advertiser!=null)try{advertiser.stopAdvertising(advertising);}catch(Exception ignored){}
        close(listener);close(socket);close(clientGatt);close(gattServer);
        worker.shutdownNow();timer.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        if(settings().contains(EXTRA_MODE))status="Stopped by system · reopen Roots Mesh to resume if needed";
        super.onDestroy();
    }
}
