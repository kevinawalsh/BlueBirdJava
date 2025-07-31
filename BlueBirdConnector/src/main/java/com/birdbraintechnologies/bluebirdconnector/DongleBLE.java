package com.birdbraintechnologies.bluebirdconnector;

import com.fazecast.jSerialComm.SerialPort; //import gnu.rxtx.*; //import javax.comm.*;  //import gnu.io.SerialPort;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.thingml.bglib.*;

import org.thingml.bglib.gui.*;

import java.util.*;

import static com.birdbraintechnologies.bluebirdconnector.Utilities.*;


public class DongleBLE extends BGAPIDefaultListener implements RobotCommunicator {

    static final Log LOG = Log.getLogger(DongleBLE.class);

    private final RobotManager robotManager = RobotManager.getSharedInstance();
    private final FrontendServer frontendServer = FrontendServer.getSharedInstance();

    private Deque<String> connectionQueue = new ArrayDeque<String>();

    protected BGAPI bgapi = null;
    private SerialPort port = null;

    /*
     * GATT DISCOVERY
     */
    private static final int IDLE = 0;
    private static final int SERVICES = 1;
    private static final int ATTRIBUTES = 2;
    private Iterator<BLEService> discovery_it = null;
    private BLEService discovery_srv = null;
    private int discovery_state = IDLE;
    //List of discovered devices
    protected BLEDeviceList devList = new BLEDeviceList();
    //List of supported device types
    List<String> supportedRobotTypes;

    //Connection Parameters
    protected final int interval_min;
    protected final int interval_max;
    protected final int latency;
    protected final int timeout;
    protected final int scan_interval;
    protected final int scan_window;
    protected final int active;

    protected final int addr_type = 1;

    //Synchronized Read support
    private static final class ReadLock { }
    private final Object readLock = new ReadLock();
    protected volatile int readResult = -1;
    protected volatile int readHandle = -1;
    private byte [] nullArray = new byte[0];
    protected volatile byte[] readValue = nullArray;

    //Service discovery
    protected boolean [] characteristicDiscover = new boolean [16];
    protected boolean descriptorsDiscover = false;
    protected int serviceCount = 0;
    //Hashtable primaryServiceTable;
    List<Integer> serviceAttHandleList = new ArrayList<Integer>();
    protected boolean buildingTable = false;
    protected boolean firstServiceAddress = false;
    protected String primaryAddress = null;

    static String SERVICE_UUID = "6e4001b5a3f393e0a9e5e24dcca9e";
    static String WRITE_CHARACTERISTIC_UUID  = "6e4002b5a3f393e0a9e5e24dcca9e";
    static String NOTIFY_CHARACTERISTIC_UUID = "6e4003b5a3f393e0a9e5e24dcca9e";
    static int HB_NOTIFY_CTL_CHAR = 0x2902;
    static int RSSI_THRESHOLD = 20;

    //Information about the device that is currently being connected
    BLEDevice bledConnecting = null;
    //List of connected devices
    BLEDevice[] connectedDevices = new BLEDevice[10]; //TODO: initialize to 3?
    private Hashtable<String, Integer> robotIndexes = new Hashtable<>();
    private Hashtable<String, Integer> disconnectRequests = new Hashtable<>();

    public DongleBLE() {
        //Initialize characteristic discovery table
        for (int i = 0; i < characteristicDiscover.length; i++)
            characteristicDiscover[i] = false;

        //From scratchME.properties
        interval_min = 35;
        interval_max = 40;
        latency = 100;
        timeout = 0;
        scan_interval=500;
        scan_window=  250;
        active =      1;
        supportedRobotTypes = new ArrayList<String>(List.of("MB","BB","FN"));

        startBLEDongle();
    }

    //**** RobotCommunicator Methods ****

    @Override
    public void requestConnection(String name) {
        if (bgapi == null) {
            LOG.error("requestConnection: bgapi is null.");
            return;
        }
        // Find device by address from the scan list
        bledConnecting = devList.getFromName(name);
        if (bledConnecting != null) {
            LOG.info("Do_Connect_address: Device found");
            bgapi.send_gap_connect_direct(BDAddr.fromString(bledConnecting.getAddress()), addr_type, interval_min, interval_max, latency,timeout);
        }else {
            LOG.error("Device " + name + " NOT FOUND");
            //TODO: Let the robot manager know about the failure?
        }
    }

