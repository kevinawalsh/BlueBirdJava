package com.birdbraintechnologies.bluebirdconnector;

import java.io.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.interfaces.DBusSigHandler;
import org.freedesktop.dbus.interfaces.ObjectManager;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

import static com.birdbraintechnologies.bluebirdconnector.Utilities.stackTraceToString;

public class LinuxBluezBLE implements RobotCommunicator {

    private static final Log LOG = Log.getLogger(LinuxBluezBLE.class);

    private static final String SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
    private static final String TX_CHAR_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
    private static final String RX_CHAR_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";

    private static final int RSSI_THRESHOLD = 20;
    private static final List<String> supportedRobotTypes = List.of("MB", "BB", "FN");

    private static final byte[] GET_VERSION_FINCH = new byte[] { (byte)0xD4 };
    private static final byte[] GET_VERSION_OTHER = new byte[] { (byte)0xCF };
    private static final byte[] POLL_START_V1 = new byte[] { 0x62, 0x67 };
    private static final byte[] POLL_START_V2 = new byte[] { 0x62, 0x70 };
    private static final byte[] POLL_STOP = new byte[] { 0x62, 0x73 };

    private final RobotManager robotManager = RobotManager.getSharedInstance();
    private final FrontendServer frontendServer = FrontendServer.getSharedInstance();

    // These are shared, accessed by any threads.
    private DBusConnection conn;
    private ObjectManager manager;
    
    // Lock and state for starting/stopping discovery.
    private ReentrantLock discoveryLock = new ReentrantLock();
    private ScheduledFuture<?> discoveryTimer;


    // Notes on concurrency:
    //  - BlueBird front-end invokes via the RobotCommunicator API. These
    //    functions do minimal work, mostly just queueing tasks for the primary
    //    worker thread.
    //  - The worker thread examines those tasks, mostly in serial order.
    //    Many jobs involve multiple tasks to be executed in sequence, so the
    //    worker puts continuations into the work queue as needed to avoid
    //    blocking.
    //  - BLE signal handlers are invoked by bluez threads. Like the front-end
    //    API handlers, these signal handler functions do minimal work, mostly
    //    just queueing notifications on the primary thread's work queue. The
    //    worker thread examines the notifications and does something with them.

    // BLERobotDevice object lifetime and state machine:
    //
    //        _______________
    //       |   no object   |
    //       |_______________|
    //             |
    //             |    InterfaceAdded signal indicating a not-yet-known path for
    //             |    a Device1 object, "/org/bluez/hci0/dev_DE_EC_24_5C_80_39".
    //        _____v______
    //       |            |  Robot is put in robotsByPath, as "/org/bluez/hci/dev_..."
    //       |   IDLE     |  and in robotsByName under name, e.g. "FNC8039"
    //       |   robot    |  but not yet in robotsByRxPath or robotsByTxPath.
    //       |____________|  Front-end is notified of robot name and signal strength.
    //       /     |
    //      /      |    Front-end requests to connect to robot.
    //     /       |    So initialize robot.device, and call device.Connect().
    //    |   _____v______
    //    |  | CONNECTING |  Waiting until both txChar and rxChar are initialized.
    //    |  |   BEGIN    |  
    //    |  |____________|    <-,
    //    |  /   |    |          |  InterfaceAdded signal to notify of a new GattCharacteristic1
    //    | /    |    '----------'  for this robot, either the txChar or rxChar.
    //    |/     |
    //    |      |   Both txChar and rxChar have been initialized for this robot.
    //    |      |   Enable rxChar notifications,
    //    |      |   put robot into robotsByTxPath and robotsByRxPath, and
    //    |      |   send a GET_VERSION command to robot.
    //    |   ___v________
    //    |  | CONNECTING |  Waiting to get version info response.
    //    |  |  GETTING   |
    //    |  |  VERSION   |  
    //    |  |____________|
    //    |  /   |
    //    | /    |   PropertiesChanged signal for txCharPath arrives with version info.
    //    |/     |   Enable notifications, and subscribe to rxChar, to get sensor data.
    //    |      |   Notify front-end this robot is connected.
    //    |   ___v________
    //    |  |            |  Front-end may now use sendCommand, and 
    //    |  | CONNECTED  |  receives regular sensor updates.
    //    |  |____________|    <-,
    //     \   |      |          | PropertiesChanged signal for rxCharPath with sensor data.
    //      \  |      '----------'
    //       \ |    
    //        \|
    //         |  If front-end requests disconnection,
    //         |  then disconnect if needed, cleanup, and reset back to IDLE status.
    //         |--------> Back to IDLE state.
    //         |
    //         |  If InterfaceRemoved signal indicates robot is gone,
    //         |  then cleanup and reset fully. Remove from all maps.
    //         '-----> Back to "no object" state.
    //
    private final LinkedBlockingDeque<Work> workQueue = new LinkedBlockingDeque<>();
    private final Worker worker = new Worker();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();



