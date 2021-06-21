package com.birdbraintechnologies.bluebirdconnector;

import com.fazecast.jSerialComm.SerialPort; //import gnu.rxtx.*; //import javax.comm.*;  //import gnu.io.SerialPort;
import org.json.JSONObject;
import org.thingml.bglib.*;

//logging
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thingml.bglib.gui.*;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.*;

import static com.birdbraintechnologies.bluebirdconnector.Utilities.*;

//TODO: implement RobotCommunicator and extend BGAPIDefaultListener
public class DongleBLE extends RobotCommunicator implements BGAPIListener {

    static final Logger LOG = LoggerFactory.getLogger(DongleBLE.class);

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
    //List<String> deviceList;
    List<String> supportedRobotTypes;

    static CharsetEncoder asciiEncoder =
            Charset.forName("US-ASCII").newEncoder(); // or "ISO-8859-1" for ISO Latin 1

    //Connection Parameters
    protected  int interval_min = 100;
    protected  int interval_max = 120;
    protected  int latency = 900;
    protected  int timeout = 0;

    protected  int scan_interval = 500;
    protected  int scan_window = 500;
    protected  int active = 1;

    protected int addr_type = 1;


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
    Hashtable primaryServiceTable;
    List<Integer> serviceAttHandleList = new ArrayList<Integer>();
    protected boolean buildingTable = false;
    protected boolean firstServiceAddress = false;
    protected String primaryAddress = null;

    static String SERVICE_UUID = "6e4001b5a3f393e0a9e5e24dcca9e";
    static String WRITE_CHARACTERISTIC_UUID  = "6e4002b5a3f393e0a9e5e24dcca9e";
    static String NOTIFY_CHARACTERISTIC_UUID = "6e4003b5a3f393e0a9e5e24dcca9e";
    static int   HB_NOTIFY_CTL_CHAR = 0x2902;
    static int RSSI_THRESHOLD = 20;

    //Information about the device that is currently being connected
    BLEDevice bledConnecting = null;
    //List of connected devices
    BLEDevice[] connectedDevices = new BLEDevice[10]; //TODO: initialize to 3?
    private Hashtable<String, Integer> robotIndexes = new Hashtable<>();

