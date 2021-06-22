package com.birdbraintechnologies.bluebirdconnector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import static com.birdbraintechnologies.bluebirdconnector.Utilities.stackTraceToString;

public class WinBLE implements RobotCommunicator {

    static final Logger LOG = LoggerFactory.getLogger(WinBLE.class);

    private RobotManager robotManager = RobotManager.getSharedInstance();
    private FrontendServer frontendServer = FrontendServer.getSharedInstance();
    private long notificationStartTime; //TODO: remove?
    //private boolean deviceConnecting;
    private Deque<String> connectionQueue = new ArrayDeque<String>();

    private final ProcessBuilder processBuilder = new ProcessBuilder();
    private Process process;
    private BufferedReader notificationPipe;
    private BufferedWriter send;

    //keep track of the current connection attempt
    //boolean deviceConnecting;
    String deviceConnecting;

    //private static final int DATA_PACKET_SIZE = 14;
    //byte [] incomingDataPacket = new byte[DATA_PACKET_SIZE];

    public WinBLE() {
        //super(manager);
        LOG.info("WinBLE Constructor");
        //this.deviceConnecting = false;

        String userDir = System.getProperty("user.dir");
        LOG.info("Working dir: {}", userDir);

        processBuilder.command(userDir + "\\BlueBirdWindowsCL.exe");
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE); // Manage IO streams

        LOG.info("WinBLE process builder built.");

        try {

            // start BLE process
            process = processBuilder.start();

            // set up data stream going to BLE driver i.e. commands to driver
            send = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

            LOG.info ("Starting notification thread...");
            //  Notification data receive thread
            Thread notificationThread = new Thread(){
                @Override
                public void run() {

                    LOG.info("-------------------- Output of notification pipe: -------------------------");
                    notificationPipe = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    notificationPipe.lines().forEach(line -> blePacketReceived(line));

                    LOG.error("!!! NativeBLE Notification Receive thread quit !!!");
                }
            };
            notificationThread.start();
        } catch (Exception e) {
            LOG.error("startWinBLEProcess() Exception: {} {}", e.toString(), stackTraceToString(e));
        }