    @Override
    public void requestDisconnect(String name) {
        Integer index = robotIndexes.get(name);
        if (index != null) {
            disconnectRequests.put(name, index); //indicate that this robot should no longer be connected
            bgapi.send_connection_disconnect(index);
        } else {
            LOG.error("Request to disconnect " + name + ". Robot not found.");
            //TODO: Let the robot manager know about the failure?
        }
    }

    @Override
    public boolean isRunning() {
        return (bgapi != null);
    }

    @Override
    public void startDiscovery() {
        LOG.debug("START DISCOVERY");
        if (bgapi == null){
            LOG.error("Attempting to start discovery with bgapi null.");
            return;
        }
        LOG.info("BLED112: scan_interval= {}, scan_window= {}, active= {} ", +scan_interval  , scan_window, active);

        //End any gap procedures before scanning
        bgapi.send_gap_end_procedure();
        devList.clear();  // Clear the global device list

        bgapi.send_gap_set_scan_parameters(scan_interval, scan_window, active);

        //wait for command to complete
        try {
            Thread.sleep(200);
        } catch (InterruptedException ex) {
            ;
        }

        bgapi.send_gap_discover(1);
        frontendServer.updateGUIScanStatus(true);
    }

    //Stop looking for robots
    @Override
    public void stopDiscovery() {
        LOG.debug("STOP DISCOVERY");
        if (bgapi != null) {
            bgapi.send_gap_end_procedure();
            //listener.updateGUIScanStatus(false);
            frontendServer.updateGUIScanStatus(false);
        } else {
            LOG.error("bgapi is null when attempting to stop discovery");
        }
    }

    //Cancel the current connection request
    //TODO: There should be more to this...
    // @Override
    // public void cancelConnectionRequest() {
    //     if (bgapi != null) {
    //         bgapi.send_gap_end_procedure();
    //     } else {
    //         LOG.error("cancelConnectionRequest: bgapi is null");
    //     }
    // }

    @Override
    public void sendCommand(String robotName, byte[] command) {
        Integer index = robotIndexes.get(robotName);
        if (index != null) {
            sendCommand(command, index);
        } else {
            LOG.error("Attempt to send command to {} failed. Robot not connected.", robotName);
        }
    }

    /**
     * shut down the communicator
     */
    @Override
    public void kill() {
        if (bgapi != null) {
            bgapi.removeListener(this);
            LOG.info("BLE: Reset BLED112 Dongle");
            bgapi.send_system_reset(0);
            bgapi.disconnect();
            bgapi = null;
            LOG.info("BLE: Done resetting BLED112 Dongle");
        }

        if (port != null) {
            LOG.info("Freeing port");
            try {
                LOG.info("Closing input stream");
                port.getInputStream().close();
                LOG.info("Closing output stream");
                port.getOutputStream().close();
                LOG.info("Port streams closed");

            } catch (Exception ex) {
                LOG.error(" Error closing serial input and output streams:");
                LOG.error("{}", stackTraceToString(ex));
            }
            finally {
                LOG.info("Closing port");
                port.closePort();
                LOG.info("Port closed");
            }
        }
        bgapi = null;
        port = null;
        devList.clear();
    }

    //***** Private Helper Methods *****

    //Send command to specified device
    private void sendCommand(byte[] command, int connection) {
        int attHandle = connectedDevices[connection].getTxHandle();
        LOG.debug("Writing to connection {} using handle {}", connection, attHandle);
        sendAsyncCommandWithHandle(command, connection, attHandle);
    }

    private void sendAsyncCommandWithHandle(byte[] command, int connection, int attHandle) {
        if (attHandle == -1) {
            LOG.error("Sending command to {} without attHandle", connection);
            return;
        }
        if (bgapi != null) {
            LOG.debug("Sending to connection {}, handle: {}. command: {}", connection, attHandle, bytesToString(command));
            bgapi.send_attclient_write_command(connection, attHandle, command);
        } else {
            LOG.error("Tried to write to connection {} while bgapi is null", connection);
        }

        //Synchronous writes for debugging
        //bgapi.send_attclient_attribute_write(connection, attHandle, setAllData);
        //Async for speed
    }

