package com.birdbraintechnologies.bluebirdconnector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;

public class WinBLE extends RobotCommunicator {

    static final Logger LOG = LoggerFactory.getLogger(WinBLE.class);
    //private final ProcessBuilder pipeProcessBuilder = new ProcessBuilder();
    private final ProcessBuilder processBuilder = new ProcessBuilder();
    //private Process pipeProcess;
    private Process process;
    private BufferedReader notificationPipe;

    private BufferedWriter send;

    //private BlueBirdDriver blueBirdDriver;

    //keep track of the current connection attempt
    boolean deviceConnecting;

    private static final int DATA_PACKET_SIZE = 14;
    byte [] incomingDataPacket = new byte[DATA_PACKET_SIZE];

    //public WinBLE(BlueBirdDriver blueBirdDriver) {
        //super(blueBirdDriver);
    public WinBLE(RobotManager manager) {
        super(manager);
        //this.type = CommType.bleWinNative;
        LOG.info("WinBLE Constructor");
        this.deviceConnecting = false;
        //this.blueBirdDriver = blueBirdDriver;

        //blueBirdDriver.connectionTable.clear();
        manager.connectionTable.clear();

        LOG.info("WinBLE about to look for resource dir");

        //Get the nativeBLE directory path
        /*ClassLoader loader = Thread.currentThread().getContextClassLoader();
        String resourcePath = "winNativeBLE";
        String loaderPath = null;
        try {
            LOG.info("WinBLE in try block. loader==null? " + (loader == null));
            loaderPath = loader.getResource(resourcePath).toURI().getPath();
            resourcePath = loaderPath;
        } catch (Exception e) {
            LOG.error("Error getting the resource path...");
            e.printStackTrace();

            String userDir = System.getProperty("user.dir");
            LOG.info("Working dir: {}", userDir);
            resourcePath = userDir + "\\" + resourcePath;
        }

        LOG.info("Found winNativeBLE dir at path " + resourcePath);*/


        // Run the nativeBLE driver
        //processBuilder.directory(new File("nativeBLE")); // set execution dir
        //processBuilder.directory(new File(resourcePath)); // set execution dir


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

    @Override
    boolean robotFound() {
        LOG.error("robotFound: Method not implemented in WinBLE");
        return false;
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

    @Override
    void startCalibration(int connection) {
        LOG.error("startCalibration: Method not implemented in WinBLE");
    }

    @Override
    void stopCalibration() {
        LOG.error("stopCalibration: Method not implemented in WinBLE");
    }

    @Override
    void stopFirmwareUpgrade() {
        LOG.error("stopFirmwareUpgrade: Method not implemented in WinBLE");
    }

    public void sendCommand(byte[] command, int connection){
        //encode UInt8 array to base64 String
        /*char devLetter = blueBirdDriver.getDevLetterFromConnection(connection);
        String blob = Base64.getEncoder().encodeToString(command);//https://howtodoinjava.com/array/convert-byte-array-string-vice-versa/
        String name = blueBirdDriver.getAddressFromDevLetter(devLetter);
        // Assemble the command. Note the BlueBirdNative command "sendBlob" takes the encoded array as a string parameter
        String cmd = "sendBlob " + name + " " + blob + "\n";
        LOG.debug("Send win command: {}", cmd);
        writeBLE(cmd);*/
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
            //receive.close();
            notificationPipe.close();
            send.close();
        }
        catch (IOException e) {
            LOG.error("killNativeBLEDriver(): Exception closing IO streams {}", stackTraceToString(e));
        }
    }

    // Receives communications from the BlueBirdNative driver
    private void blePacketReceived(String packetData){
        /*long notification_ms = System.currentTimeMillis() - notificationStartTime;
        //LOG.debug("Time since last notification: {} ms", notification_ms);
        notificationStartTime = System.currentTimeMillis();  // reset timer to now for next iteration
        try {
            //Parse JSON
            JsonNode root = mapper.readTree(packetData);
            JsonNode packet = root.path("packetType");
            int connection = -1;

            if (!packet.isMissingNode()) {
                String packetType = packet.asText();
                switch (packetType) {
                    case "quit":
                        JsonNode reason = root.path("reason");
                        LOG.info("BlueBirdNative process has quit due to '" + reason.asText() + "'.");
                        break;
                    case "ping":
                        LOG.debug("BlueBirdNative process returned ping.");
                        break;
                    case  "notification" :
                        JsonNode peripheral = root.path("peripheral");
                        String peripheralName = peripheral.asText();
                        JsonNode data = root.path("data");
                        String peripheralData = data.asText();
                        //LOG.debug("blePacketReceived(): Peripheral: {},  Data: {}", peripheralName, peripheralData);
                        //byte[] bytes = Base64.getDecoder().decode(peripheralData);
                        String[] hexArray = peripheralData.split("-");
                        byte[] bytes = new byte[hexArray.length];
                        for (int i = 0; i < hexArray.length; i++){
                            bytes[i] = (byte)Integer.parseInt(hexArray[i], 16);
                        }
                        //LOG.debug("Notification bytes: " + bytesToString(bytes));
                        JsonNode devLetterNode = root.path("devLetter");
                        String devLetterStr = devLetterNode.asText();
                        char devLetter = devLetterStr.charAt(0);
                        connection = blueBirdDriver.mapDevLetterToConnection(devLetter);

                        listener.receiveNotification(connection, bytes);
                        break;
                    case  "discovery" :
                        peripheral = root.path("peripheral");
                        peripheralName = peripheral.asText();
                        if (!(peripheralName.startsWith("FN") || peripheralName.startsWith("BB") || peripheralName.startsWith("MB"))){
                            break;
                        }
                        JsonNode rssiNode = root.path("rssi");
                        ActiveDeviceInfo activeDevice = new ActiveDeviceInfo();
                        activeDevice.name = peripheralName;
                        activeDevice.fancyName = blueBirdDriver.getDeviceFancyName(peripheralName);
                        activeDevice.address = peripheralName; // macos native address is peripheral name i.e. BBxxxxx
                        activeDevice.rssi = rssiNode.asInt();
                        LOG.debug("blePacketReceived():discovery: Peripheral: {},  rssi: {}", peripheralName, activeDevice.rssi);
                        listener.receiveScanResponse(activeDevice);
                        break;
                    case  "bluetoothState" :
                        JsonNode bleStatusNode = root.path("status");
                        String bleStatus = bleStatusNode.asText();
                        LOG.info("blePacketReceived(): bluetoothStatus: {}", bleStatus);
                        listener.updateBleStatus(bleStatus);

                        break;
                    case  "connection" :
                        JsonNode statusNode = root.path("status");
                        String status = statusNode.asText();
                        peripheral = root.path("peripheral"); // The address i.e. BBxxxxxx
                        peripheralName = peripheral.asText();
                        devLetterNode = root.path("devLetter");
                        devLetterStr = devLetterNode.asText();
                        devLetter = devLetterStr.charAt(0);
                        connection = blueBirdDriver.mapDevLetterToConnection(devLetter);
                        switch (status) {
                            case "connected":
                                LOG.info("blePacketReceived():Connection: connected, Peripheral: {}, devLetter: {}", peripheralName, devLetter);
                                deviceConnecting = false;
                                DeviceInfo deviceInfo = new DeviceInfo();
                                deviceInfo.deviceConnection = connection;
                                deviceInfo.deviceName = peripheralName;
                                deviceInfo.deviceAddress = peripheralName;
                                deviceInfo.devLetter = devLetter;
                                deviceInfo.deviceFancyName = blueBirdDriver.getDeviceFancyName(peripheralName);
                                //listener.receiveConnectionEvent(connection, peripheralName, devLetter);
                                listener.receiveConnectionEvent(deviceInfo);
                                break;
                            case "userDisconnected":  // The device was disconnected by the user
                                listener.receiveDisconnectionEvent(connection, true);
                                LOG.info("blePacketReceived() userDisconnected: Peripheral: {}, devLetter: {}, connection: {}", peripheralName, devLetter, connection);
                                break;
                            case "deviceDisconnected":  // THe device disconnected itself i.e. power cut or out of range.
                                listener.receiveDisconnectionEvent(connection, false);
                                break;
                        }
                        break;
                    case "ERROR":
                        String message = root.path("message").asText();
                        LOG.error("Ble packet error: " + message);
                    default:
                        LOG.error ("Invalid packet type received from BlueBirdNative Driver");
                }
            } else
                LOG.error ("Invalid data received from BlueBirdNative Driver. packet: " + packetData);
        } catch (Exception e) {
            LOG.error("Error! packet: " + packetData);
            System.out.println("blePacketReceived: JSON Parsing exception:" + e.toString());
            e.printStackTrace();
        }*/
    }

    public void requestDisconnect(String address, int connection) {
        writeBLE("disconnect " + address + "\n");
    }

    /**
     * Request a connection to specified robot
     * @param deviceInfo - info about the device to connect
     */
    public void requestConnection(RobotInfo deviceInfo){
        /*LOG.info("Requesting Native MacOS Connection to {} at position {}", deviceInfo.deviceAddress, deviceInfo.devLetter);
        ConnectionRequestObj connReq = new ConnectionRequestObj ();
        connReq.address = deviceInfo.deviceAddress;
        connReq.devLetter = deviceInfo.devLetter;*/
        LOG.info("Requesting Windows Native BLE connection to {} at position {}", deviceInfo.deviceName, deviceInfo.devLetter);

        // Launch the connection attempt in a separate thread so it doesn't block the next connection request.
        new Thread(() -> processConnectionRequest(deviceInfo)).start();

    }

    //TODO
    public void cancelConnectionRequest() {

    }

    /**
     * Process a request to connect to a robot over MacOS native ble.
     * If there is a queue, add it. If the queue is empty, connect immediately.
     * @param connReq - request object
     */
    private void processConnectionRequest(RobotInfo connReq) {

        if (connectionQueue.isEmpty() && !deviceConnecting) {
            deviceConnecting = true;
            sendConnectionRequest(connReq.deviceName, connReq.devLetter);
        }
        else {
            LOG.info("Adding connection request: address: {}  Device: {}  to queue", connReq.deviceName, connReq.devLetter);
            connectionQueue.add(connReq);  // Place in queue (FIFO)
        }

    }
    private void sendConnectionRequest(String address, char devLetter){
        LOG.info("sendConnectionRequest() command: connect " + address + " " + devLetter);
        writeBLE("connect " + address + " " + devLetter + "\n");
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
            while (deviceConnecting && !quit) {
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
            deviceConnecting = false;
        }

        /***
         *  Perform action based on whether connected successfully or timed out (fail)
         **/
        private void execConnectionStatus() {
            boolean success = !this.quit && !deviceConnecting; // The thread didn't time out and device has completed connecting.

            if (success) {
                LOG.info("execConnectionStatus() Connection SUCCESS");
            } else {
                LOG.error("execConnectionStatus() Connection ERROR");
            }
            this.quit = true;
            deviceConnecting = false;
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
                deviceConnecting = true;
                RobotInfo connReq = connectionQueue.remove();
                // Launch the connection thread with status/timeout
                sendConnectionRequest(connReq.deviceName, connReq.devLetter);
            }
        }
    }
}