        LOG.info("WinBLE Process started");
    }

    public boolean isRunning() {
        return (process != null && process.isAlive());
    }

    public void startDiscovery(){
        // Assemble the command.
        String command = "startScan" + "\n";
        LOG.info("startDiscovery command: {}", command);
        writeBLE(command);
        frontendServer.updateGUIScanStatus(true);
    }

    public void stopDiscovery(){
        // Assemble the command.
        String command = "stopScan\n";
        LOG.info("stopDiscovery command: {}", command);
        writeBLE(command);
        frontendServer.updateGUIScanStatus(false);
    }

    public void sendCommand(String name, byte[] command){
        //encode UInt8 array to base64 String
        String blob = Base64.getEncoder().encodeToString(command);//https://howtodoinjava.com/array/convert-byte-array-string-vice-versa/

        // Assemble the command. Note the command "sendBlob" takes the encoded array as a string parameter
        String cmd = "sendBlob " + name + " " + blob + "\n";
        LOG.debug("Send win command: {}", cmd);
        writeBLE(cmd);
    }

    private void writeBLE(String str){
        //LOG.debug("writeBLE: writing: {}", str);
        try {
            send.write(str);
            send.flush();
        } catch (Exception e) {
            LOG.error("writeBLE(): Exception: {} {}", e.toString(), stackTraceToString(e));
        }
    }

    public void disconnect() {

    }


    public void kill() {
        LOG.info("Killing Windows native ble driver");
        try {
            Thread.sleep(1000); //Give some tasks a chance to finish up
            writeBLE("quit\n");
            Thread.sleep(1000); //Give the process a chance to quit normally
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        if (process.isAlive()) { process.destroy(); }

        try {
            LOG.info("killNativeBLEDriver(): closing IO streams...");
            notificationPipe.close();
            send.close();
        }
        catch (IOException e) {
            LOG.error("killNativeBLEDriver(): Exception closing IO streams {}", stackTraceToString(e));
        }
    }

    // Receives communications from the BlueBirdNative driver
    private void blePacketReceived(String packetData){
        long notification_ms = System.currentTimeMillis() - notificationStartTime;
        //LOG.debug("Time since last notification: {} ms", notification_ms);
        notificationStartTime = System.currentTimeMillis();  // reset timer to now for next iteration
        try {
            //Parse JSON
            JSONObject root = new JSONObject(packetData);
            String packetType = root.getString("packetType");

            switch (packetType) {
                case "quit":
                    String reason = root.getString("reason");
                    LOG.info("BlueBirdWindowsCL process has quit due to '" + reason + "'.");
                    break;
                case "ping":
                    LOG.debug("BlueBirdWindowsCL process returned ping.");
                    break;
                case  "notification" :
                    String peripheralName = root.getString("peripheral");
                    String peripheralData = root.getString("data");
                    String[] hexArray = peripheralData.split("-");
                    byte[] bytes = new byte[hexArray.length];
                    for (int i = 0; i < hexArray.length; i++){
                        bytes[i] = (byte)Integer.parseInt(hexArray[i], 16);
                    }
                    robotManager.receiveNotification(peripheralName, bytes);
                    break;
                case  "discovery" :
                    peripheralName = root.getString("name");
                    if (!(peripheralName.startsWith("FN") || peripheralName.startsWith("BB") || peripheralName.startsWith("MB"))){
                        break;
                    }
                    frontendServer.receiveScanResponse(peripheralName, root);
                    break;
                case  "bluetoothState" :
                    String bleStatus = root.getString("status");
                    LOG.info("blePacketReceived(): bluetoothStatus: {}", bleStatus);
                    boolean isAvailable = !bleStatus.equals("unavailable");
                    boolean isOn = bleStatus.equals("on");
                    FrontendServer.getSharedInstance().updateBleStatus(isAvailable, isOn);
                    break;
                case  "connection" :
                    String status = root.getString("status");
                    peripheralName = root.getString("peripheral");
                    String hasV2String = root.getString("hasV2");
                    switch (status) {
                        case "connected":
                            LOG.info("blePacketReceived():Connection: connected, Peripheral: {}, hasV2: {}", peripheralName, hasV2String);
                            deviceConnecting = null;
                            boolean hasV2 = hasV2String.equals("True");
                            RobotManager.getSharedInstance().receiveConnectionEvent(peripheralName, hasV2);
                            break;
                        case "userDisconnected":  // The device was disconnected by the user
                            RobotManager.getSharedInstance().receiveDisconnectionEvent(peripheralName, true);
                            LOG.info("blePacketReceived() userDisconnected: Peripheral: {}", peripheralName);
                            break;
                        case "deviceDisconnected":  // The device disconnected itself i.e. power cut or out of range.
                            RobotManager.getSharedInstance().receiveDisconnectionEvent(peripheralName, false);
                            break;
                    }
                    break;
                case "ERROR":
                    String message = root.getString("message");
                    LOG.error("Error received from BlueBirdWindowsCL: " + message);
                    break;
                default:
                    LOG.error ("Invalid packet type '{}' received from BlueBirdWindowsCL", packetType);
            }
        } catch (Exception e) {
            LOG.error("Error! packet: " + packetData);
            System.out.println("blePacketReceived: JSON Parsing exception:" + e.toString());
            e.printStackTrace();
        }
    }

    public void requestDisconnect(String address) {
        writeBLE("disconnect " + address + "\n");
    }

    /**
     * Request a connection to specified robot
     * @param robotName - name of the device to connect
     */
    public void requestConnection(String robotName){
        LOG.info("Requesting Windows Native BLE connection to {}.", robotName);

        // Launch the connection attempt in a separate thread so it doesn't block the next connection request.
        new Thread(() -> processConnectionRequest(robotName)).start();

    }

    //TODO
    public void cancelConnectionRequest() {

    }

    /**
     * Process a request to connect to a robot over MacOS native ble.
     * If there is a queue, add it. If the queue is empty, connect immediately.
     * @param name - name of device to connect
     */
    private void processConnectionRequest(String name) {

        if (connectionQueue.isEmpty() && (deviceConnecting == null)) {
            deviceConnecting = name;
            sendConnectionRequest(name);
        }
        else {
            LOG.info("Adding connection request: {}  to queue", name);
            connectionQueue.add(name);  // Place in queue (FIFO)
        }

    }
    private void sendConnectionRequest(String address){
        LOG.info("sendConnectionRequest() command: connect {}", address);
        writeBLE("connect " + address + "\n");
        // Wait for the response
        final WinBLE.WaitForConnectionResponse waitForConnectionResponse = new WinBLE.WaitForConnectionResponse();
        Thread thread = new Thread(waitForConnectionResponse);
        try {
            thread.start();
            thread.sleep(7000);  // Blocks for 2 sec or when response is received, whichever comes first.
            if (thread.isAlive()) {
                waitForConnectionResponse.quit(); // No response, kill the thread and continue
            }
        } catch (InterruptedException e) {
            LOG.info("waitForConnectionResponse(): InterruptedException: {}", e.toString());
        }
    }

    /**
     * Once a connect request is sent, this thread is started to
     * wait for a response.
     */
    private class WaitForConnectionResponse extends Thread {
        boolean quit = false;

        @Override
        public void run() {
            LOG.info("Thread WaitForConnectionResponse" + this.getName() + " started");
            while ((deviceConnecting != null) && !quit) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    LOG.info("Thread WaitForConnectionResponse" + this.getName() + " interrupted");
                    execConnectionStatus();
                }
            }
            LOG.info("Thread WaitForConnectionResponse" + this.getName() + " exiting");
            execConnectionStatus();
        }

        private void quit() {
            LOG.error("WaitForConnectionResponse() Timeout: Connection attempt did not complete in time");
            this.quit = true;
            deviceConnecting = null;
        }

        /***
         *  Perform action based on whether connected successfully or timed out (fail)
         **/
        private void execConnectionStatus() {
            boolean success = !this.quit && (deviceConnecting == null); // The thread didn't time out and device has completed connecting.

            if (success) {
                LOG.info("execConnectionStatus() Connection SUCCESS");
            } else {
                LOG.error("execConnectionStatus() Connection ERROR");
                if (deviceConnecting != null) {
                    RobotManager.getSharedInstance().receiveDisconnectionEvent(deviceConnecting, false);
                }
            }
            this.quit = true;
            deviceConnecting = null;
            tryNextConnection();
        }

        /**
         * MacOS native ble only. Looks in the queue for a pending connection and
         * attempts to connect if one is found.
         */
        private void tryNextConnection() {
            LOG.info("tryNextConnection(): ");
            if (connectionQueue.isEmpty()) {
                LOG.info("Connection queue empty. Nothing to do.");
            } else {
                String connReq = connectionQueue.remove();
                deviceConnecting = connReq;
                // Launch the connection thread with status/timeout
                sendConnectionRequest(connReq);
            }
        }
    }
}