    private void sendNotificationSetup(BLEDevice robot, int connection, boolean enable) {
        try {
            byte op;
            long sleepTime;
            if (enable) {
                op = 0x01;
                sleepTime = 1000;
            }
            else {
                op = 0x00;
                sleepTime = 0;
            }

            //Turn on/off notifications 0x2902
            Thread.sleep(sleepTime);
            //int attHandle = getAttHandleStr(connection, HB_NOTIFY_SVC_UUID,  HB_NOTIFY_CTL_CHAR);
            int attHandle = robot.getRxHandle();
            byte data [] = new byte[2];
            data[0]= op;
            data[1]= 0x00;
            //LOG.info("Channel {}: Write to Att Handle Sync 0x{}, payload: {}", connection,  Integer.toHexString(attHandle), bytesToString(data));
            //doWriteHandle((byte)connection, attHandle, data);
            sendAsyncCommandWithHandle(data, connection, attHandle);

            //Write hummingbird specific enable packet
            if (enable) {
                Thread.sleep(sleepTime);

                //Send a get firmware command to determine which type of microbit this is.
                byte[] getFirmwareCmd = new byte[] { (byte)0xCF };
                if (connectedDevices[connection] != null && connectedDevices[connection].getName().startsWith("FN")) {
                    getFirmwareCmd[0] = (byte)0xD4;
                }
                LOG.debug("Getting firmware...");
                sendCommand(getFirmwareCmd, connection);
            }
        }catch (Exception e){
            LOG.error("Device initialization error. Connection {},  {}", connection,  stackTraceToString(e));
        }

    }

    private void removeConnectionInfo(int connection) {
        BLEDevice robot = connectedDevices[connection];
        if (robot != null) {
            robotIndexes.remove(robot.getName());
            connectedDevices[connection] = null;
            Integer disconnectIndex = disconnectRequests.remove(robot.getName());
            robotManager.receiveDisconnectionEvent(robot.getName(), disconnectIndex != null);
        }
    }

    /**
     * Proceedure for starting up a ble dongle.
     * @return
     */
    private boolean startBLEDongle () {
        LOG.info("Connecting to BLED112. Resetting Dongle...");
        //connectionTable.clear(); //reset connecton table
        //blueBirdDriver.disconnect (false); //reset previous dongle connection
        //TODO: make sure another instance isn't already running?

        LOG.debug("disconnection complete, starting bluetooth dongle connection attempt");
        SerialPort blueGigaPort = BLED112.selectSerialPort();
        if (blueGigaPort!=null){
            port = BLED112.connectSerial(blueGigaPort);
            LOG.info("Found BLE112 on port {}", port);
            if (setupBGAPI(port)){
                LOG.info("Connected to BLED112 OK");
                return true;
            } else {
                LOG.error("ERROR: Error connecting to BLED112, cannot setup the BGAPI. Trying again...");
                return false;
            }
        } else {
            LOG.info("Cannot find BLED112 Dongle.");
            return false;
        }
    }

