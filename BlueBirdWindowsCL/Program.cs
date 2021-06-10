using System;
using System.Linq;
using System.Threading.Tasks;


namespace BlueBirdWindowsCL
{
    class Program
    {
        static bool shouldRun = true;

        static void Main(string[] args)
        {
            // Run main loop
            MainAsync(args).Wait();
        }

        static async Task MainAsync(string[] args)
        {
            //TODO: evaluate bluetooth state.
            bool bleAvailable = await BleManager.Shared.CheckBleAvailability();
            if (!bleAvailable)
            {
                Utilities.WriteBluetoothState( "unavailable" );
            }

            while (shouldRun)
            {
                string userInput = Console.ReadLine();

                if (!string.IsNullOrEmpty(userInput))
                {
                    await HandleCommand(userInput);
                }
            }
            
        }

        static async Task HandleCommand(string userInput)
        {
            string[] strs = userInput.Split(' ');
            string cmd = strs.First();

            switch (cmd)
            {
                case "quit":
                    shouldRun = false;
                    BleManager.Shared.close();
                    break;
                case "startScan":
                    BleManager.Shared.startScan();
                    break;
                case "stopScan":
                    BleManager.Shared.stopScan();
                    break;
                case "connect":
                    if (strs.Length > 1)
                    {
                        BleManager.Shared.OpenDevice(strs[1]);
                    }
                    else
                    {
                        Utilities.WriteError("Malformed connect command " + userInput);
                    }
                    break;
                case "disconnect":
                    if (strs.Length > 1)
                    {
                        BleManager.Shared.DisconnectDevice(strs[1], true);
                    }
                    else
                    {
                        Utilities.WriteError("Malformed disconnect command " + userInput);
                    }
                    break;
                case "sendBlob":
                    if (strs.Length > 2)
                    {
                        BleManager.Shared.sendBlob(strs[1], strs[2]);
                    }
                    else
                    {
                        Utilities.WriteError("Malformed sendBlob command " + userInput);
                    }
                    break;
                default:
                    Utilities.WriteError("Unhandled command: " + userInput);
                    break;
            }
        }

    }
}
