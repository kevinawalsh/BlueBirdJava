using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;

using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Enumeration;


namespace BlueBirdWindowsCL
{
    class BleManager
    {
        public static BleManager Shared { get; private set; } = new BleManager();

        private BluetoothLEAdvertisementWatcher adWatcher;
        private DeviceWatcher watcher;
        // "Magic" string for all BLE devices
        static string _aqsAllBLEDevices = "(System.Devices.Aep.ProtocolId:=\"{bb7bb05e-5972-42b5-94fc-76eaa7084d49}\")";
        static string[] _requestedBLEProperties = { "System.Devices.Aep.DeviceAddress", "System.Devices.Aep.Bluetooth.Le.IsConnectable", };
        
        static List<DeviceInformation> _deviceList = new List<DeviceInformation>();
        static Dictionary<string, Robot> connectedRobots = new Dictionary<string, Robot>();

        static TimeSpan _timeout = TimeSpan.FromSeconds(3);

        public void startScan()
        {
            // Create Bluetooth Listener
            adWatcher = new BluetoothLEAdvertisementWatcher();
            adWatcher.ScanningMode = BluetoothLEScanningMode.Active;
            // Only activate the watcher when we're recieving values >= -80
            adWatcher.SignalStrengthFilter.InRangeThresholdInDBm = -80;
            // Stop watching if the value drops below -90 (user walked away)
            adWatcher.SignalStrengthFilter.OutOfRangeThresholdInDBm = -90;
            // Register callback for when we see an advertisements
            adWatcher.Received += OnAdvertisementReceived;
            // Wait 5 seconds to make sure the device is really out of range
            adWatcher.SignalStrengthFilter.OutOfRangeTimeout = TimeSpan.FromMilliseconds(5000);
            adWatcher.SignalStrengthFilter.SamplingInterval = TimeSpan.FromMilliseconds(2000);


            // Start endless BLE device watcher
            watcher = DeviceInformation.CreateWatcher(_aqsAllBLEDevices, _requestedBLEProperties, DeviceInformationKind.AssociationEndpoint);
            watcher.Added += (DeviceWatcher sender, DeviceInformation devInfo) =>
            {
                if (_deviceList.FirstOrDefault(d => d.Id.Equals(devInfo.Id) || d.Name.Equals(devInfo.Name)) == null) _deviceList.Add(devInfo);
            };
            watcher.Updated += (_, __) => { }; // We need handler for this event, even an empty!
            //TODO:
            watcher.Removed += (_, __) => { };
            watcher.EnumerationCompleted += (_, __) => { };
            watcher.Stopped += (_, __) => { };

            watcher.Start();
            adWatcher.Start();
            //Console.WriteLine("Scan Started...");
        }

        private void OnAdvertisementReceived(BluetoothLEAdvertisementWatcher watcher, BluetoothLEAdvertisementReceivedEventArgs eventArgs)
        {
            if (string.IsNullOrEmpty(eventArgs.Advertisement.LocalName))
            {
                //Console.WriteLine("empty...");
                return;
            }
            var args = new string[] {"peripheral", eventArgs.Advertisement.LocalName, "rssi", eventArgs.RawSignalStrengthInDBm.ToString()};
            Utilities.WriteOut("discovery", args);
        }

        public void stopScan()
        {
            //TODO: unregister event handlers?
            if (watcher != null)
            {
                watcher.Stop();
                watcher = null;
            }
            if (adWatcher != null)
            {
                adWatcher.Stop();
                adWatcher = null;
            }
            //Console.WriteLine("Scan Stopped");
        }

        public void close()
        {
            //TODO: disconnect all devices
            stopScan();
        }

        /// <summary>
        /// Connect to the specific device by name or number, and make this device current
        /// </summary>
        /// <param name="deviceName"></param>
        /// <returns></returns>
        public async Task<int> OpenDevice(string deviceName, string devLetter)
        {
            int retVal = 0;
            if (!string.IsNullOrEmpty(deviceName))
            {
                var devs = _deviceList.OrderBy(d => d.Name).Where(d => !string.IsNullOrEmpty(d.Name)).ToList();
                string foundId = Utilities.GetIdByNameOrNumber(devs, deviceName);

                // If device is found, connect to device and enumerate all services
                if (!string.IsNullOrEmpty(foundId))
                {
                    try
                    {
                        var device = await BluetoothLEDevice.FromIdAsync(foundId).AsTask().TimeoutAfter(_timeout);
                        var robot = new Robot(device, devLetter);
                        connectedRobots.Add(device.Name, robot);
                    }
                    catch
                    {
                        Console.WriteLine($"Device {deviceName} is unreachable.");
                        retVal += 1;
                    }
                }
                else
                {
                    retVal += 1;
                }
            }
            else
            {
                Console.WriteLine("Device name can not be empty.");
                retVal += 1;
            }
            return retVal;
        }

        public void DisconnectDevice(string name, bool userDisconnect)
        {
            //Console.WriteLine($"Disconnecting {name} with {userDisconnect}");
            Robot robot;
            bool robotFound = connectedRobots.TryGetValue(name, out robot);
            if (robotFound)
            {
                robot.CloseDevice(userDisconnect);
                if (userDisconnect)
                {
                    connectedRobots.Remove(name);
                }
                else
                {
                    //TODO: Autoreconnect?
                }
                //Console.WriteLine("Should be disconnected");
            }
            else
            {
                Utilities.WriteError($"Could not find {name} to disconnect");
            }
        }

        public void sendBlob(string devName, string blob)
        {
            Robot robot;
            bool robotFound = connectedRobots.TryGetValue(devName, out robot);
            if (robotFound)
            {
                robot.sendBlob(blob);
            }
            else
            {
                Utilities.WriteError($"Could not sendBlob to {devName}. Robot not found.");
            }
        }

        public void RobotConnectionStatusChanged(BluetoothLEDevice sender, object args)
        {
            //Console.WriteLine($"ConnectionStatusChange {sender.ConnectionStatus}");
            switch (sender.ConnectionStatus)
            {
                case BluetoothConnectionStatus.Connected:
                    break;
                case BluetoothConnectionStatus.Disconnected:
                    DisconnectDevice(sender.Name, false);
                    break;
            }
        }
        public void RobotNameChanged(BluetoothLEDevice sender, object args)
        {
            Utilities.WriteError($"Name changed to {sender.Name}??");
        }
    }
}
