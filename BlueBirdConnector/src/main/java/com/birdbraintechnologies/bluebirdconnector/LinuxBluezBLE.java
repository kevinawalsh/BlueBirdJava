package com.birdbraintechnologies.bluebirdconnector;

import java.io.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
import static com.birdbraintechnologies.bluebirdconnector.LinuxBluezBLE.Status.*;

public class LinuxBluezBLE implements RobotCommunicator {

    private static final Log LOG = Log.getLogger(LinuxBluezBLE.class);

    private static final String SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
    private static final String TX_CHAR_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
    private static final String RX_CHAR_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";

    private static final int RSSI_CHANGE_THRESHOLD = 10; // ignore small changes in strength
    private static final int RSSI_LEVEL_THRESHOLD = -90; // below this, BLE is unreliable
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

    // BLERobotDevice object example lifetime and state machine:
    //
    //      _______________
    //     |   no object   |
    //     |      yet      |
    //     |_______________|
    //             |
    //             |    InterfacesAdded signal indicating a not-yet-known path for
    //             |    a Device1 object, "/org/bluez/hci0/dev_DE_EC_24_5C_80_39".
    //             |
    //             |    Robot is put in robotsByPath, as "/org/bluez/hci/dev_..."
    //             |    and in robotsByName under name, e.g. "FNC8039"
    //             |    but not yet in robotsByRxPath.
    //             |
    //            <?>   Is RSSI reasonable? Note: RSSI=null occurs for device that was paired
    //           /   \       and cached, but isn't currently in range, or perhaps not even on.
    //       yes/     \no       _____________
    //         /       \       | UNAVAILABLE | Not shown in UI, just waiting for
    //         |        `------>   robot     | more reasonable signal strength.
    //         |               |_____________|
    //         |        _________.'     
    //         |       |         PropertiesChanged, or InterfacesAdded again,
    //         |       |         with a reasonable RSSI.
    //        _v_______v__
    //       |            |   Front-end is notified of robot name and signal strength.   
    //       | AVAILABLE  |   and robot is shown in UI. RSSI is reasonable.
    //       |   robot    |<--.  
    //       |____________|   | RSSI changes, but still okay, update frontend
    //         /   |  | '-----'
    //        /    |  '-------> RSSI drops too low, remove from frontend, back to UNAVAILABLE
    //       /     |
    //      /      |    Front-end requests to connect to robot.
    //     /       |    So initialize robot.device, and call device.Connect().
    //    |   _____v______
    //    |  | CONNECTING |  Waiting until both txChar and rxChar are initialized.
    //    |  |   BEGIN    |<-.
    //    |  |____________|  | InterfacesAdded signal to notify of a new GattCharacteristic1
    //    |  /   |    '------' for this robot, either the txChar or rxChar.
    //    | /    |
    //    |/     |   Both txChar and rxChar have been initialized for this robot.
    //    |      |   Enable rxChar notifications,
    //    |      |   put robot into robotsByRxPath, and
    //    |      |   send a GET_VERSION command to robot.
    //    |   ___v________
    //    |  | CONNECTING |  Waiting to get version info response.
    //    |  |   PROBE    |  
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
    //         |  then disconnect and tell manager to remove from "connected/pending" list.
    //         |--------> Back to UNAVAILABLE or AVAILABLE state, depending on RSSI.
    //         |
    //         |  If BLE callback indicates device is no longer connected,
    //         |  then tell manager to remove from "connected/pending" list.
    //         |-----> Back to UNAVAILABLE or AVAILABLE, depending on RSSI.
    //         |
    //         |  If BLE signal indicates robot is gone,
    //         |  then cleanup and reset fully. Remove from all maps.
    //         '-----> Robot becomes DEAD.
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
        if (conn == null)
            return;
        LOG.info("Killing linux bluez ble driver");
        worker.interrupt();
        try { worker.join(); }
        catch (InterruptedException e) { }
        conn.disconnect();
        conn = null;
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
                // re-report to the front end all previously discovered and still available devices
                workQueue.offer(worker.newEnumerationRequest());
                // timer to stop discovery after a few seconds
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
        // copy cmd, so caller doesn't modify it before workQueue is processed
        final byte cmd[] = Arrays.copyOf(command, command.length);
        LOG.info("Sending command to {}: {}", robotName, Utilities.bytesToString(cmd));
        workQueue.offer(worker.newSendRequest(robotName, cmd));
    }


    //
    // Implementation for BLERobotDevice
    //
   
    enum Status {
     UNAVAILABLE, AVAILABLE,
     CONNECTING_BEGIN, CONNECTING_PROBE, CONNECTED,
     DISCONNECTING,
     DEAD
    };

    private static class BLERobotDevice {
        String path; // example: "/org/bluez/hci0/dev_DE_EC_24_5C_80_39"
        String addr; // example: "DE:EC:24:5C:80:39"
        String name; // example: "FNC8039"
        Short rssi;  // 0 is strongest, more negative is weaker, null is unknown
        int version; // 0 is unknown, 1 or 2 are valid known micro:bit version numbers 
        Status status;
        ScheduledFuture<?> connectionTimer;
        Device1 device;
        GattCharacteristic1 txChar, rxChar;
        String txCharPath, rxCharPath;
        public BLERobotDevice(String p, String a, String n) {
            path = p;
            addr = a;
            name = n;
            rssi = null;
            status = UNAVAILABLE;
        }
        public void dump() {
            LOG.debug("=== Robot ===");
            LOG.debug("  path: {}", path);
            LOG.debug("  addr: {}", addr);
            LOG.debug("  name: {}", name);
            LOG.debug("  rssi: {}", rssi);
            LOG.debug("  version: {}", version);
            LOG.debug("  status: {}", status);
            LOG.debug("  device: {}", (device == null ? "-" : "non-null"));
            LOG.debug("  txChar: {}", txChar == null ? "-" : txCharPath);
            LOG.debug("  rxChar: {}", rxChar == null ? "-" : rxCharPath);
        }
        public boolean owns(Work work) {
            return work.path.equals(name) || work.path.equals(path) || work.path.startsWith(path + "/");
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
            JsonObject scanResponse = JsonParser.parseString("{'packetType': 'discovery', 'name': "+ name +", 'rssi': "+ rssi +"}").getAsJsonObject();
            LOG.debug("scan: {}", scanResponse.toString());
            frontendServer.receiveScanResponse(name, scanResponse);
        }
    }


    //
    // Implementation for Worker
    //

    private class Worker extends Thread {

        private HashMap<String, BLERobotDevice> robotsByName = new HashMap<>();
        private HashMap<String, BLERobotDevice> robotsByPath = new HashMap<>();
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
                        (sig) -> async_deviceRemoved(sig.getSignalSource().getPath(), sig.getInterfaces()));

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
                LOG.info("worker shutting down.");
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
            // LOG.debug("Device interfaces added for {} {}", path, viaCallback ? "via callback" : "during initial enumeration");
            // for (var entry : ifaces.entrySet()) {
            //     LOG.debug("  iface {} contents: ", entry.getKey());
            //     Map<String, Variant<?>> p = entry.getValue();
            //     for (var e2 : p.entrySet())
            //         LOG.debug("    key: {}   value: {}", e2.getKey(), e2.getValue().getValue().toString());
            // }
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
            // LOG.debug("Device detected: " + name + " [" + addr + "] at " + path);
            String prefix = name.substring(0, 2);
            if (!supportedRobotTypes.contains(prefix))
                return;
            if (!containsIgnoreCase(props.get("UUIDs").getValue(), SERVICE_UUID))
                return;
            LOG.info("Detected device offering BirdBrain services: {} [{}]", name, path);
            for (var entry : props.entrySet())
                LOG.debug("  key: {}   value: {}", entry.getKey(), entry.getValue().getValue().toString());
            Short rssi = null;
            if (props.containsKey("RSSI")) {
                rssi = (Short)props.get("RSSI").getValue();
            } else {
                // TODO: Maybe query dbus to see if RSSI property can be found?
                // Currently, we get the RSSI on the next PropertiesChanged signal.
                LOG.info("RSSI: unknown");
            }
            Boolean connected = null;
            if (props.containsKey("Connected")) {
                connected = (Boolean)props.get("Connected").getValue();
                LOG.info("Already Connected: " + connected);
            } else {
                LOG.info("Already Connected: unknown");
            }

            BLERobotDevice robot = robotsByPath.get(path);
            if (robot == null) {
                LOG.info("New robot: name={} path={}", name, path);
                // Never-before-seen device, this is the happy path.
                name = makeUnique(robotsByName, name, path);
                robot = new BLERobotDevice(path, addr, name);
                robotsByPath.put(path, robot);
                robotsByName.put(name, robot);
                // If BLE is already connected... perhaps BlueBirdConnector was connected then
                // killed abruptly without doing a BLE disconnection. Let's just kill the connection
                // and hope for the best. Conceivably, we could try to recover and reconnect, but
                // let's not.
                cleanupConnection(connected, robot.path, robot.status);
                updateRSSI(robot, rssi);
                return;
            }

            // Known device, verify name first
            LOG.warn("New device notification, but robot info was already present.");
            robot.dump();
            if (!robot.name.equals(name)) {
                // Name change: Robot was registered under some other name previously???
                LOG.error("DANGER: ROBOT HAS CHANGED NAME??? This code is untested.");
                disconnect(robot, UNAVAILABLE);
                robotsByName.remove(robot.name);
                robot.name = name = makeUnique(robotsByName, name, path);
                robotsByName.put(name, robot);
                updateRSSI(robot, rssi);
                return;
            }

            updateRobot(robot, rssi, connected);
        }
        
        private void updateRSSI(BLERobotDevice robot, Short rssi) {
            LOG.info("RSSI for robot {}: {} {}", robot.name, rssi,
                    (rssi == null) ? "(not currently known)" :
                    (rssi < RSSI_LEVEL_THRESHOLD) ? "(unreliable)" :
                    (rssi < -75) ? "(weak)" :
                    (rssi < -60) ? "(okay)" :
                    "(good)");
            switch (robot.status) {
                case UNAVAILABLE:
                    robot.rssi = rssi;
                    if (rssi != null && rssi >= RSSI_LEVEL_THRESHOLD) {
                        robot.status = AVAILABLE;
                        robot.reportTo(frontendServer);
                    }
                    break;
                case AVAILABLE:
                    if (rssi != null && Math.abs(rssi - robot.rssi) >= RSSI_CHANGE_THRESHOLD) {
                        robot.rssi = rssi;
                        if (rssi >= RSSI_LEVEL_THRESHOLD) {
                            robot.reportTo(frontendServer); // this is only to update RSSI
                        } else {
                            robot.status = UNAVAILABLE;
                            frontendServer.removeScanResponse(robot.name);
                        }
                    }
                    break;
                case CONNECTED:
                case CONNECTING_BEGIN:
                case CONNECTING_PROBE:
                    if (rssi != null && Math.abs(rssi - robot.rssi) >= RSSI_CHANGE_THRESHOLD) {
                        robot.rssi = rssi;
                        if (rssi >= RSSI_LEVEL_THRESHOLD) {
                            // rssi changes in UI not yet implemented for this case ...
                        } else {
                            // TODO: consider preemptive disconnection here
                            LOG.info("Robot {} has unreliable signal, should perhaps be disconnected preemptively?");
                        }
                    }
                    break;
                default:
                    // ignore RSSI changes for robots that are DEAD, etc.
                    break;
            }
        }
        
        private void updateRobot(BLERobotDevice robot, Short rssi, Boolean connected) {
            LOG.info("update {} with {} {}", robot.name, rssi, connected);
            switch (robot.status) {
                case UNAVAILABLE:
                    // We knew of device, perhaps we enumerated it just as it was being created. Earlier it must have had
                    // unacceptable RSSI, maybe that has changed now.
                    cleanupConnection(connected, robot.path, robot.status);
                    updateRSSI(robot, rssi);
                    break;

                case AVAILABLE:
                    LOG.warn("WARNING: Previously available device was updated, or discovered again.");
                    cleanupConnection(connected, robot.path, robot.status);
                    updateRSSI(robot, rssi);
                    break;

                case CONNECTING_BEGIN:
                case CONNECTING_PROBE:
                case CONNECTED:
                    // Should be connected. Ignore RSSI changes here, as there is no mechanism to notify the frontend
                    // about RSSI for connected (and thus not "available") devices.
                    if (connected != null && !((boolean)connected)) {
                        LOG.info("Device was connected, or connecting, but appears to have failed?");
                        disconnect(robot, UNAVAILABLE);
                    }
                    updateRSSI(robot, rssi);
                    break;
                
                case DISCONNECTING:
                    // Should not happen... it is straight code from DISCONNECTING to UNAVAILABLE
                    // so there should be no BLE event handling between them.
                    LOG.error("Unexpected state, got BLE event while disconnecting");
                    break;

                default:
                    LOG.error("Unknown robot status: {}", robot.status);
                    break;
            }
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
            // See if we just learned txChar or rxChar
            try {
                if (uuid.equalsIgnoreCase(TX_CHAR_UUID) && robot.txChar == null) {
                    robot.txChar = conn.getRemoteObject("org.bluez", path, GattCharacteristic1.class);
                    if (robot.txChar == null)
                        LOG.error("failed to get tx characteristic for " + path);
                    robot.txCharPath = path;
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
                // let connection timeout handle the cleanup
            }
            // See if we are ready to transition to CONNECTED_GETTING_VERSION
            try {
                if (robot.txChar != null && robot.rxChar != null) {
                    LOG.info("Enabling notifications and getting version info for {}", robot.name);
                    robot.status = CONNECTING_PROBE;
                    robot.rxChar.StartNotify();
                    robot.checkedSend(robot.name.startsWith("FN") ? GET_VERSION_FINCH : GET_VERSION_OTHER);
                }
            } catch (Exception e) {
                LOG.error("failed to enable notifications or get version for " + robot.name);
                // let connection timeout handle the cleanup
            }
        }

        private void bluetoothValueChanged(String path, Map<String, Variant<?>> props) {
            // Filter early for robot to avoid cluttering the logs
            BLERobotDevice robot;
            if ((robot = robotsByRxPath.get(path)) != null) {

                LOG.debug("Properties of {} changed (rxChar path match)...", robot.name);
                for (var entry : props.entrySet())
                    LOG.debug("  key: {}   value: {}", entry.getKey(), entry.getValue().getValue().toString());

                // We only care about Value changes, object should be a List of bytes
                List val = getProp(props, "Value", List.class);
                if (val == null)
                    return;
                @SuppressWarnings("unchecked")
                byte[] value = toByteArray((List<Byte>)val);
                LOG.debug("Robot {} received data from {}: {}", robot.name, path, Utilities.bytesToString(value));
                bluetoothRxResponse(robot, value);
            } else if ((robot = robotsByPath.get(path)) != null) {

                LOG.debug("Properties of {} changed (device path match)...", robot.name);
                for (var entry : props.entrySet())
                    LOG.debug("  key: {}   value: {}", entry.getKey(), entry.getValue().getValue().toString());
                
                // Check for connected status and RSSI level changes
                Boolean connected = getProp(props, "Connected", Boolean.class);
                Short rssi = getProp(props, "RSSI", Short.class);
                if (connected != null || rssi != null)
                    updateRobot(robot, rssi, connected);
            }
        }

        private void bluetoothRxResponse(BLERobotDevice robot, byte[] value) {
            if (value.length >= 4 && robot.status == CONNECTING_PROBE) {
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

        // Note: this gets called directly from BLE async signal handler
        private void async_deviceRemoved(String path, List<String> ifaces) {
            if (!ifaces.contains("org.bluez.Device1"))
                return;
            workQueue.removeIf((work) -> work.path.equals(path) || work.path.startsWith(path + "/"));
            workQueue.offerFirst(new Work("dbus device removal", path, 
                        () -> {
                            BLERobotDevice robot = robotsByPath.get(path);
                            if (robot != null) {
                                LOG.info("Robot {} BLE device removed from path {}", robot.name, path);
                                // for (String iface: ifaces)
                                //     LOG.info("with iface: " + iface);
                                disconnect(robot, DEAD);
                            }
                        }));
        }

        private boolean revalidateDevice(BLERobotDevice robot) {
            try {
                if (robotsByName.get(robot.name) != robot) {
                    LOG.error("robot {} is no longer present in table", robot.name);
                    return false;
                }
                Device1 device = conn.getRemoteObject("org.bluez", robot.path, Device1.class);
                if (device == null) {
                    LOG.error("robot {} is no longer present at {}", robot.name, robot.path);
                    return false;
                } else {
                    return true;
                }
            } catch (Exception e) {
                LOG.error("can't revalidate robot {} at {}", robot.name, robot.path);
                return false;
            }
        }
        
        public Work newEnumerationRequest() {
            // re-report previously known devices to front end, if still valid and AVAILABLE
            return new Work("user enumeration request", "", () -> {
                ArrayList<BLERobotDevice> dead = new ArrayList<>();
                for (BLERobotDevice robot : robotsByPath.values()) {
                    if (!revalidateDevice(robot)) {
                        LOG.info("Device no long valid, scheduling removal: {} {}", robot.name, robot.path);
                        dead.add(robot);
                        continue;
                    }
                    if (robot.status == AVAILABLE)
                        robot.reportTo(frontendServer);
                }
                for (BLERobotDevice robot : dead)
                    disconnect(robot, DEAD);
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
                robotManager.receiveDisconnectionEvent(robotName, true /* permanent */);
                // do not repopulate frontend availabile list
                return;
            }
            if (robot.status != AVAILABLE) {
                // Should not happen
                LOG.error("Device " + robotName + " is not available.");
                // Should we call manager here to notify of removal event?
                if (robot.status == CONNECTED || robot.status == CONNECTING_BEGIN || robot.status == CONNECTING_PROBE) {
                    // Maybe just leave it alone? UI could end up with two entries of same name?
                    return;
                } else {
                    // Robot is dead, unavailable, or disconnecting
                    robotManager.receiveDisconnectionEvent(robotName, true /* permanent */);
                    // do not repopulate frontend availabile list
                    return;
                }
            }
            LOG.info("robot {} is currently AVAILABLE, path is {}", robot.name, robot.path);
            try {
                Device1 device = conn.getRemoteObject("org.bluez", robot.path, Device1.class);
                if (device == null) {
                    LOG.error("can't find " + robot.path);
                    robot.status = UNAVAILABLE;
                    robot.rssi = null;
                    robotManager.receiveDisconnectionEvent(robotName, true /* permanent */);
                    return;
                }
                robot.status = CONNECTING_BEGIN;
                device.Connect();
                robot.device = device;
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
            } catch (Exception e) {
                LOG.error("can't connect to " + robot.path + ": " + e.getMessage());
                disconnect(robot, UNAVAILABLE);
                return;
            }
            // set a timer in case connection drops out
            if (robot.status != CONNECTED && robot.status != DISCONNECTING) {
                robot.connectionTimer = scheduler.schedule(() -> {
                    LOG.error("Connection timeout for {}...", robot.name);
                    workQueue.offer(worker.newDisconnectRequest(robot.name));
                }, 3, TimeUnit.SECONDS);
            }
        }

        public Work newDisconnectRequest(String robotName) {
            return new Work("user disconnect request", robotName, () -> beginDisconnect(robotName));
        }

        private void beginDisconnect(String robotName) {
            LOG.info("User-initiated disconnection from " + robotName);
            BLERobotDevice robot = robotsByName.get(robotName);
            if (robot == null) {
                LOG.error("can't find info for " + robotName);
                return;
            }
            disconnect(robot, AVAILABLE); // This is more like "AVAILABLE pending revalidation"
        }

        private void disconnect(BLERobotDevice robot, Status nextStatus) {
            Status prevStatus = robot.status;
            LOG.info("Disconnecting from robot {}, {} -> {}.", robot.name, prevStatus, nextStatus);
            if (prevStatus == null || prevStatus == DEAD) {
                LOG.error("invalid disconnect");
                return;
            }
            if (prevStatus == DISCONNECTING) {
                LOG.error("reentrant disconnect");
                return;
            }
            robot.status = DISCONNECTING;
            if (prevStatus == CONNECTED) {
                robot.checkedSend(POLL_STOP);
            }
            if (prevStatus == CONNECTED || prevStatus == CONNECTING_PROBE) {
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
            workQueue.removeIf((work) -> robot.owns(work));
            robot.status = nextStatus;
            if (robot.status == AVAILABLE) {
                // This is the user-initiated case, so permanent=true, that way the manager won't
                // try to reconnect.
                if (prevStatus == CONNECTED || prevStatus == CONNECTING_BEGIN || prevStatus == CONNECTING_PROBE) {
                    // Expected case: robot was CONNECTED
                    // Unexpected cases: robot was still connecting... should not happen.
                    robotManager.receiveDisconnectionEvent(robot.name, true /* permanent */);
                } else {
                    // Unexpected cases: robot was not connected or connecting... should not happen.
                    frontendServer.removeScanResponse(robot.name);
                }
                // In the expected case, the robot was CONNECTED, and the user simply wanted to
                // disconnect, we go back to AVAILABLE if RSSI is reasonable. But maybe there was
                // some glitch and the robot was stuck in some other state (in which case robot
                // should not have been visible to user), and the user is disconnecting out of
                // frustration. So let's revalidate before allowing this to show up in UI again.
                if (revalidateDevice(robot)) {
                    // Device is still good, check if RSSI is still reasonable...
                    if (robot.rssi != null && robot.rssi >= RSSI_LEVEL_THRESHOLD)
                        robot.reportTo(frontendServer); // repopulate frontend available list
                    else
                        robot.status = UNAVAILABLE;
                    return;
                }
                robot.status = DEAD;
                // fall through to next case
            }
            if (robot.status == DEAD) {
                // Just in case, notify manager or frontend to remove from UI
                if (prevStatus == CONNECTED || prevStatus == CONNECTING_BEGIN || prevStatus == CONNECTING_PROBE)
                    robotManager.receiveDisconnectionEvent(robot.name, true /* permanent */);
                else
                    frontendServer.removeScanResponse(robot.name);
                robotsByPath.remove(robot.path);
                robotsByName.remove(robot.name);
                robot.path += "[poisoned]";
                robot.addr += "[expired]";
                robot.name += "[dead]";
                robot.rssi = null;
                return;
            }
            if (robot.status == UNAVAILABLE) {
                robot.rssi = null;
                if (prevStatus == CONNECTED) {
                    // Remove from manager's list of robots "Connected and/or Pending Connection".
                    // Since we were previuosly connected, and apparently have lost the connection,
                    // use permanent=false. This may be the intended case for that flag, e.g. where
                    // robot has gone out of range temporarily, or was accidentally turned off.
                    robotManager.receiveDisconnectionEvent(robot.name, false /* permanent */);
                } else if (prevStatus == CONNECTING_BEGIN || prevStatus == CONNECTING_PROBE) {
                    // Remove from manager's list of robots "Connected and/or Pending Connection".
                    // Since we were previuosly connecting, we probably failed, or timed out, or the
                    // user got frustrated. So use permanent=true, that way manager won't try to
                    // reconnect to this misbehaving device.
                    robotManager.receiveDisconnectionEvent(robot.name, true /* permanent */);
                } else {
                    // Remove from frontend's list of available robots, if it is there.
                    frontendServer.removeScanResponse(robot.name);
                }
                return;
            }
        }

        private void cleanupConnection(Boolean connected, String path, Status status) {
            if (connected != null && ((boolean)connected)) {
                LOG.error("DANGER: Device was {} but already connected??? This code is untested.", status);
                LOG.error("Forcing BLE disconnection from {}", path);
                try {
                    Device1 device = conn.getRemoteObject("org.bluez", path, Device1.class);
                    if (device == null) {
                        LOG.error("can't find device at " + path);
                    } else {
                        device.Disconnect();
                    }
                }
                catch (Exception e) {
                    LOG.error("can't force disconect from " + path);
                }
            }
        }

        private void disconnectAll() {
            ArrayList<BLERobotDevice> dead = new ArrayList<>(robotsByPath.values());
            for (BLERobotDevice robot : dead)
                disconnect(robot, DEAD);
        }

        public Work newSendRequest(String robotName, byte[] cmd) {
            return new Work("user command", robotName, () -> send(robotName, cmd));
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

        private String makeUnique(HashMap<String, BLERobotDevice> robots, String name, String path) {
            BLERobotDevice old = robotsByName.get(name);
            if (old == null)
                return name;
            LOG.error("Name collision: {} is used by both {} and {}", name, path, old.path);
            // Constraints:
            //  - must not change the 2-letter prefix (e.g. "FN" or "MB")
            //  - the rest must be valid hex
            //  - must it remain 6 chars? Unknown.
            // Let's just add more digits until it is unique.
            int suffix = 1;
            while (true) {
                String alt = name + Integer.toHexString(suffix).toUpperCase();
                if (!robotsByName.containsKey(alt)) {
                    LOG.error("Renamed {} to {} for {} due to name collision", name, alt, path);
                    return alt;
                }
            }
        }

    } // end of Worker


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

    private static <T> T getProp(Map<String, Variant<?>> props, String key, Class<T> type) {
        Variant<?> val = props.get(key);
        if (val == null)
            return null;
        Object raw = val.getValue();
        if (raw == null) {
            LOG.error("BLE stack sent unexpected empty-value for key {}", key);
            return null;
        }
        if (!type.isInstance(raw)) {
            LOG.error("BLE stack sent unexpected type {} for key {}", raw.getClass().getName(), key);
            return null;
        }
        return type.cast(raw);
    }


    //
    // Bluez DBus Interfacces
    //

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