    //
    // Implementation for Front-end RobotCommunicator API
    //

    public LinuxBluezBLE() {
        LOG.info("initializing dbus/bluez bluetooth driver");

        try {
            // Connect to system-wide dbus
            conn = DBusConnectionBuilder.forSystemBus().build();

            // Get Bluez root object.
            manager = conn.getRemoteObject("org.bluez", "/", ObjectManager.class);

            // Make sure there is at least one bluetooth adapter present.
            int count = 0;
            for (var entry : manager.GetManagedObjects().entrySet()) {
                DBusPath path = entry.getKey();
                var ifaces = entry.getValue();
                if (ifaces.containsKey("org.bluez.Adapter1")) {
                    count++;
                    LOG.info("Found BLE adapter at: " + path.getPath());
                }
            }
            if (count == 0) {
                LOG.info("No BLE adapters found.");
                robotManager.updateCommunicatorStatus(false /* no adapters */, true /* but driver works */);
                return;
            }

            // Start the worker thread.
            worker.start();
                
            LOG.info("dbus/bluez bluetooth initialization finished.");

        } catch (Exception e) {
            LOG.error("Initialization Exception: {} {}", e.toString(), stackTraceToString(e));
            robotManager.updateCommunicatorStatus(false /* no adapters */, false /* driver fatally broken */);
        }
    }

    public boolean isRunning() {
        return (conn != null);
    }

    public void kill() {
        if (conn != null) {
            LOG.info("Killing linux bluez ble driver");
            worker.interrupt();
            try { worker.join(); }
            catch (InterruptedException e) { }
            conn.disconnect();
            conn = null;
        }
    }