    public DongleBLE(RobotManager manager) {
        super(manager);

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
        //supportedRobotTypes = new ArrayList<String>(Arrays.asList("BBC micro:bit,MB,BB,FN".split(",")));
        supportedRobotTypes = new ArrayList<String>(List.of("MB","BB","FN"));

        if (supportedRobotTypes!=null){
            try {
                LOG.info("\n==> Supported Devices");
                Iterator<String> deviceListIterator = supportedRobotTypes.iterator();
                while (deviceListIterator.hasNext()) {
                    byte[] asciBytes;
                    String devString = deviceListIterator.next();
                    asciBytes = devString.getBytes("UTF8");
                    LOG.info("\t"+ devString + " bytes: " + bytesToString(asciBytes));
                }
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        } else
            System.out.println ("ERROR: Could not load device list from properties file!!");

        startBLEDongle();
    }

    //**** RobotCommunicator Methods ****

    @Override
    void requestConnection(String name) {
        if (bgapi == null) {
            LOG.error("requestConnection: bgapi is null.");
            return;
        }
        // Find device by address from the scan list
        bledConnecting = devList.getFromName(name);
        if (bledConnecting != null) {
            LOG.info("Do_Connect_address: Device found");
            deviceConnecting = true;
        }else {
            LOG.error("Device " + name + " NOT FOUND");
            deviceConnecting = false;
        }

        //LOG.info("-----NEW CONNECTION START: connectToDevice sending INITIAL parameters: device: " + devInfo.deviceFancyName +" : " + devInfo.deviceName +" :  "+ interval_min + ", " + interval_max+ ", " + latency + ", " + timeout);
        //int addrType = devInfo.deviceAddressType;
        //LOG.info("BLEDevice: name: " + devInfo.deviceName + "  address: " + BDAddr.fromString(devInfo.deviceAddress) + "address Type = " + addrType);
        //new Thread(() -> bgapi.send_gap_connect_direct(BDAddr.fromString(devInfo.deviceAddress), addrType, interval_min, interval_max, latency,timeout)).start();
        //try { Thread.sleep(8000);} catch (Exception e) {}

        //bgapi.send_gap_connect_direct(BDAddr.fromString(devInfo.deviceAddress), addrType, interval_min, interval_max, latency,timeout);
        //LOG.info("-----NEW CONNECTION INITIATED. connectToDevice : {}", devInfo.deviceName);
        bgapi.send_gap_connect_direct(BDAddr.fromString(bledConnecting.getAddress()), addr_type, interval_min, interval_max, latency,timeout);
        //LOG.info("-----NEW CONNECTION INITIATED. connectToDevice : {}", devInfo.deviceName);
    }

    @Override
    void requestDisconnect(String address) {
        Integer index = robotIndexes.get(address);
        if (index != null) {
            bgapi.send_connection_disconnect(index);
        } else {
            LOG.error("Request to disconnect " + address + ". Robot not found.");
        }
    }

    @Override
    public boolean isRunning() {
        return (bgapi != null);
    }

    /**
     * connect to specified robot
     * @param devInfo - info about the device to connect
     */
    /*public void requestConnection(DeviceInfo devInfo) {
        if (bgapi == null) {
            LOG.error("requestConnection: bgapi is null.");
            return;
        }
        // Find device by address from the scan list
        bledConnecting = findDeviceByAddress(devInfo.deviceAddress);
        if (bledConnecting != null) {
            LOG.info("Do_Connect_address: Device found");
            deviceConnecting = true;
            bledConnecting.setDevLetter(devInfo.devLetter);
            devInfo.deviceFancyName = bledConnecting.getFancyName();
            devInfo.deviceName = bledConnecting.getName();
            devInfo.deviceAddressType = bledConnecting.getAddressType();
        }else {
            LOG.error("Device " + devInfo.deviceName + " NOT FOUND");
            deviceConnecting = false;
            //blueBirdDriver.deviceConnecting = false;
        }

        LOG.info("-----NEW CONNECTION START: connectToDevice sending INITIAL parameters: device: " + devInfo.deviceFancyName +" : " + devInfo.deviceName +" :  "+ interval_min + ", " + interval_max+ ", " + latency + ", " + timeout);
        int addrType = devInfo.deviceAddressType;
        LOG.info("BLEDevice: name: " + devInfo.deviceName + "  address: " + BDAddr.fromString(devInfo.deviceAddress) + "address Type = " + addrType);
        //new Thread(() -> bgapi.send_gap_connect_direct(BDAddr.fromString(devInfo.deviceAddress), addrType, interval_min, interval_max, latency,timeout)).start();
        //try { Thread.sleep(8000);} catch (Exception e) {}
        bgapi.send_gap_connect_direct(BDAddr.fromString(devInfo.deviceAddress), addrType, interval_min, interval_max, latency,timeout);
        LOG.info("-----NEW CONNECTION INITIATED. connectToDevice : {}", devInfo.deviceName);
    }*/

    /**
     * Disconnect from specified robot
     //* @param address
     */
    /*public void requestDisconnect(String address, int connection) {
        sendNotificationSetup(connection, false);
        blueBirdDriver.setHummingbirdNotifications(connection, false); // disable notifications on device before disconnecting
        bgapi.send_connection_disconnect(connection);
    }*///

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
    } //Start looking for robots

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
    @Override
    public void cancelConnectionRequest() {
        if (bgapi != null) {
            bgapi.send_gap_end_procedure();
        } else {
            LOG.error("cancelConnectionRequest: bgapi is null");
        }
    }