    /**
     * Sets up the ble dongle on the specified port. Called from startBLEDongle().
     * Part of the WaitForConnection thread.
     * @param port
     * @return true for a successful setup
     */
    private boolean setupBGAPI (SerialPort port) {

        if (port != null) {
            try {
                LOG.info("Connected on {}", port);
                port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0);
                BGAPITransport bgapiTransport = new BGAPITransport(port.getInputStream(), port.getOutputStream());
                //bgapiTransport.addSerialErrorListener(this); //TODO: make replacement for this?
                bgapi = new BGAPI(bgapiTransport);
                LOG.debug("bgapi");
                bgapi.addListener(this);

                LOG.debug("Adding Listener");
                //Thread.sleep(1000);
                Thread.sleep(500);
                LOG.debug("sending system get info");
                bgapi.send_system_get_info();
                LOG.debug("sent.");
                Thread.sleep(1000);
                LOG.debug("about to stopDiscovery");
                stopDiscovery();
                LOG.debug("about to return true");
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
        else {
            LOG.error("Invalide serial port:");
            return false;
            //jTextFieldBLED112.setText("Invalide serial port:" + jTextFieldBLED112.getText());
            //jButtonBLED112Conn.setEnabled(true);
        }

    }

    public static String bytesToUUIDString(byte[] bytes) {
        /*StringBuffer result = new StringBuffer();
        for(byte b : bytes) result.append( Integer.toHexString(b & 0xFF));
        return result.toString();*/
        String result = "";
        for(byte b : bytes) result = (Integer.toHexString(b & 0xFF)) + result;
        return result;
    }

    //******** BGAPIListener Methods ********//

    // Callbacks for class system (index = 0)
    public void receive_system_get_info(int major, int minor, int patch, int build, int ll_version, int protocol_version, int hw) {
        LOG.info("Connected. BLED112:" + major + "." + minor + "." + patch + " (" + build + ") " + "ll=" + ll_version + " hw=" + hw);
    }
    public void receive_system_endpoint_rx(int endpoint, byte[] data) {
        LOG.debug("receive_system_endpoint_rx");
    }

    // Callbacks for class flash (index = 1)

    // Callbacks for class attributes (index = 2)
    public void receive_attributes_read(int handle, int offset, int result, byte[] value) {
        LOG.debug("receive_attributes_read value: " + bytesToString(value));
    }
    public void receive_attributes_read_type(int handle, int result, byte[] value) {
        LOG.debug("receive_attributes_read_type att= " + Integer.toHexString(handle) + " val = " + bytesToString(value));
    }
    public void receive_attributes_value(int connection, int reason, int handle, int offset, byte[] value) {
        LOG.debug("receive_attributes_value att=" + Integer.toHexString(handle) + " val = " + bytesToString(value));
    }
    public void receive_attributes_user_request(int connection, int handle, int offset) {}

    // Callbacks for class connection (index = 3)
    public void receive_connection_disconnect(int connection, int result) {
        LOG.info("receive_connection_disconnect");
        LOG.info("Master initiated disconnect. connection: {}, Result: {}", connection, result);
        //blueBirdDriver.masterDisconnect[connection] = true;
        //blueBirdDriver.purgeConnection(connection, true);

        removeConnectionInfo(connection);
    }
    public void receive_connection_update(int connection, int result) {
        LOG.debug("receive_connection_update: connection: " + connection + " result: 0x" + Integer.toHexString(result));
    }
    public void receive_connection_get_status(int connection) {
        LOG.debug("receive_connection_get_status: Connection: " + connection);
    }

    public void receive_connection_raw_tx(int connection) {
        LOG.debug("receive_connection_raw_TX");
    }
    //Second response after a connection is requested
    public void receive_connection_status(int conn, int flags, BDAddr address, int address_type, int conn_interval, int timeout, int latency, int bonding) {
        LOG.info("receive_connection_status " + "[" + address.toString() + "] Conn = " + conn + " Flags = " + flags + " address type = " + address_type + " interval = " + conn_interval + " timeout = " + timeout + " latency = " + latency + " bonding = " + bonding);

        /*if (!deviceConnecting) {
            LOG.error("!!!! Device is not in connecting state. Aborting connection attempt.");
            return;
        }*/

        try{
            if (flags == 0x05) {
                LOG.info("Connection made. Connection number= " + conn);

                if (connectedDevices[conn] != null) {
                    LOG.error("DEVICE ALREADY CONNECTED?");
                } else {
                    connectedDevices[conn] = bledConnecting;
                    robotIndexes.put(bledConnecting.getName(), conn);
                    bledConnecting = null;
                }

                LOG.info("Discovering Services........");

                byte [] uuid = new byte[2];
                uuid[0] = 0x03;
                uuid[1] = 0x28;

                characteristicDiscover[conn] = true;
                //Allocate hastable for this connection.
                //primaryServiceTable = new Hashtable();

                descriptorsDiscover =false;
                serviceCount = 0;
                serviceAttHandleList.removeAll(serviceAttHandleList);

                bgapi.send_attclient_read_by_type(conn, 0x1, 0xffff, uuid);

                LOG.info("receive_connection_status  function completed.");
                //deviceConnecting = false;

            } else if (flags == 0x09) {
                LOG.info("Connection Update: Address: "  + "[" + address.toString() + "] Conn = " + conn + " connection_interval = " + conn_interval);

            }else {
                LOG.error("Connection " + conn + " lost!");
                if (bledConnecting != null) {
                    robotManager.receiveDisconnectionEvent(bledConnecting.getName(), false);
                    bledConnecting = null;
                } else {
                    removeConnectionInfo(conn);
                }
                //deviceConnecting = false;

            }
        }catch (Exception e){
            LOG.error("Connection Error: {}",  stackTraceToString(e));
        }
    }
    public void receive_connection_raw_rx(int connection, byte[] data) {
        LOG.debug("receive_connection_raw_rx");
    }
    public void receive_connection_disconnected(int connection, int reason) {
        LOG.info("receive_connection_disconnected :  Connection: " + connection + "reason: 0x" + Integer.toHexString(reason));
        removeConnectionInfo(connection);
    }

    // Callbacks for class attclient (index = 4)
    //Third response after a connection is requested
    public void receive_attclient_read_by_type(int connection, int result) {
        LOG.debug("receive_attclient_read_by_type Result: " + Integer.toHexString(result));
    }
    public void receive_attclient_find_information(int connection, int result) {
        LOG.debug("receive_attclient_find_information Result: " + Integer.toHexString(result));
    }
    public void receive_attclient_read_by_handle(int connection, int result) {
        synchronized (readLock) {
            LOG.debug("receive_attclient_read_by_handle result " + result + "  Connection: " + connection);
            readResult = result;
            if (result != 0)       // Error, no value response will come. Release the lock
                readLock.notify();
        }
    }
    public void receive_attclient_attribute_write(int connection, int result) {
        if (result != 0) {
            LOG.error("receive_attclient_attribute_write Sync: {}: Write error: busy" , Integer.toString(result));
        } else LOG.debug("receive_attclient_attribute_write Sync: SUCCESS");
    }
    public void receive_attclient_write_command(int connection, int result) {
        if (result != 0) {
            LOG.error("receive_attclient_write_command Async: {}: Write error: busy" , Integer.toString(result));
        }  else LOG.debug("receive_attclient_write_command Async: SUCCESS");
    }
    //Fifth response after connection is requested. Once for each characteristic
    public void receive_attclient_procedure_completed(int connection, int result, int chrhandle) {

        LOG.debug("receive_attclient_procedure_completed Result: " + Integer.toHexString(result) + " chrhandle: " + Integer.toHexString(chrhandle));

        if (characteristicDiscover[connection]) { // Get list of services
            //if (serviceCount < serviceAttHandleList.size() -1) {
            if (serviceCount < serviceAttHandleList.size()) {
                descriptorsDiscover = true;
                //attrDiscoverLooping = true;
                Collections.sort(serviceAttHandleList);
                int start = serviceAttHandleList.get(serviceCount) + 1;
                int end;
                if (serviceCount == serviceAttHandleList.size() - 1){
                    LOG.debug("Last service:" + serviceCount);
                    end = 0xffff;
                }
                else
                    end = serviceAttHandleList.get(serviceCount+1);
                LOG.debug("serviceCount = " + serviceCount);
                LOG.debug("Start: " + start + " end: " + end);
                serviceCount++;
                firstServiceAddress = true;
                bgapi.send_attclient_find_information (connection, start, end);
            } else {
                LOG.debug("Done Generating Service Attribute Handle Table. Resetting initial parameters.");
                serviceCount = 0;
                serviceAttHandleList.removeAll(serviceAttHandleList);

                //Connection is complete, update the data structures
                if (connectedDevices[connection] != null) {
                    sendNotificationSetup(connectedDevices[connection], connection, true);
                }

                characteristicDiscover[connection] = false;
                descriptorsDiscover =false;
            }
        } else {
            LOG.info("Attribute Write Event Response. Connection {}, Result: {}", connection ,Integer.toHexString(result));
        }

        if (result != 0) {
            System.err.println("ERROR: Attribute Procedure Completed with error code 0x" + Integer.toHexString(result));
            LOG.error("ERROR: Attribute Procedure Completed with error code 0x" + Integer.toHexString(result));
            synchronized (readLock) {
                readResult = result;
                readLock.notify();
            }

            // Reset everything
            descriptorsDiscover =false;
            characteristicDiscover[connection] =false;

            //Cancel whatever operation is happening
            bgapi.send_gap_end_procedure();

        } else {
            LOG.info("receive_attclient_procedure_completed SUCCESSFULLY for connection {}", connection);
        }
    }
    public void receive_attclient_group_found(int connection, int start, int end, byte[] uuid) {
        LOG.debug("receive_attclient_group_found");
        //TODO: something here? what is this function? Not called?
        /*if (blueBirdDriver.bledevice != null) {
            BLEService srv = new BLEService(uuid, start, end);
            blueBirdDriver.bledevice.getServices().put(srv.getUuidString(), srv);
        }*/
    }
    public void receive_attclient_find_information_found(int connection, int chrhandle, byte[] uuid) {

        LOG.debug("\n receive_attclient_find_information_found chrhandle: " + Integer.toHexString(chrhandle) + " uuid: " + bytesToUUIDString(uuid));

        String uuidString = bytesToUUIDString(uuid);
        //LOG.debug("Found {}, looking for {}", uuidString, SERVICE_UUID);
        if (uuidString.equals(WRITE_CHARACTERISTIC_UUID) && connectedDevices[connection] != null) {
            LOG.debug("Setting TX handle to {}", chrhandle);
            connectedDevices[connection].setTxHandle(chrhandle);
        }

        if (discovery_state == ATTRIBUTES && discovery_srv != null) {
            LOG.debug("Discovery State ATTRIBUTES");
            BLEAttribute att = new BLEAttribute(uuid, chrhandle);
            discovery_srv.getAttributes().add(att);
        }

        if (descriptorsDiscover) {
            LOG.debug("Creating Service UUID to Attribute Hash Table");
            if (firstServiceAddress) {
                primaryAddress = bytesToUUIDString(uuid);
                //LOG.debug("UUID String: " + primaryAddress);
                LOG.debug("First Service Address : "  + primaryAddress);
                firstServiceAddress = false;
            } else {
                LOG.debug("Primary address: " + primaryAddress);
                int secondaryAddress = ((uuid[1] & 0xFF) << 8) + (uuid[0] & 0xFF);   //Unsigned Int SHift

                if (primaryAddress.equals(NOTIFY_CHARACTERISTIC_UUID) && secondaryAddress == HB_NOTIFY_CTL_CHAR && connectedDevices[connection] != null) {
                    LOG.debug("Setting RX handle to {}", chrhandle);
                    connectedDevices[connection].setRxHandle(chrhandle);
                }
            }
        }
    }
    //Fourth response after a connection is requested - characteristicDiscover called once for each characteristic
    public void receive_attclient_attribute_value(int connection, int atthandle, int type, byte[] value) {

        if (characteristicDiscover[connection]) {
            if (value.length > 0) {
                LOG.debug("Service found: " + bytesToString(value) + " AttHandle: " + atthandle);
                serviceAttHandleList.add(atthandle);
            }
        } else if (atthandle == readHandle) {
            synchronized (readLock) {
                System.out.println ("Processing Read Request");
                readValue = value;
                System.out.println ("Read Result: " + readResult + " Read Value = " + bytesToString(value));
                System.out.println ("DONE with read, unlocking....");
                readLock.notify();
            }
        } else {  //Incoming Notifications
            //LOG.debug("Receive notification for {} with value {}.", connection, bytesToString(value));
            BLEDevice robot = connectedDevices[connection];
            if (robot != null) {
                if (value.length < 10 && robot.getMicrobitVersion() == 0) {
                    //The first notification should be version information
                    int version = 1;
                    if (value.length > 3) {
                        version = (value[3] == 0x22) ? 2 : 1;
                    }
                    robot.setMicrobitVersion(version);
                    LOG.debug("Set microbit version for {} to {}", robot.getName(), robot.getMicrobitVersion());

                    //send poll start
                    byte[] pollStart = (version == 2) ? new byte[] { 0x62, 0x70 } : new byte[] { 0x62, 0x67 };
                    sendCommand(pollStart, connection);

                    //finally call the connection successful
                    robotManager.receiveConnectionEvent(robot.getName(), (version == 2));
                } else if (value.length > 10){
                    //Sensor data
                    robotManager.receiveNotification(robot.getName(), value);
                } else {
                    LOG.error("Unidentified short notification found for {}. {}", robot.getName(), bytesToString(value));
                }

            } else {
                LOG.error("Receive notification for connection " + connection + " but nothing connected?!");
            }

            //  Sample Data
            //[ b3 92 3e 0    73 d4 18 0   43 2 92 8b   5c 61 0 0   0 0 0 0 ]

            //Java Data Types Def:
            //http://docs.oracle.com/javase/6/docs/api/java/io/DataInput.html#readInt%28%29
            //http://stackoverflow.com/questions/13203426/convert-4-bytes-to-an-unsigned-32-bit-integer-and-storing-it-in-a-long
            //  0xFFB5 / 0x00  indicate that the data is from a notification vs read.

        }
    }

    // Callbacks for class sm (index = 5)

    // Callbacks for class gap (index = 6)
    public void receive_gap_set_mode(int result) {
        LOG.debug("receive_gap_set_mode. Result: " + result + "  " + Integer.toString(result));
    }
    public void receive_gap_discover(int result) {
        LOG.debug("receive_gap_discover : Result = " + result);
        if (result == 0) {
            frontendServer.updateGUIScanStatus(true);
        } else {
            frontendServer.updateGUIScanStatus(false);
        }

    }
    //The first response after a connection is requested.
    public void receive_gap_connect_direct(int result, int connection_handle) {
        LOG.debug("receive_gap_connect_direct Result: {},  connection: {}" , result, connection_handle);

        //If the result is not 0, the connection is failing.
        if (result != 0)  {
            bledConnecting = null;
        }
    }
    public void receive_gap_end_procedure(int result) {
        LOG.debug("receive_gap_end_procedure");
        frontendServer.updateGUIScanStatus(false);
    }
    public void receive_gap_scan_response(int rssi, int packet_type, BDAddr sender, int address_type, int bond, byte[] data) {

        //frontendServer.updateGUIScanStatus(true);

        if ((packet_type == 0) || (packet_type == 4)) { // Scan Response Packet

            String name = new String(data).trim();
            if (name.length() < 2) {
                LOG.debug("Scan response from {} does not contain enough data: {}", sender.toString(), bytesToString(data));
                return;
            }

            String prefix = name.substring(0, 2);
            if (supportedRobotTypes.contains(prefix)) {
                BLEDevice d = devList.getFromAddress(sender.toString());
                LOG.debug("Discovered {}. rssi = {}.", name, rssi);

                // This is a newly advertising device, therefore, place it in the list
                if (d == null) {
                    LOG.debug("Placing {} in device list: {}", name, sender.toString());
                    d = new BLEDevice(sender.toString());
                    devList.add(d);
                }
                boolean nameChanged = !name.equals(d.getName());
                boolean rssiChanged = !(rssi < d.getRssi() + RSSI_THRESHOLD && rssi > d.getRssi() - RSSI_THRESHOLD);
                if (nameChanged || rssiChanged) {
                    LOG.debug("Setting {}. new name = {}, old rssi = {}, new rssi = {}.", d.getName(), name, d.getRssi(), rssi);
                    d.setRssi(rssi);
                    d.setName(name);
                    devList.changed(d); //TODO: what does this do?

                    JsonObject scanResponse = JsonParser.parseString("{'packetType': 'discovery', 'name': "+ name +", 'rssi': "+ rssi +"}").getAsJsonObject();
                    frontendServer.receiveScanResponse(name, scanResponse);
                }
            }
        }
    }
    public void receive_gap_mode_changed(int discover, int connect) {
        LOG.debug("receive_gap_mode_changed: " + discover + "  " + connect);
    }

    // Callbacks for class hardware (index = 7)

    // Callbacks for class test (index = 8)

    public void serialError() {
        LOG.info("Serial Error. Dongle disconnected.");
        bgapi.disconnect();
        bgapi = null;
        this.kill();
        robotManager.updateCommunicatorStatus(false, true);
    }
    //******** end BGAPIListener methods ********//

}



