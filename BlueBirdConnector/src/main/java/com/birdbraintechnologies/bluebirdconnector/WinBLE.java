package com.birdbraintechnologies.bluebirdconnector;

import java.io.*;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Date;
import java.util.Deque;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static com.birdbraintechnologies.bluebirdconnector.Utilities.stackTraceToString;

public class WinBLE implements RobotCommunicator {

    static final Log LOG = Log.getLogger(WinBLE.class);

    private static String START_SCAN = "startScan\n";
    private static String STOP_SCAN = "stopScan\n";

    private RobotManager robotManager = RobotManager.getSharedInstance();
    private FrontendServer frontendServer = FrontendServer.getSharedInstance();
    //private long notificationStartTime; //TODO: remove?
    //private boolean deviceConnecting;
    private Deque<String> connectionQueue = new ArrayDeque<String>();

    private final ProcessBuilder processBuilder;
    private Process process;
    private BufferedReader notificationPipe;
    private BufferedWriter send;

    //keep track of the current connection attempt
    //boolean deviceConnecting;
    String deviceConnecting;

    private boolean setupComplete = false;
    private boolean bleIsOn = false;

    public WinBLE() {
        //super(manager);
        LOG.info("WinBLE Constructor");
        //this.deviceConnecting = false;

        String userDir = System.getProperty("user.dir");
        LOG.info("Working dir: {}", userDir);

        processBuilder = new ProcessBuilder();
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
                    try {
                        notificationPipe.lines().forEach(line -> new Thread(() -> blePacketReceived(line)).start());
                    } catch (Exception e) {
                        LOG.error("Failed to read from WinBLE. Exception: " + e.getMessage());
                        new Thread(() -> kill()).start();
                    }

                    LOG.info("WinBLE notification thread is shutting down.");
                }
            };
            notificationThread.start();
        } catch (Exception e) {
            LOG.error("startWinBLEProcess() Exception: {} {}", e.toString(), stackTraceToString(e));
        }

        writeBLE(START_SCAN);
        Long startTime = System.currentTimeMillis();
        while (!setupComplete && (System.currentTimeMillis() < startTime + 5000)) {
            try {
                Thread.sleep(500);
            } catch (Exception e) {
                LOG.error("Sleep exception: {}; {}", e.getMessage(), stackTraceToString(e));
            }
        }
        writeBLE(STOP_SCAN);

        LOG.info("WinBLE Process started");
    }

    public boolean isRunning() {
        return (process != null && process.isAlive() && bleIsOn);
    }

    public void startDiscovery(){
        writeBLE(START_SCAN);
        frontendServer.updateGUIScanStatus(true);
    }

    public void stopDiscovery(){
        writeBLE(STOP_SCAN);
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
        if (send == null) {
            LOG.debug("writeBLE: send is null. Cannot send '{}'.", str);
            return;
        }
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
            LOG.error("sleep interrupted: {} - {}", e.getMessage(), stackTraceToString(e));
        }

        if (process != null) { //&& process.isAlive()) { process.destroy(); }
            process.destroy();
            process = null;
            LOG.debug("process destroyed.");
        }

        try {
            LOG.info("kill(): closing IO streams...");
            if (notificationPipe != null) {
                notificationPipe.close();
                notificationPipe = null;
                LOG.debug("notification pipe closed.");
            }
            if (send != null) {
                send.close();
                send = null;
                LOG.debug("send closed.");
            }
        } catch (IOException e) {
            LOG.error("kill(): Exception closing IO streams {}", stackTraceToString(e));
        }


        LOG.debug("kill(): everything is closed");
    }

    // Receives communications from the BlueBirdNative driver
    private void blePacketReceived(String packetData){
        //LOG.debug("PACKET: " + packetData);
        //long notification_ms = System.currentTimeMillis() - notificationStartTime;
        //LOG.debug("Time since last notification: {} ms", notification_ms);
        //notificationStartTime = System.currentTimeMillis();  // reset timer to now for next iteration
        try {
            //Parse JSON
            JsonObject root = JsonParser.parseString(packetData).getAsJsonObject();
            String packetType = root.get("packetType").getAsString();

            switch (packetType) {
                case "quit":
                    String reason = root.get("reason").getAsString();
                    LOG.info("BlueBirdWindowsCL process has quit due to '" + reason + "'.");
                    break;
                case "ping":
                    LOG.debug("BlueBirdWindowsCL process returned ping.");
                    break;
                case  "notification" :
                    String peripheralName = root.get("peripheral").getAsString();
                    String peripheralData = root.get("data").getAsString();
                    String[] hexArray = peripheralData.split("-");
                    byte[] bytes = new byte[hexArray.length];
                    for (int i = 0; i < hexArray.length; i++){
                        bytes[i] = (byte)Integer.parseInt(hexArray[i], 16);
                    }
                    robotManager.receiveNotification(peripheralName, bytes, null /* rssi unknown */);
                    break;
                case  "discovery" :
                    peripheralName = root.get("name").getAsString();
                    if (!(peripheralName.startsWith("FN") || peripheralName.startsWith("BB") || peripheralName.startsWith("MB"))){
                        break;
                    }
                    frontendServer.receiveScanResponse(peripheralName, root);
                    break;
                case  "bluetoothState" :
                    String bleStatus = root.get("status").getAsString();
                    LOG.info("blePacketReceived(): bluetoothStatus: {}", bleStatus);
                    boolean isAvailable = !bleStatus.equals("unavailable");
                    bleIsOn = bleStatus.equals("on");
                    setupComplete = true;
                    robotManager.updateCommunicatorStatus(bleIsOn, isAvailable);
                    break;
                case  "connection" :
                    String status = root.get("status").getAsString();
                    peripheralName = root.get("peripheral").getAsString();
                    String hasV2String = root.get("hasV2").getAsString();
                    switch (status) {
                        case "connected":
                            LOG.info("blePacketReceived():Connection: connected, Peripheral: {}, hasV2: {}", peripheralName, hasV2String);
                            deviceConnecting = null;
                            boolean hasV2 = hasV2String.equals("True");
                            RobotManager.getSharedInstance().receiveConnectionEvent(peripheralName, hasV2, null /* rssi unknown */);
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
                    String message = root.get("message").getAsString();
                    LOG.error("Error received from BlueBirdWindowsCL: " + message);
                    break;
                case "DEBUG":
                    String debugMsg = root.get("message").getAsString();
                    LOG.debug("Message from BlueBirdWindowsCL: " + debugMsg);
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
    // public void cancelConnectionRequest() {

    // }

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