    @Override
    void sendCommand(String robotName, byte[] command) {
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
    void kill() {
        if (bgapi != null) {
            bgapi.removeListener(this);
            //bgapi.getLowLevelDriver().removeListener(logger);
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
                //port.close();
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
        //int attHandle = getAttHandleStr(connection, HB_WRITE_SVC_UUID, 0);
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

    //private void sendNotificationSetup(int connection, boolean enable) {
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
                //we will do the following in sethummingbirdnotifications
                //attHandle = getAttHandleStr(connection, HB_WRITE_SVC_UUID,  0);
                //data[0] = 0x62;
                //data[1] = 0x67;
                //LOG.info("Channel {}: Write to Att Handle Sync 0x{}, payload: {}", connection,  Integer.toHexString(attHandle), bytesToString(data));
                //doWriteHandle((byte)connection, attHandle, data);

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
            robotManager.receiveDisconnectionEvent(robot.getName(), false);
        }
    }

    /**
     * Proceedure for starting up a ble dongle. Called from within connect() which
     * is called within the WaitForConnection thread.
     * @return
     */
    private boolean startBLEDongle () {
        LOG.info("Connecting to BLED112. Resetting Dongle...");
        //connectionTable.clear(); //reset connecton table
        //blueBirdDriver.disconnect (false); //reset previous dongle connection
        //TODO: make sure another instance isn't already running?

        LOG.debug("disconnection complete, starting bluetooth dongle connection attempt");
        //String blueGigaPort = BLED112.selectSerialPort();//BLED112.selectSerialPort(true);
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
                //System.out.println("Connected on " + port);
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
    public void receive_system_reset() {}
    public void receive_system_hello() {}
    public void receive_system_address_get(BDAddr address) {}
    public void receive_system_reg_write(int result) {}
    public void receive_system_reg_read(int address, int value) {}
    public void receive_system_get_counters(int txok, int txretry, int rxok, int rxfail) {}
    public void receive_system_get_connections(int maxconn) {}
    public void receive_system_read_memory(int address, byte[] data) {}
    public void receive_system_get_info(int major, int minor, int patch, int build, int ll_version, int protocol_version, int hw) {
        LOG.info("Connected. BLED112:" + major + "." + minor + "." + patch + " (" + build + ") " + "ll=" + ll_version + " hw=" + hw);
    }
    public void receive_system_endpoint_tx() {}
    public void receive_system_whitelist_append(int result) {}
    public void receive_system_whitelist_remove(int result) {}
    public void receive_system_whitelist_clear() {}
    public void receive_system_boot(int major, int minor, int patch, int build, int ll_version, int protocol_version, int hw) {}
    public void receive_system_debug(byte[] data) {}
    public void receive_system_endpoint_rx(int endpoint, byte[] data) {
        LOG.debug("receive_system_endpoint_rx");
    }


    // Callbacks for class flash (index = 1)
    public void receive_flash_ps_defrag() {}
    public void receive_flash_ps_dump() {}
    public void receive_flash_ps_erase_all() {}
    public void receive_flash_ps_save(int result) {}
    public void receive_flash_ps_load(int result, byte[] value) {}
    public void receive_flash_ps_erase() {}
    public void receive_flash_erase_page(int result) {}
    public void receive_flash_write_words() {}
    public void receive_flash_ps_key(int key, byte[] value) {}


    // Callbacks for class attributes (index = 2)
    public void receive_attributes_write(int result) {}
    public void receive_attributes_read(int handle, int offset, int result, byte[] value) {
        LOG.debug("receive_attributes_read value: " + bytesToString(value));
    }
    public void receive_attributes_read_type(int handle, int result, byte[] value) {
        LOG.debug("receive_attributes_read_type att= " + Integer.toHexString(handle) + " val = " + bytesToString(value));
    }
    public void receive_attributes_user_response() {}
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

    public void receive_connection_get_rssi(int connection, int rssi) {}
    public void receive_connection_update(int connection, int result) {
        LOG.debug("receive_connection_update: connection: " + connection + " result: 0x" + Integer.toHexString(result));
    }

    public void receive_connection_version_update(int connection, int result) {}
    public void receive_connection_channel_map_get(int connection, byte[] map) {}
    public void receive_connection_channel_map_set(int connection, int result) {}
    public void receive_connection_features_get(int connection, int result) {}
    public void receive_connection_get_status(int connection) {
        LOG.debug("receive_connection_get_status: Connection: " + connection);
    }

    public void receive_connection_raw_tx(int connection) {
        LOG.debug("receive_connection_raw_TX");
    }
    //Second response after a connection is requested
    public void receive_connection_status(int conn, int flags, BDAddr address, int address_type, int conn_interval, int timeout, int latency, int bonding) {
        LOG.info("receive_connection_status " + "[" + address.toString() + "] Conn = " + conn + " Flags = " + flags + " address type = " + address_type + " interval = " + conn_interval + " timeout = " + timeout + " latency = " + latency + " bonding = " + bonding);

        if (!deviceConnecting) {
            LOG.error("!!!! Device is not in connecting state. Aborting connection attempt.");
            return;
        }

        try{
            if (flags == 0x05) {
                //this.connection = conn;
                LOG.info("Connection made. Connection number= " + conn);

                // Send device connection Event - all zeroes
                byte [] header = new byte[5];  //header with secondary address set to 1 means connection update.
                header[0] = (byte)0x00;
                header[1] = (byte)0x00;
                header[2] = (byte)0x00;
                header[3] = (byte)0x00;
                header[4] = (byte)0x00;
                //session.getRemote().sendBytes(ByteBuffer.wrap(header), null);

                //TODO: what is this?
                //Relay.getInstance().scratchWriteBytes(header, 0, 0);

                //sendNotificationSetup(conn, true);
                //listener.receiveConnectionEvent(conn, "", bledConnecting.getDevLetter());
                /*DeviceInfo deviceInfo = new DeviceInfo(bledConnecting);
                deviceInfo.deviceConnection = conn;
                listener.receiveConnectionEvent(deviceInfo);*/
                if (connectedDevices[conn] != null) {
                    LOG.error("DEVICE ALREADY CONNECTED?");
                } else {
                    connectedDevices[conn] = bledConnecting;
                    robotIndexes.put(bledConnecting.getName(), conn);
                    bledConnecting = null;
                }


                LOG.info("Discovering Services........");
                //discovery_state = SERVICES;

                byte [] uuid = new byte[2];
                uuid[0] = 0x03;
                uuid[1] = 0x28;

                characteristicDiscover[conn] = true;
                //Allocate hastable for this connection.
                primaryServiceTable = new Hashtable();
                //Associate address with connection

                //debug
                //LOG.debug("BP 1");

                descriptorsDiscover =false;
                serviceCount = 0;
                serviceAttHandleList.removeAll(serviceAttHandleList);

                //debug
                //LOG.debug("BP 2");

                //debug note
                // The new connection disconnects before it gets to here.
                // Sleeping before the next device IO did not matter
                // Conclusion: the device is disconnecting itself because of the connection condition.
                //try { Thread.sleep(5000);} catch (Exception e) {}

                bgapi.send_attclient_read_by_type(conn, 0x1, 0xffff, uuid);

                //bgapi.send_attclient_find_information (conn, 0x12, 0x14);
                LOG.info("receive_connection_status  function completed.");
                deviceConnecting = false;

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
                deviceConnecting = false;
                //blueBirdDriver.receiveConnectionLost(conn);

            }
        }catch (Exception e){
            LOG.error("Connection Error: {}",  stackTraceToString(e));
        }
    }
    public void receive_connection_version_ind(int connection, int vers_nr, int comp_id, int sub_vers_nr) {}
    public void receive_connection_feature_ind(int connection, byte[] features) {}
    public void receive_connection_raw_rx(int connection, byte[] data) {
        LOG.debug("receive_connection_raw_rx");
    }
    public void receive_connection_disconnected(int connection, int reason) {
        LOG.info("receive_connection_disconnected :  Connection: " + connection + "reason: 0x" + Integer.toHexString(reason));

        //Don't purge connection. Reuse data for the re-connection.
        //purgeConnection(connection);

        /*if (!blueBirdDriver.masterDisconnect[connection]) {  // This is a device disconnect
            listener.receiveDisconnectionEvent(connection,false);
        } else LOG.debug("This is a Master disconnection made by user");*/
        removeConnectionInfo(connection);
    }


    // Callbacks for class attclient (index = 4)
    public void receive_attclient_find_by_type_value(int connection, int result) {}
    public void receive_attclient_read_by_group_type(int connection, int result) {}

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
    public void receive_attclient_reserved() {}
    public void receive_attclient_read_long(int connection, int result) {}
    public void receive_attclient_prepare_write(int connection, int result) {}
    public void receive_attclient_execute_write(int connection, int result) {}
    public void receive_attclient_read_multiple(int connection, int result) {}
    public void receive_attclient_indicated(int connection, int attrhandle) {}

    //Fifth response after connection is requested
    public void receive_attclient_procedure_completed(int connection, int result, int chrhandle) {

        LOG.debug("\n receive_attclient_procedure_completed Result: " + Integer.toHexString(result) + " chrhandle: " + Integer.toHexString(result));

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

                TreeMap<Integer, AttHandleService> attHandles = new TreeMap<Integer, AttHandleService>();

                // Print out hashtable to check values
                Enumeration services = primaryServiceTable.keys();
                while(services.hasMoreElements()) {

                    //Integer service = (Integer) services.nextElement();
                    //System.out.println("Primary Service: 0x" + Integer.toHexString(service));

                    String service = (String) services.nextElement();
                    LOG.debug("Primary Service: 0x" + service);


                    Hashtable addresses = (Hashtable) primaryServiceTable.get(service);
                    Enumeration secondaryAddr = addresses.keys();
                    while (secondaryAddr.hasMoreElements()) {
                        Integer secondarySvc = (Integer) secondaryAddr.nextElement();
                        LOG.debug ("\t Secondary UUID: 0x" + Integer.toHexString(secondarySvc) + "\tAttribute Handle: 0x" + Integer.toHexString((Integer)addresses.get(secondarySvc)));

                        AttHandleService attHandleService = new AttHandleService();
                        attHandleService.primaryUUID = service;
                        attHandleService.secondaryUUID = secondarySvc;
                        attHandles.put((Integer)addresses.get(secondarySvc), attHandleService);

                        if (service.equals(WRITE_CHARACTERISTIC_UUID)) {
                            LOG.debug("Found write characteristic!");
                        } else if (service.equals(NOTIFY_CHARACTERISTIC_UUID)) {
                            LOG.debug("Found notify characteristic!");
                        }
                    }
                }



                LOG.debug("Atthandle: \tPrimary: \tSecondary");
                // Get a set of the entries
                Set set = attHandles.entrySet();
                // Get an iterator
                Iterator i = set.iterator();
                // Display elements
                while(i.hasNext()) {
                    Map.Entry me = (Map.Entry)i.next();
                    System.out.print("0x"+Integer.toHexString((Integer)me.getKey()) + ": ");
                    AttHandleService atthndl = (AttHandleService)attHandles.get((Integer)me.getKey());
                    //System.out.println("\t\t" +Integer.toHexString(atthndl.primaryUUID) + "     \t\t" + Integer.toHexString(atthndl.secondaryUUID));
                    LOG.debug("\t\t" + atthndl.primaryUUID + "     \t\t" + Integer.toHexString(atthndl.secondaryUUID));
                    //System.out.println((AttHandleService)me.get().secondaryUUID);
                }


                //Connection is complete, update the data structures
                //blueBirdDriver.addConnection(connection, primaryServiceTable, attHandles);
                if (connectedDevices[connection] != null) {
                    sendNotificationSetup(connectedDevices[connection], connection, true);
                }
                //sendNotificationSetup(connection, true);
                //blueBirdDriver.setHummingbirdNotifications(connection, true);

                //listener.receiveConnectionEvent(connection, "", blueBirdDriver.bledConnecting.getDevLetter());



                characteristicDiscover[connection] = false;
                descriptorsDiscover =false;

                // THis doesn't work. The device can override these settings
                //System.out.println("\nConnection sending UPDATE parameters: " + interval_min + ", " + interval_max+ ", " + latency + ", " + timeout);
                //bgapi.send_connection_update(connection, interval_min, interval_max, latency, timeout);
                //System.out.println("Done with bgapi.send_attclient_find_information");

            }
        } else  LOG.info("Attribute Write Event Response. Connection {}, Result: {}", connection ,Integer.toHexString(result));


        if (result != 0) {
            System.err.println("ERROR: Attribute Procedure Completed with error code 0x" + Integer.toHexString(result));
            LOG.error("ERROR: Attribute Procedure Completed with error code 0x" + Integer.toHexString(result));
            synchronized (readLock) {
                readResult = result;
                readLock.notify();
            }

            // Reset everthing
            descriptorsDiscover =false;
            characteristicDiscover[connection] =false;
            deviceConnecting = false;
            //blueBirdDriver.deviceConnecting = false;
            //blueBirdDriver.deviceReconnecting = false;//TODO: replace

            //Cancel whatever operation is happening
            bgapi.send_gap_end_procedure();
            //attrDiscoverLooping = false;

        }
        else
            LOG.info("receive_attclient_procedure_completed SUCCESSFULLY for connection {}", connection);
    }
    public void receive_attclient_group_found(int connection, int start, int end, byte[] uuid) {
        LOG.debug("receive_attclient_group_found");
        //TODO: something here? what is this function? Not called?
        /*if (blueBirdDriver.bledevice != null) {
            BLEService srv = new BLEService(uuid, start, end);
            blueBirdDriver.bledevice.getServices().put(srv.getUuidString(), srv);
        }*/
    }
    public void receive_attclient_attribute_found(int connection, int chrdecl, int value, int properties, byte[] uuid) {}

