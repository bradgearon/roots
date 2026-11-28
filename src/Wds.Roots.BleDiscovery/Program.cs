using System.Collections.Concurrent;
using System.Net;
using System.Text.Json;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

// WinRT supports GATT discovery but does not provide Android's LE CoC API.
// Return only the advertised public Axon route; REP authenticates it over TCP.
Guid service=Guid.Parse("7d6f6f74-732d-4c45-9a01-000000000001");
Guid route=Guid.Parse("7d6f6f74-732d-4c45-9a01-000000000004");
var addresses=new ConcurrentDictionary<ulong,byte>();
var watcher=new BluetoothLEAdvertisementWatcher{ScanningMode=BluetoothLEScanningMode.Passive};
watcher.AdvertisementFilter.Advertisement.ServiceUuids.Add(service);
watcher.Received+=(_,e)=>{if(addresses.Count<32)addresses.TryAdd(e.BluetoothAddress,0);};
try{watcher.Start();await Task.Delay(6000);}
catch(Exception){return 2;}
finally{watcher.Stop();}
using var slots=new SemaphoreSlim(3);
await Task.WhenAll(addresses.Keys.Select(async address=>{
    await slots.WaitAsync();
    try
    {
        using var device=await BluetoothLEDevice.FromBluetoothAddressAsync(address).AsTask().WaitAsync(TimeSpan.FromSeconds(5));
        if(device==null)return;
        var services=await device.GetGattServicesForUuidAsync(service,BluetoothCacheMode.Uncached).AsTask().WaitAsync(TimeSpan.FromSeconds(5));
        if(services.Status!=GattCommunicationStatus.Success)return;
        foreach(var gatt in services.Services)
        {
            using(gatt)
            {
                var chars=await gatt.GetCharacteristicsForUuidAsync(route,BluetoothCacheMode.Uncached).AsTask().WaitAsync(TimeSpan.FromSeconds(4));
                if(chars.Status!=GattCommunicationStatus.Success)continue;
                foreach(var characteristic in chars.Characteristics)
                {
                    var value=await characteristic.ReadValueAsync(BluetoothCacheMode.Uncached).AsTask().WaitAsync(TimeSpan.FromSeconds(4));
                    if(value.Status!=GattCommunicationStatus.Success||value.Value.Length!=38)continue;
                    using var reader=DataReader.FromBuffer(value.Value);byte[] data=new byte[38];reader.ReadBytes(data);
                    int port=data[4]*256+data[5];if(port!=8235)continue;
                    string host=new IPAddress(data[..4]).ToString(),id=Convert.ToHexString(data[6..]).ToLowerInvariant();
                    Console.WriteLine(JsonSerializer.Serialize(new{host,id,port}));
                }
            }
        }
    }
    catch(Exception){/* optional radio/peer not available */}
    finally{slots.Release();}
}));
return 0;