    public void startDiscovery() {
        discoveryLock.lock();
        boolean restarting = false;
        try {
            if (discoveryTimer != null) {
                restarting = true;
                LOG.info("Restarting discovery timer.");
                discoveryTimer.cancel(false);
                discoveryTimer = null;
            } else {
                LOG.info("Starting discovery.");
            }
            int count = 0;
            try {
                for (var entry : manager.GetManagedObjects().entrySet()) {
                    DBusPath path = entry.getKey();
                    if (entry.getValue().containsKey("org.bluez.Adapter1")) {
                        LOG.info("starting discovery on adapter " + path.getPath());
                        try {
                            Adapter1 adapter = conn.getRemoteObject("org.bluez", path.getPath(), Adapter1.class);
                            adapter.StartDiscovery();
                            count++;
                        } catch (Exception e) {
                            if (!restarting)
                                LOG.error("failed to start discovery on adapter " + path.getPath() + ": " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("failed to enumerate bluetooth adapters: " + e.getMessage());
            }
            if (count > 0 || restarting) {
                // re-report to the front end all previously discovered devices
                workQueue.offer(worker.newEnumerationRequest());
                // stop discovery after a few seconds
                discoveryTimer = scheduler.schedule(() -> stopDiscovery(), 8, TimeUnit.SECONDS);
            }
            frontendServer.updateGUIScanStatus(count > 0 || restarting);
        } finally {
            discoveryLock.unlock();
        }
    }

    public void stopDiscovery() {
        discoveryLock.lock();
        try {
            if (discoveryTimer == null) {
                LOG.warn("Ignoring spurrious stopDiscovery request");
                return;
            }
            LOG.info("Stopping discovery");
            discoveryTimer.cancel(false);
            discoveryTimer = null;
            try {
                for (var entry : manager.GetManagedObjects().entrySet()) {
                    DBusPath path = entry.getKey();
                    if (entry.getValue().containsKey("org.bluez.Adapter1")) {
                        LOG.info("stopping discovery on adapter " + path.getPath());
                        try {
                            Adapter1 adapter = conn.getRemoteObject("org.bluez", path.getPath(), Adapter1.class);
                            adapter.StopDiscovery();
                        } catch (Exception e) {
                            LOG.error("failed to stop discovery on adapter " + path.getPath() + ": " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("failed to enumerate bluetooth adapters: " + e.getMessage());
            }
            frontendServer.updateGUIScanStatus(false);
        } finally {
            discoveryLock.unlock();
        }
    }

    public void requestConnection(String robotName) {
        LOG.info("Requesting connection to {}.", robotName);
        workQueue.offer(worker.newConnectionRequest(robotName));
    }

    public void requestDisconnect(String robotName) {
        LOG.info("Requesting disconnection from {}.", robotName);
        workQueue.offer(worker.newDisconnectRequest(robotName));
    }

    public void sendCommand(String robotName, byte[] command) {
        LOG.info("Sending command to {}: {}", robotName, Utilities.bytesToString(command));
        workQueue.offer(worker.newSendRequest(robotName, command));
    }


    //
    // Implementation for Worker
    //

    private static class BLERobotDevice {
        String path; // example: "/org/bluez/hci0/dev_DE_EC_24_5C_80_39"
        String addr; // example: "DE:EC:24:5C:80:39"
        String name; // example: "FNC8039"
        Short rssi;  // 0 is strongest, more negative is weaker
        int version; // 0 is unknown, 1 or 2 are valid known micro:bit version numbers 
        Object status = IDLE;
        ScheduledFuture<?> connectionTimer;
        Device1 device;
        GattCharacteristic1 txChar, rxChar;
        String txCharPath, rxCharPath;
        public BLERobotDevice(String p, String a, String n, Short r) {
            path = p;
            addr = a;
            name = n;
            rssi = r;
        }
        public void send(byte[] cmd) throws DBusException {
            LOG.debug("sending to {}: cmd={}", name, Utilities.bytesToString(cmd));
            txChar.WriteValue(toByteList(cmd), Map.of());
        }
        public boolean checkedSend(byte[] cmd) {
            try {
                send(cmd);
                return true;
            } catch (Exception e) {
                LOG.error("error writing to " + path + ": cmd=" + Utilities.bytesToString(cmd));
                return false;
            }
        }
        public void reportTo(FrontendServer frontendServer) {
            if (status != IDLE) {
                return; // filter out robots already connected (or in progress)
            } else if (rssi == null) {
                LOG.info("Robot {} has undefined RSSI, probably too distant or turned off. Ignoring.", name);
                return; // ignore phantom, distant robots
            } else {
                LOG.info("Robot {} has RSSI level {}", name, rssi);
            }
            JsonObject scanResponse = JsonParser.parseString("{'packetType': 'discovery', 'name': "+ name +", 'rssi': "+ rssi +"}").getAsJsonObject();
            LOG.debug("scan: {}", scanResponse.toString());
            frontendServer.receiveScanResponse(name, scanResponse);
        }
    }

    private static final Object IDLE = "idle device";
    private static final Object CONNECTING_BEGIN = "attempting to connect";
    private static final Object CONNECTING_GETTING_VERSION = "getting version information";
    private static final Object CONNECTING_CANCELLED = "cancelling connection attempt";
    private static final Object CONNECTED = "connected";
    private static final Object DISCONNECTING = "disconnecting";

    private class Worker extends Thread {

        private HashMap<String, BLERobotDevice> robotsByName = new HashMap<>();
        private HashMap<String, BLERobotDevice> robotsByPath = new HashMap<>();
        private HashMap<String, BLERobotDevice> robotsByTxPath = new HashMap<>();
        private HashMap<String, BLERobotDevice> robotsByRxPath = new HashMap<>();

        public void run() {
            try {

                // Set Bluez handler, used during device discovery and connection
                conn.addSigHandler(ObjectManager.InterfacesAdded.class,
                        (sig) ->
                            workQueue.offer(
                            new Work("dbus device detection", sig.getSignalSource().getPath(),
                                () -> bluetoothIfaceAdded(true, sig.getSignalSource().getPath(), sig.getInterfaces()))));

                // Set Bluez handler, used for removed devices
                conn.addSigHandler(ObjectManager.InterfacesRemoved.class,
                        (sig) -> deviceRemoved(sig.getPath(), sig.getInterfaces()));

                // Set Bluez handler, used for reading version info and sensor data
                conn.addSigHandler(Properties.PropertiesChanged.class,
                    (sig) -> workQueue.offer(
                        new Work("dbus data received", sig.getPath(),
                            () -> bluetoothValueChanged(sig.getPath(), sig.getPropertiesChanged()))));

                // Enumerate existing Bluetooth devices
                for (var entry : manager.GetManagedObjects().entrySet())
                    bluetoothIfaceAdded(false, entry.getKey().getPath(), entry.getValue());

                // Main work loop
                while (!Thread.currentThread().isInterrupted()) {
                    Work work = workQueue.take();
                    work.task.run(work);
                }

                // Disconnect from all robots
                disconnectAll();

            } catch (InterruptedException e) {
                LOG.info("worker was interrupted.");
                disconnectAll();
            } catch (Exception e) {
                LOG.error("Worker Exception: {} {}", e.toString(), stackTraceToString(e));
                robotManager.updateCommunicatorStatus(false, true); // will force kill and restart
            }
            LOG.info("worker shutting down.");
        }

        private void bluetoothIfaceAdded(boolean viaCallback, String path, Map<String, Map<String, Variant<?>>> ifaces) {
            LOG.info("interfaces added for {} {}", path, viaCallback ? "via callback" : "during initial enumeration");

            for (var entry : ifaces.entrySet()) {
                LOG.debug("  iface {} contents: ", entry.getKey());
                Map<String, Variant<?>> p = entry.getValue();
                for (var e2 : p.entrySet())
                    LOG.debug("    key: {}   value: {}", e2.getKey(), e2.getValue().getValue().toString());
            }
            Map<String, Variant<?>> props;
            // Check if this is a BirdBrain device, e.g. during discovery
            props = ifaces.get("org.bluez.Device1");
            if (props != null)
                bluetoothDeviceAdded(path, props);
            // Check if this is a tx or notify channel for a known device, e.g. during connection
            props = ifaces.get("org.bluez.GattCharacteristic1");
            if (props != null)
                bluetoothGattAdded(path, props);
        }

        private void bluetoothDeviceAdded(String path, Map<String, Variant<?>> props) {
            String addr = props.containsKey("Address") ? props.get("Address").getValue().toString() : "(unknown)";
            String name = props.containsKey("Name") ? props.get("Name").getValue().toString() : "(no name)";
            LOG.info("Device detected: " + name + " [" + addr + "] at " + path);
            String prefix = name.substring(0, 2);
            if (!supportedRobotTypes.contains(prefix))
                return;
            if (!containsIgnoreCase(props.get("UUIDs").getValue(), SERVICE_UUID))
                return;
            LOG.info("Device offers BirdBrain services: " + name);
            for (var entry : props.entrySet())
                LOG.debug("  key: {}   value: {}", entry.getKey(), entry.getValue().getValue().toString());
            Short rssi = null;
            if (props.containsKey("RSSI")) {
                rssi = (Short)props.get("RSSI").getValue();
                LOG.info("RSSI: {} {}", rssi, (rssi > -75) ? "(strong signal)" : "(weak signal)");
            } else {
                // FIXME: Maybe query dbus to see if RSSI property can be found?
                // Currently, we get the RSSI on the next PropertiesChanged signal.
                LOG.info("RSSI: unknown");
            }
            // Add to list, if needed
            boolean changed = false;
            BLERobotDevice robot = robotsByPath.get(path);
            if (robot == null) {
                robot = new BLERobotDevice(path, addr, name, rssi);
                robotsByPath.put(path, robot);
                robotsByName.put(name, robot); // FIXME: potential name collision?
                changed = true;
            } else if (!robot.name.equals(name)) {
                robotsByName.remove(robot.name);
                robot.name = name;
                robotsByName.put(name, robot); // FIXME: potential name collision?
                changed = true;
            } else if (rssi != null && (robot.rssi == null || Math.abs(rssi - robot.rssi) > RSSI_THRESHOLD)) {
                robot.rssi = rssi;
                changed = true;
            }
            // Notify front end
            if (changed)
                robot.reportTo(frontendServer);
        }

        private void bluetoothGattAdded(String path, Map<String, Variant<?>> props) {
            Variant<?> uuidVar = props.get("UUID");
            Variant<?> serviceVar = props.get("Service");
            if (uuidVar == null || serviceVar == null)
                return;
            String uuid = uuidVar.getValue().toString();
            String service = ((DBusPath)serviceVar.getValue()).getPath();
            BLERobotDevice robot = robotByService(service);
            LOG.debug("GATT update for Robot {}, UUID is {}", robot != null ? robot.name : "<NULL>", uuid);
            updateGatt(path, robot, uuid);
        }

        private void updateGatt(String path, BLERobotDevice robot, String uuid) {
            if (robot == null || robot.status != CONNECTING_BEGIN)
                return;
            try {
                if (uuid.equalsIgnoreCase(TX_CHAR_UUID) && robot.txChar == null) {
                    robot.txChar = conn.getRemoteObject("org.bluez", path, GattCharacteristic1.class);
                    if (robot.txChar == null)
                        LOG.error("failed to get tx characteristic for " + path);
                    robot.txCharPath = path;
                    robotsByTxPath.put(path, robot);
                    LOG.debug("Found txChar for {}: {}", robot.name, path);
                } else if (uuid.equalsIgnoreCase(RX_CHAR_UUID) && robot.rxChar == null) {
                    robot.rxChar = conn.getRemoteObject("org.bluez", path, GattCharacteristic1.class);
                    if (robot.rxChar == null)
                        LOG.error("failed to get rx characteristic for " + path);
                    robot.rxCharPath = path;
                    robotsByRxPath.put(path, robot);
                    LOG.debug("Found rxChar for {}: {}", robot.name, path);
                } else {
                    LOG.debug("Unrecognized GATT characteristic for {}, UUID {}", robot.name, uuid);
                }
            } catch (Exception e) {
                LOG.error("failed to get gatt characteristic for " + path + ": " + e.getMessage());
            }
            try {
                if (robot.txChar != null && robot.rxChar != null) {
                    LOG.info("Enabling notifications and getting version info for {}", robot.name);
                    robot.status = CONNECTING_GETTING_VERSION;
                    robot.rxChar.StartNotify();
                    robot.checkedSend(robot.name.startsWith("FN") ? GET_VERSION_FINCH : GET_VERSION_OTHER);
                }
            } catch (Exception e) {
                LOG.error("failed to enable notifications or get version for " + robot.name);
            }
        }

        private void bluetoothValueChanged(String path, Map<String, Variant<?>> props) {
            LOG.debug("Properties of {} changed...", path);
            for (var entry : props.entrySet())
                LOG.debug("  key: {}   value: {}", entry.getKey(), entry.getValue().getValue().toString());
            if (props.containsKey("Value")) {
                Object val = props.get("Value").getValue();
                if (!(val instanceof List)) {
                    LOG.error("BLE device {} sent unexpected Value type {}", path, val);
                    return;
                }
                @SuppressWarnings("unchecked")
                byte[] value = toByteArray((List<Byte>)val);
                LOG.debug("Received from " + path + " : " + Utilities.bytesToString(value));
                BLERobotDevice robot;
                robot = robotsByTxPath.get(path);
                if (robot != null) {
                    bluetoothTxResponse(robot, value);
                    return;
                }
                robot = robotsByRxPath.get(path);
                if (robot != null) {
                    bluetoothRxResponse(robot, value);
                    return;
                }
            } else if (props.containsKey("RSSI")) {
                Object val = props.get("RSSI").getValue();
                if (val == null)
                    return;
                if (!(val instanceof Short)) {
                    LOG.error("BLE device {} sent unexpected RSSI type {}", path, val.getClass());
                    return;
                }
                Short rssi = (Short)val;
                BLERobotDevice robot;
                robot = robotsByPath.get(path);
                if (robot != null && rssi != null && (robot.rssi == null || Math.abs(rssi - robot.rssi) > RSSI_THRESHOLD)) {
                    robot.rssi = rssi;
                    robot.reportTo(frontendServer);
                }
            } else if (props.containsKey("Connected")) {
                Object val = props.get("Connected").getValue();
                if (val == null)
                    return;
                if (!(val instanceof Boolean)) {
                    LOG.error("BLE device {} sent unexpected Connected status type {}", path, val.getClass());
                    return;
                }
                boolean connected = (Boolean)val;
                BLERobotDevice robot = robotsByPath.get(path);
                if (robot != null && !connected && robot.status != DISCONNECTING && robot.status != IDLE) {
                    LOG.info("Device disconnected: " + path);
                    workQueue.removeIf((work) -> work.path.equals(path) || work.path.startsWith(path + "/"));
                    disconnect(robot, false);
                }
            }
        }

        private void bluetoothTxResponse(BLERobotDevice robot, byte[] value) {
            LOG.warn("got unexpected tx channel data from " + robot.txCharPath);
        }

        private void bluetoothRxResponse(BLERobotDevice robot, byte[] value) {
            LOG.debug("rx response");
            if (value.length >= 4 && robot.status == CONNECTING_GETTING_VERSION) {
                // Response for GET_VERSION command.
                // Example response data: { 0x2, 0x2, 0x44, 0x22 }
                // where 0x44 means "Finch", 0xFF would mean "MB" (micro:bit?),
                // and 0x03 would mean "Hummingbird".
                // The last byte, 0x22, means version 2 micro:bit.
                robot.version = (value[3] == 0x22 ? 2 : 1);
                try {
                    robot.send(robot.version == 2 ? POLL_START_V2 : POLL_START_V1);
                    robot.status = CONNECTED;
                    if (robot.connectionTimer != null) {
                        robot.connectionTimer.cancel(false);
                        robot.connectionTimer = null;
                    }
                    robotManager.receiveConnectionEvent(robot.name, (robot.version == 2));
                } catch (Exception e) {
                    LOG.error("can't start polling for " + robot.name);
                }
            } else if (value.length > 10 && robot.status == CONNECTED) {
                // Sensor data.
                robotManager.receiveNotification(robot.name, value);
            } else {
                LOG.warn("got unexpected data from " + robot.rxCharPath);
            }
        }

        private void deviceRemoved(String path, List<String> ifaces) {
            LOG.info("Device removed: " + path);
            for (String iface: ifaces)
                LOG.info("with iface: " + iface);
            if (!ifaces.contains("org.bluez.Device1"))
                return;
            workQueue.removeIf((work) -> work.path.equals(path) || work.path.startsWith(path + "/"));
            workQueue.offerFirst(new Work("dbus device removal", path, 
                        () -> {
                            BLERobotDevice robot = robotsByPath.remove(path);
                            if (robot != null) {
                                disconnect(robot, false);
                                robotsByName.remove(robot.name);
                            }
            }));
        }
        
        public Work newEnumerationRequest() {
            return new Work("user enumeration request", "", () -> {
                // re-report previously known devices to front end
                for (BLERobotDevice robot : robotsByPath.values())
                    robot.reportTo(frontendServer);
            });
        }
       
        public Work newConnectionRequest(String robotName) {
            return new Work("user connection request", robotName, () -> beginConnection(robotName));
        }

        private void beginConnection(String robotName) {
            LOG.info("connecting to " + robotName);
            BLERobotDevice robot = robotsByName.get(robotName);
            if (robot == null) {
                LOG.error("can't find info for " + robotName);
                return;
            }
            if (robot.status != IDLE) {
                LOG.error("Device " + robotName + " is busy.");
                return;
            }
            LOG.info("robot {} is currently IDLE, path is {}", robotName, robot.path);
            try {
                Device1 device = conn.getRemoteObject("org.bluez", robot.path, Device1.class);
                if (device == null) {
                    LOG.error("can't find " + robot.path);
                    return;
                }
                device.Connect();
                robot.device = device;
                robot.status = CONNECTING_BEGIN;
                // query dbus to get rxChar and txChar, if already present,
                // otherwise we get them from InterfacesAdded signals
                for (var entry : manager.GetManagedObjects().entrySet()) {
                    String path = entry.getKey().getPath();
                    if (!path.startsWith(robot.path + "/"))
                        continue;
                    LOG.info("enumerated relevant item: {}", path);
                    var ifaces = entry.getValue();
                    if (ifaces.containsKey("org.bluez.GattCharacteristic1")) {
                        Map<String, Variant<?>> props = ifaces.get("org.bluez.GattCharacteristic1");
                        String uuid = (String) props.get("UUID").getValue();
                        LOG.debug("item is a Gatt characteristic, UUID={}", uuid);
                        updateGatt(path, robot, uuid);
                    }
                }
                // set a timer in case connection drops out
                if (robot.status != CONNECTED && robot.status != DISCONNECTING) {
                    robot.connectionTimer = scheduler.schedule(() -> {
                        LOG.error("Connection timeout...");
                        requestDisconnect(robot.name);
                    }, 3, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                LOG.error("can't connect to " + robot.path + ": " + e.getMessage());
                disconnect(robot, true /*false*/); // userInitiated=true so frontend doesn't try to reconnect immediately
            }
        }

        public Work newDisconnectRequest(String robotName) {
            return new Work("user disconnect request", robotName, () -> beginDisconnect(robotName));
        }

        private void beginDisconnect(String robotName) {
            LOG.info("disconnecting from " + robotName);
            BLERobotDevice robot = robotsByName.get(robotName);
            if (robot == null) {
                LOG.error("can't find info for " + robotName);
                return;
            }
            disconnect(robot, true);
        }

        private void disconnect(BLERobotDevice robot, boolean userInitiated) {
            LOG.info("Disconnecting from {} robot {}, userInitiated={}.", robot.status, robot.name, userInitiated);
            Object prevStatus = robot.status;
            robot.status = DISCONNECTING;
            if (prevStatus == DISCONNECTING) {
                LOG.error("reentrant disconnect");
                return;
            }
            if (prevStatus == CONNECTED) {
                robot.checkedSend(POLL_STOP);
                try {
                    robot.rxChar.StopNotify();
                } catch (Exception e) {
                    LOG.error("can't unsubscribe from " + robot.rxCharPath);
                }
            }
            if (robot.connectionTimer != null) {
                robot.connectionTimer.cancel(false);
                robot.connectionTimer = null;
            }
            if (robot.txCharPath != null) {
                robotsByTxPath.remove(robot.txCharPath);
                robot.txCharPath = null;
                robot.txChar = null;
            }
            if (robot.rxCharPath != null) {
                robotsByRxPath.remove(robot.rxCharPath);
                robot.rxCharPath = null;
                robot.rxChar = null;
            }
            robot.version = 0; // unknown
            if (robot.device != null) {
                try {
                    robot.device.Disconnect();
                }
                catch (Exception e) {
                    LOG.error("can't disconect from " + robot.path);
                }
                robot.device = null;
            }
            robot.status = IDLE;
            workQueue.removeIf((work) -> work.path.equals(robot.name));
            if (prevStatus != IDLE && prevStatus != DISCONNECTING)
                robotManager.receiveDisconnectionEvent(robot.name, userInitiated);
            if (userInitiated)
                robot.reportTo(frontendServer); // re-populate available robot list
        }

        private void disconnectAll() {
            for (BLERobotDevice robot : robotsByPath.values())
                disconnect(robot, true); // userInitiated=true to avoid reconnection attempt
        }

        public Work newSendRequest(String robotName, byte[] command) {
            return new Work("user command", robotName, () -> send(robotName, command));
        }

        private void send(String robotName, byte[] command) {
            LOG.debug("sending to " + robotName);
            BLERobotDevice robot = robotsByName.get(robotName);
            if (robot == null) {
                LOG.error("can't find info for " + robotName);
                return;
            }
            if (robot.status != CONNECTED) {
                LOG.error("can't send user command, robot is not yet connected");
                return;
            }
            robot.checkedSend(command);
        }

        private BLERobotDevice robotByService(String service) {
            // Note: service is a path like
            //    "/org/bluez/hci2/dev_DE_EC_24_5C_80_39/service000a"
            // or "/org/bluez/hci2/dev_DE_EC_24_5C_80_39/service000a/char000b"
            // and the keys in the robotsByPath hashmap are like
            //    "/org/bluez/hci2/dev_DE_EC_24_5C_80_39"
            
            // First try exact match
            BLERobotDevice robot = robotsByPath.get(service);
            if (robot != null)
                return robot;

            // Try each prefix of service path
            int slash = service.length();
            while ((slash = service.lastIndexOf('/', slash - 1)) > 0) {
                String parent = service.substring(0, slash);
                robot = robotsByPath.get(parent);
                if (robot != null)
                    return robot;
            }

            return null;
        }
        
    }


    //
    // Helpers
    //
    
    private boolean containsIgnoreCase(Object list, String target) {
        if (!(list instanceof List))
            return false;
        @SuppressWarnings("unchecked")
        List<String> strings = (List<String>)list;
        return containsIgnoreCase(strings, target);
    }
    
    private boolean containsIgnoreCase(List<String> list, String target) {
        for (String s : list)
            if (s.equalsIgnoreCase(target))
                return true;
        return false;
    }

    @FunctionalInterface
    private interface WorkTask {
        void run(Work work) throws IOException, DBusException, InterruptedException;
    }

    private static class Work {
        public String desc; // description of the work to be done
        public String path; // dbus device path, or robot name, this work applies to
        public WorkTask task; // task to execute
        public Work(String d, String p) { desc = d; path = p; }
        public Work(String d, String p, Runnable r) { desc = d; path = p; task = (work) -> r.run(); }
        public Work(String d, String p, WorkTask t) { desc = d; path = p; task = t; }
    }

    private static byte[] toByteArray(List<Byte> list) {
        byte[] array = new byte[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    public static List<Byte> toByteList(byte[] array) {
        List<Byte> list = new ArrayList<>(array.length);
        for (byte b : array) {
            list.add(b);
        }
        return list;
    }


    //
    // Bluez DBus Interfacces
    //
    
    // @DBusInterfaceName("org.freedesktop.DBus.ObjectManager")
    // public interface ObjectManager extends DBusInterface {
    //     Map<DBusPath, Map<String, Map<String, Variant<?>>>> GetManagedObjects();
    // }

    @DBusInterfaceName("org.bluez.Adapter1")
    public interface Adapter1 extends DBusInterface {
        void StartDiscovery();
        void StopDiscovery();
    }

    @DBusInterfaceName("org.bluez.Device1")
    public interface Device1 extends DBusInterface {
        void Connect();
        void Disconnect();
    }

    @DBusInterfaceName("org.bluez.GattCharacteristic1")
    public interface GattCharacteristic1 extends DBusInterface {
        void WriteValue(List<Byte> value, Map<String, Variant<?>> options);
        void StartNotify();
        void StopNotify();
    }


}
