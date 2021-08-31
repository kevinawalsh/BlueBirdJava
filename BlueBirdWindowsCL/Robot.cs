using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;

using Windows.Storage.Streams;
using Windows.Security.Cryptography;

using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;


namespace BlueBirdWindowsCL
{
    public class Robot
    {
        private static Guid ServiceUuid = new Guid("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
        private static BluetoothCacheMode cacheMode = BluetoothCacheMode.Uncached;

        private List<GattDeviceService> Services = new List<GattDeviceService>();

        private BluetoothLEDevice bleDevice;
        private string deviceType;
        private int microBitVersionNumber = 0;
        public bool hasV2
        {
            get
            {
                return microBitVersionNumber == 2;
            }
        }

        private GattCharacteristic tx;
        private GattCharacteristic rx;

        private byte[] GET_FIRMWARE_CMD
        {
            get
            {
                if (bleDevice == null)
                {
                    return new byte[] { };
                }
                else if (deviceType == "FN")
                {
                    return new byte[] { 0xD4 };
                }
                else
                {
                    return new byte[] { 0xCF };
                }
            }
        }

        private string name;
        public string Name
        {
            get 
            {
                return name;
            }
        }


        public Robot(BluetoothLEDevice device)
        {
            bleDevice = device;
            name = device.Name;
            deviceType = device.Name.Substring(0, 2);

            bleDevice.ConnectionStatusChanged += BleManager.Shared.RobotConnectionStatusChanged;
            bleDevice.NameChanged += BleManager.Shared.RobotNameChanged;

            getRXandTX();
        }

        ~Robot()
        {
            CloseDevice(false);
        }

        private async void getRXandTX()
        {
            bool setupSuccess = false;
            CancellationTokenSource GetGattServicesAsyncTokenSource = new CancellationTokenSource(5000);
            var GetGattServicesAsyncTask = Task.Run(() => bleDevice.GetGattServicesForUuidAsync(ServiceUuid, cacheMode), GetGattServicesAsyncTokenSource.Token);
            var serviceResult = await GetGattServicesAsyncTask.Result;

            if (serviceResult.Status == GattCommunicationStatus.Success)
            {
                //Console.WriteLine($"Found {serviceResult.Services.Count} services:");

                for (int i = 0; i < serviceResult.Services.Count; i++)
                {
                    Services.Add(serviceResult.Services[i]);

                    //Console.WriteLine($"#{i:00}: {_services[i].Name}");
                    var service = serviceResult.Services[i];

                    try
                    {
                        //await serviceLock.WaitAsync();

                        // Request the necessary access permissions for the service and abort
                        // if permissions are denied.
                        GattOpenStatus status = await service.OpenAsync(GattSharingMode.SharedReadAndWrite);

                        if (status != GattOpenStatus.Success && status != GattOpenStatus.AlreadyOpened)
                        {
                            string error = "Error opening service: " + status.ToString();
                            Utilities.WriteError(error);
                        }
                        else
                        {
                            GattCharacteristicsResult result = await service.GetCharacteristicsAsync(cacheMode);

                            if (result.Status == GattCommunicationStatus.Success)
                            {

                                foreach (GattCharacteristic gattchar in result.Characteristics)
                                {
                                    var UUID = gattchar.Uuid.ToString();

                                    if (UUID == "6e400002-b5a3-f393-e0a9-e50e24dcca9e")
                                    {
                                        tx = gattchar;
                                    }
                                    if (UUID == "6e400003-b5a3-f393-e0a9-e50e24dcca9e")
                                    {
                                        var setNotifyResult = await
                                            gattchar.WriteClientCharacteristicConfigurationDescriptorAsync(
                                            GattClientCharacteristicConfigurationDescriptorValue.Notify);
                                        if (setNotifyResult == GattCommunicationStatus.Success)
                                        {
                                            rx = gattchar;
                                            rx.ValueChanged += ReceiveNotification;
                                        }
                                    }
                                }

                                if (rx != null && tx != null)
                                {
                                    setupSuccess = true;
                                    Write(GET_FIRMWARE_CMD);
                                }
                                else
                                {
                                    Utilities.WriteError($"Failed to get rx and tx for {Name}.");
                                }
                            }
                            else
                            {
                                Utilities.WriteError($"Failed to get Characteristics for {Name}.");
                            }
                        }
                    }
                    catch (Exception ex)
                    {
                        Utilities.WriteError($"Exception caught while getting characteristics: {ex.Message}");
                    }
                    finally
                    {
                        //serviceLock.Release();
                    }

                }
            }
            else
            {
                Utilities.WriteError($"Could not get services for device {Name}.");
            }

            if (!setupSuccess)
            {
                Utilities.WriteError($"Setup failed for {Name}. Disconnecting.");
                BleManager.Shared.DisconnectDevice(Name, false);
            }
        }

        private async void Write(byte[] bytes)
        {
            //Utilities.WriteDebug($"About to write {BitConverter.ToString(bytes)}");

            if (tx == null)
            {
                Utilities.WriteError($"Cannot write to {Name}, tx not set.");
                return;
            }

            var writer = new DataWriter();
            writer.WriteBytes(bytes);

            GattCommunicationStatus result = await tx.WriteValueAsync(writer.DetachBuffer(), GattWriteOption.WriteWithoutResponse);
            if (result == GattCommunicationStatus.Success)
            {
                //Utilities.WriteDebug($"Successfully wrote {BitConverter.ToString(bytes)}");
            }
            else
            {
                Utilities.WriteError($"Failed to write {BitConverter.ToString(bytes)}");
            }
        }

        public void sendBlob(string blob)
        {
            byte[] bytes = Convert.FromBase64String(blob);
            Write(bytes);
        }

        private void ReceiveNotification(GattCharacteristic sender, GattValueChangedEventArgs args)
        {
            byte[] data;
            CryptographicBuffer.CopyToByteArray(args.CharacteristicValue, out data);

            if (rx != null && tx != null)
            {
                if (data.Length < 10) //this should be firmware version info
                {
                    SetFirmwareVersion(data);
                }
                else
                {
                    var arguments = new string[]
                    {
                        "peripheral", Name,
                        "data", BitConverter.ToString(data)
                    };
                    Utilities.WriteOut("notification", arguments);
                }
            }
        }

        private void SetFirmwareVersion(byte[] fvArray)
        {
            //Debug.WriteLine($"Setting firmware version for {name} to {BitConverter.ToString(fvArray)}");
            if (microBitVersionNumber != 0)
            {
                //Debug.WriteLine($"Cannot set firmware version for {name} more than once!");
                Utilities.WriteError($"Cannot set firmware version for {Name} more than once!");
                return;
            }

            byte[] pollStart;
            if (fvArray.Length > 3 && fvArray[3] == 0x22)
            {
                microBitVersionNumber = 2;
                pollStart = new byte[] { 0x62, 0x70 };
            }
            else
            {
                microBitVersionNumber = 1;
                pollStart = new byte[] { 0x62, 0x67 };
            }

            Write(pollStart);

            Utilities.WriteConnectionUpdate("connected", this);
        }


        /// <summary>
        /// Disconnect current device and clear list of services and characteristics
        /// </summary>
        public void CloseDevice(bool userDisconnect)
        {
            //Console.WriteLine($"CloseDevice {Name}");
            if (bleDevice == null) { return; }

            Services.ForEach((s) => { s.Dispose(); });
            Services.Clear();
            bleDevice.ConnectionStatusChanged -= BleManager.Shared.RobotConnectionStatusChanged;
            bleDevice.NameChanged -= BleManager.Shared.RobotNameChanged;
            bleDevice.Dispose();
            bleDevice = null;

            if (userDisconnect)
            {
                Utilities.WriteConnectionUpdate("userDisconnected", this);
            }
            else
            {
                Utilities.WriteConnectionUpdate("deviceDisconnected", this);
            }

            // Remove all subscriptions
            //if (_subscribers.Count > 0) Unsubscribe("all");

            /*if (bleDevice != null)
            {
                if (!Console.IsInputRedirected)
                    Console.WriteLine($"Device {Name} is disconnected.");

                //_services?.ForEach((s) => { s.service?.Dispose(); });
                //_services?.Clear();
                //_characteristics?.Clear();

                Services.ForEach((s) => { s.Dispose(); });
                Services.Clear();
                bleDevice?.Dispose();
            }*/
        }

        /// <summary>
        /// This function is used to unsubscribe from "ValueChanged" event
        /// </summary>
        /// <param name="param"></param>
        static async void Unsubscribe(string param)
        {
            /*if (_subscribers.Count == 0)
            {
                if (!Console.IsOutputRedirected)
                    Console.WriteLine("No subscription for value changes found.");
            }
            else if (string.IsNullOrEmpty(param))
            {
                if (!Console.IsOutputRedirected)
                    Console.WriteLine("Please specify characteristic name or # (for single subscription) or type \"unsubs all\" to remove all subscriptions");
            }
            // Unsubscribe from all value changed events
            else if (param.Replace("/", "").ToLower().Equals("all"))
            {
                foreach (var sub in _subscribers)
                {
                    await sub.WriteClientCharacteristicConfigurationDescriptorAsync(GattClientCharacteristicConfigurationDescriptorValue.None);
                    sub.ValueChanged -= Characteristic_ValueChanged;
                }
                _subscribers.Clear();
            }
            // unsubscribe from specific event
            else
            {

            }*/
        }

        /// <summary>
        /// Event handler for ValueChanged callback
        /// </summary>
        /// <param name="sender"></param>
        /// <param name="args"></param>
        static void Characteristic_ValueChanged(GattCharacteristic sender, GattValueChangedEventArgs args)
        {
            /*if (_primed)
            {
                var newValue = Utilities.FormatValue(args.CharacteristicValue, _dataFormat);

                if (Console.IsInputRedirected) Console.Write($"{newValue}");
                else Console.Write($"Value changed for {sender.Uuid}: {newValue}\nBLE: ");
                if (_notifyCompleteEvent != null)
                {
                    _notifyCompleteEvent.Set();
                    _notifyCompleteEvent = null;
                }
            }
            else _primed = true;*/
        }
    }
}