    public void receive_attclient_find_information_found(int connection, int chrhandle, byte[] uuid) {
        //Hashtable secondaryServiceTable;
        /*DeviceInfo devInfo = null;


        //Get the DeviceInfo from the connectionStatusTable
        if (blueBirdDriver.connectionStatusTable.connectedDeviceTable.containsKey(connection)) {
            devInfo = (DeviceInfo)(blueBirdDriver.connectionStatusTable.connectedDeviceTable.get(connection));
            //System.out.println("Device: " +  devInfo.deviceName + " Big Endian: " + devInfo.bigEndian);
        }*/


        LOG.debug("\n receive_attclient_find_information_found chrhandle: " + Integer.toHexString(chrhandle) + " uuid: " + bytesToString(uuid));

        String uuidString = bytesToUUIDString(uuid);
        LOG.debug("Found {}, looking for {}", uuidString, SERVICE_UUID);
        if (uuidString.equals(WRITE_CHARACTERISTIC_UUID) && connectedDevices[connection] != null) {
            LOG.debug("Setting TX handle to {}", chrhandle);
            connectedDevices[connection].setTxHandle(chrhandle);
        }


        if (discovery_state == ATTRIBUTES && discovery_srv != null) {
            BLEAttribute att = new BLEAttribute(uuid, chrhandle);
            discovery_srv.getAttributes().add(att);
        }

        /*if (!devInfo.bigEndian) {
            //reverseEndian(uuid);
            //System.out.println("Reversed uuid bytes: " + bytesToString(uuid));
        }*/

        if (descriptorsDiscover) {
            LOG.debug("Creating Service UUID to Attribute Hash Table");
            if (firstServiceAddress) {
                // The primary service will be converted to a string - The secondary services are 2 byte u integers
                //The secondary services are already big endian, so only convert the primary services if device is little endian.
                /*if (!devInfo.bigEndian) {
                    blueBirdDriver.reverseEndian(uuid);
                    LOG.debug("Reversed uuid bytes: " + bytesToString(uuid));
                }*/

                primaryAddress = bytesToUUIDString(uuid);
                LOG.debug("UUID String: " + primaryAddress);


                //primaryAddress = ((uuid[1] & 0xFF) << 8) + (uuid[0] & 0xFF);   //Unsigned Int SHift
                //System.out.println("First Service Address : " + Integer.toHexString(primaryAddress));
                LOG.debug("First Service Address : "  + primaryAddress);
                Hashtable secondaryServiceTable = new Hashtable();
                secondaryServiceTable.put(0, chrhandle);
                primaryServiceTable.put(primaryAddress, secondaryServiceTable);
                //primaryServiceTable.put(connection, new Hashtable());  //This appears to be unused.
                firstServiceAddress = false;
            } else {
                //System.out.println("Primary address: " + Integer.toHexString(primaryAddress));
                LOG.debug("Primary address: " + primaryAddress);
                int secondaryAddress = ((uuid[1] & 0xFF) << 8) + (uuid[0] & 0xFF);   //Unsigned Int SHift
                Hashtable table = (Hashtable) primaryServiceTable.get(primaryAddress);
                table.put(secondaryAddress, chrhandle);
                if (primaryAddress.equals(NOTIFY_CHARACTERISTIC_UUID) && secondaryAddress == HB_NOTIFY_CTL_CHAR && connectedDevices[connection] != null) {
                    LOG.debug("Setting RX handle to {}", chrhandle);
                    connectedDevices[connection].setRxHandle(chrhandle);
                }
            }
        }
    }
    //Fourth response after a connection is requested - characteristicDiscover
    public void receive_attclient_attribute_value(int connection, int atthandle, int type, byte[] value) {
        //System.out.println(System.currentTimeMillis());
        //System.out.println("Attclient Value atthandle= " + Integer.toHexString(atthandle) + " val = " + bytesToString(value) + " connection = " + Integer.toString(connection) + " type = " + Integer.toString(type));

        /*DeviceInfo devInfo = null;

        if (blueBirdDriver.connectionStatusTable.connectedDeviceTable.containsKey(connection)) {
            devInfo = (DeviceInfo)(blueBirdDriver.connectionStatusTable.connectedDeviceTable.get(connection));
            //System.out.println("Device: " +  devInfo.deviceName + " Big Endian: " + devInfo.bigEndian);
        }*/


        if (characteristicDiscover[connection]) {
            if (value.length > 0) {
                LOG.debug("Service found: " + bytesToString(value) + " AttHandle: " + atthandle + "\n");

                //It seems only service table data is little endian on the microbit
                /*if (!devInfo.bigEndian) {
                    blueBirdDriver.reverseEndian(value);
                    LOG.debug("Reversed bytes: " + bytesToString(value));
                }*/
                serviceAttHandleList.add(atthandle);
                    /* Used for restricting service address space. Not needed for agnositc use
                    if (((value[3] & 0xFF) > 0xb0) && ((value[4] & 0xFF ) == 0xff))  {
                        System.out.println("Service FFBx found");
                        serviceAttHandleList.add(atthandle);
                    } */
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
            byte [] header = new byte[5];
            byte [] data;

            //Send connection and att handle. CLient will calculate devType and devId
            header[0] = (byte)(connection);
            header[1] = (byte)atthandle;
            //TODO  Hummingbirds Reads require devLetter
            //TODO: I don't think this is being used at all...
            header[2] = (byte)0; //blueBirdDriver.getDevNumFromConnection (connection);

            // 0x55555555 is chosen for the generic notification magic number. This is not used - yet
            header[3] = (byte)((0x5555 >> 8) & 0xFF);
            header[4] = (byte)(0x5555 & 0xFF);
            data = Utilities.concatBytes(header, value);

            // Notification timer. Used for Calibration.
            //blueBirdDriver.observedNotificationInterval[connection] = System.currentTimeMillis() - blueBirdDriver.startTime[connection]; //timer

            // Used for notification timing analysis
            //LOG.debug("\n\n{}ms---------", observedNotificationInterval[connection]);
            //LOG.debug("{}ms: Incoming bytes: {} connection: {}", observedNotificationInterval[connection], bytesToString(ByteBuffer.wrap(data).array()), connection);

            // reset timer to now for next iteration
            //blueBirdDriver.startTime[connection] = System.currentTimeMillis();

            //Raw bytes from device sent directly to JavaScript Extension
            try {
                //Relay.getInstance().scratchWriteBytes(data, 0, 0); //TODO: ?
            } catch (Exception e) {
                System.out.println("ERROR: receive_attclient_attribute_value: Socket send error" + e.toString());
                e.printStackTrace();
            }

            // The incoming notification triggers the  SetAll write back to the device
            //if ((!blueBirdDriver.set_all_timer) && (blueBirdDriver.observedNotificationInterval[connection] > 2))
            //if (blueBirdDriver.observedNotificationInterval[connection] > 2)
            //    blueBirdDriver.sendSetAllConnection(connection);

            // Update GUI, the next ping timer will send status
            //blueBirdDriver.updateDongleConnected(true); //TODO: ?
            //blueBirdDriver.dataSending = true;

            //Snap! HTTP support  just copy the data into the global notificationData array
            //blueBirdDriver.updateNotificationData(connection, value);
            //listener.receiveNotification(connection, value);
            LOG.debug("Receive notification for {} with value {}.", connection, bytesToString(value));
            BLEDevice robot = connectedDevices[connection];
            if (robot != null) {
                if (value.length < 10 && value.length > 3 && robot.getMicrobitVersion() == 0) {
                    int version = (value[3] == 0x22) ? 2 : 1;
                    robot.setMicrobitVersion(version);
                    LOG.debug("Set microbit version for {} to {}", robot.getName(), robot.getMicrobitVersion());

                    //send poll start
                    byte[] pollStart = (version == 2) ? new byte[] { 0x62, 0x70 } : new byte[] { 0x62, 0x67 };
                    sendCommand(pollStart, connection);

                    //finally call the connection successful
                    robotManager.receiveConnectionEvent(robot.getName(), (version == 2));
                } else {
                    robotManager.receiveNotification(robot.getName(), value);
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
    public void receive_attclient_read_multiple_response(int connection, byte[] handles) {}

    // Callbacks for class sm (index = 5)
    public void receive_sm_encrypt_start(int handle, int result) {}
    public void receive_sm_set_bondable_mode() {}
    public void receive_sm_delete_bonding(int result) {}
    public void receive_sm_set_parameters() {}
    public void receive_sm_passkey_entry(int result) {}
    public void receive_sm_get_bonds(int bonds) {}
    public void receive_sm_set_oob_data() {}
    public void receive_sm_smp_data(int handle, int packet, byte[] data) {}
    public void receive_sm_bonding_fail(int handle, int result) {}
    public void receive_sm_passkey_display(int handle, int passkey) {}
    public void receive_sm_passkey_request(int handle) {}
    public void receive_sm_bond_status(int bond, int keysize, int mitm, int keys) {}


    // Callbacks for class gap (index = 6)
    public void receive_gap_set_privacy_flags() {}
    public void receive_gap_set_mode(int result) {
        LOG.debug("receive_gap_set_mode. Result: " + result + "  " + Integer.toString(result));
    }
    public void receive_gap_discover(int result) {
        LOG.debug("receive_gap_discover : Result = " + result);
        if (result == 0) {
            //listener.updateGUIScanStatus(true);
            frontendServer.updateGUIScanStatus(true);
        } else {
            //listener.updateGUIScanStatus(false);
            frontendServer.updateGUIScanStatus(false);
        }

    }
    //The first response after a connection is requested.
    public void receive_gap_connect_direct(int result, int connection_handle) {
        LOG.debug("receive_gap_connect_direct Result: {},  connection: {}" , result, connection_handle);

        //TODO: What is supposed to be happening here??
        if (result != 0)  {
            deviceConnecting = false;  // clear global flag
            //blueBirdDriver.deviceConnecting = false;
            /*blueBirdDriver.deviceReconnecting = false;

            blueBirdDriver.sendBlueBirdDriverStatus(); //TODO: need this here?

            //deviceInfo.deviceConnection = connection;
            //deviceInfo.connected = false;
            //sendStatus(deviceInfo);
            if (blueBirdDriver.connectionStatusTable.connectedDeviceTable.containsKey(connection))  //If key exists delete it and refresh
                blueBirdDriver.connectionStatusTable.connectedDeviceTable.remove(connection);
            blueBirdDriver.sendStatus(blueBirdDriver.connectionStatusTable);*/
        }
    }
    public void receive_gap_end_procedure(int result) {
        LOG.debug("receive_gap_end_procedure");
        //listener.updateGUIScanStatus(false);
        frontendServer.updateGUIScanStatus(false);
    }
    public void receive_gap_connect_selective(int result, int connection_handle) {}
    public void receive_gap_set_filtering(int result) {}
    public void receive_gap_set_scan_parameters(int result) {}
    public void receive_gap_set_adv_parameters(int result) {}
    public void receive_gap_set_adv_data(int result) {}
    public void receive_gap_set_directed_connectable_mode(int result) {}
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

                    JSONObject scanResponse = new JSONObject("{'packetType': 'discovery', 'name': "+ name +", 'rssi': "+ rssi +"}");
                    frontendServer.receiveScanResponse(name, scanResponse);
                }
            }
        }
    }
    public void receive_gap_mode_changed(int discover, int connect) {
        LOG.debug("receive_gap_mode_changed: " + discover + "  " + connect);
    }
    // Callbacks for class hardware (index = 7)
    public void receive_hardware_io_port_config_irq(int result) {}
    public void receive_hardware_set_soft_timer(int result) {}
    public void receive_hardware_adc_read(int result) {}
    public void receive_hardware_io_port_config_direction(int result) {}
    public void receive_hardware_io_port_config_function(int result) {}
    public void receive_hardware_io_port_config_pull(int result) {}
    public void receive_hardware_io_port_write(int result) {}
    public void receive_hardware_io_port_read(int result, int port, int data) {}
    public void receive_hardware_spi_config(int result) {}
    public void receive_hardware_spi_transfer(int result, int channel, byte[] data) {}
    public void receive_hardware_i2c_read(int result, byte[] data) {}
    public void receive_hardware_i2c_write(int written) {}
    public void receive_hardware_set_txpower() {}
    public void receive_hardware_io_port_status(int timestamp, int port, int irq, int state) {}
    public void receive_hardware_soft_timer(int handle) {}
    public void receive_hardware_adc_result(int input, int value) {}

    // Callbacks for class test (index = 8)
    public void receive_test_phy_tx() {}
    public void receive_test_phy_rx() {}
    public void receive_test_phy_end(int counter) {}
    public void receive_test_phy_reset() {}
    public void receive_test_get_channel_map(byte[] channel_map) {}

    public void serialError() {
        robotManager.updateCommunicatorStatus(false);
    }
    //******** end BGAPIListener methods ********//

    public class AttHandleService{
        //int primaryUUID;
        public String primaryUUID;
        public int secondaryUUID;
    }

    /**
     *
    // * @param address
     * @return
     */
    /*private BLEDevice findDeviceByAddress(String address) {
        //Get list of devices via discovery
        LOG.info("Searching Device List  for Device Address: " + address);
        for (int i = 0; i < devList.getSize(); i++) {
            LOG.info("Device: " + devList.getElementAt(i).toString());
            if ((devList.getElementAt(i).getAddress().equals(address))) {
                LOG.info("Found Device: " + devList.getElementAt(i).toString());
                return ((BLEDevice)devList.getElementAt(i));
            }
        }

        return null;
    }*/


/*    public int getAttHandleStr(int connection, String primaryUUID, int secondaryUUID) {
        Hashtable table;
        Hashtable secondaryTable;
        //System.out.println("getAttHandle params: Connection: " + connection + " primaryUUID: " + primaryUUID + " secondaryUUID " + secondaryUUID);
        if (blueBirdDriver.connectionTable.containsKey(connection)){
            BlueBirdDriver.connectionObj connObj = (BlueBirdDriver.connectionObj)(blueBirdDriver.connectionTable.get(connection));
            table = (Hashtable)connObj.primaryServiceTable;
        } else {
            LOG.error("Could not find connection {} in connection table", connection);
            return -1;
        }

        if (table.containsKey(primaryUUID))
            secondaryTable = (Hashtable)table.get(primaryUUID);
        else {
            LOG.error("Could not find secondary table for connection " + connection);
            return -1;
        }

        if (secondaryTable.containsKey(secondaryUUID))
            return (Integer)secondaryTable.get(secondaryUUID);
        else {
            LOG.error("Could not find secondary UUID for connection " + connection);
            return -1;
        }
        //System.out.println(((Hashtable)table.get(primaryUUID)).get(secondaryUUID));
    }*/



////////******** SerialErrorListener Method ********////////

    /*@Override
    public void onSerialError() {
        LOG.error("Serial Error");
        listener.receiveCommDisconnectionEvent();
    }*/

}



