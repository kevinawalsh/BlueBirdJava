package com.birdbraintechnologies.bluebirdconnector;

import javafx.application.Platform;
import netscape.javascript.JSObject;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;
import java.awt.Desktop;

public class FrontendServer {

    static final Logger LOG = LoggerFactory.getLogger(FrontendServer.class);

    private static FrontendServer sharedInstance;
    private RobotManager robotManager = RobotManager.getSharedInstance();
    private JSObject callbackManager;
    private Hashtable<String, String> availableRobots;
    private boolean autoconnectRequested = false;
    private Queue<Runnable> pendingMessages = new LinkedList<>();

    private FrontendServer(){
        availableRobots = new Hashtable<>();
    }

    public static FrontendServer getSharedInstance() {
        if (sharedInstance == null) {
            sharedInstance = new FrontendServer();
        }
        return sharedInstance;
    }

    public void setCallbackManager(JSObject cbManager) {
        LOG.debug("Setting callbackManager. There are {} pending messages.", pendingMessages.size());
        callbackManager = cbManager;
        Runnable update = pendingMessages.poll();
        while (update != null) {
            update.run();
            update = pendingMessages.poll();
        }
    }

    public void setTranslationTable(String language) {
        sendToGUI("setTranslationTable", language);
    }

    public void updateBleStatus(boolean isOn) {
        if(!isOn) {
            availableRobots.clear();
            sendToGUI("bleDisabled");
        }

        //TODO: add video for turning ble on
        //TODO: implement other possibilities
    }

    public void updateGUIScanStatus(boolean scanning) {
        if (scanning) {
            autoconnectRequested = false;
            sendToGUI("scanStarted");
        } else {
            sendToGUI("scanEnded");
        }

        boolean online = internetIsAvailable();
        LOG.debug("Internet is available? {}", online);
    }

    public void updateGUIConnection(Robot robot, int index) {
        //deviceDidConnect = function(address, name, fancyName, devLetter, hasV2) {
        if (robot.isConnected()) {
            String devLetter = Utilities.indexToDevLetter(index);
            String[] args = new String[] {robot.name, robot.name, robot.fancyName, devLetter, String.valueOf(robot.hasV2) };
            sendToGUI("deviceDidConnect", args);
        } else {
            sendToGUI("deviceDidDisconnect", robot.name);
        }

    }

    public void receiveScanResponse(String name, JSONObject discoveryInfo){
        if (robotManager.autoConnect && !autoconnectRequested) {
            autoconnectRequested = true;
            requestConnection(name);
        }
        discoveryInfo.put("fancyName", FancyNames.getDeviceFancyName(name));
        //Remove first 2 characters so that the name can change while advertising...
        availableRobots.put(name.substring(2), discoveryInfo.toString());
        LOG.debug("blePacketReceived():discovery: {} {}", name, discoveryInfo.toString());

        updateGuiDeviceList();
        robotManager.receiveScanResponse(name);
    }
    private void updateGuiDeviceList() {
        LOG.debug("updateGuiDeviceList() availableRobots.values(): " + availableRobots.values());
        String[] newList = availableRobots.values().toArray(new String[0]);

        String[][] args = new String[1][];
        args[0] = newList;

        sendToGUI("updateScanDeviceList", args);
    }

    public void showCalibrationResult(boolean success) {
        sendToGUI("showCalibrationResult", success);
    }

    public void updateBatteryState(String robotName, String batteryState) {
        String[] args = new String[] { robotName, batteryState };
        sendToGUI("deviceBatteryUpdate", args);
    }

    public void setGuiTts(boolean tts) {
        LOG.debug("setting gui tts to " + tts);
        sendToGUI("setTTS", tts);
    }

    private void sendToGUI(String methodName, Object... args) {
        if (callbackManager == null) {
            LOG.error("Cannot call {}, callback manager not set up.", methodName);
            pendingMessages.add(() -> sendToGUI(methodName, args));
            return;
        }
        Platform.runLater(() -> {
            LOG.debug("Calling callbackManager." + methodName);
            callbackManager.call(methodName, args);
        });
    }

    public void requestConnection(String nameToConnect) {
        LOG.debug("Requesting connection to " + nameToConnect);
        availableRobots.remove(nameToConnect.substring(2));
        updateGuiDeviceList();
        robotManager.connectToRobot(nameToConnect);
    }

    public void handleMessage(JSObject json) {

        Object type = json.getMember("type");
        //LOG.debug("From frontend: " + type.toString());

        switch (type.toString()) {
            case "console log":
                String logString = json.getMember("consoleLog").toString();
                //mDict.TryGetValue("consoleLog", out logString);
                LOG.debug("CONSOLE LOG: " + logString);
                break;
            case "document status":
                String documentStatus = json.getMember("documentStatus").toString();
                LOG.debug("DOCUMENT STATUS: " + documentStatus + ". Full message: " + json.toString());
                switch (documentStatus) {
                    case "READY":
                        LOG.info("Received document ready");
                        //TODO: use this or remove
                        //MainPage.Current.RobotManager.StartScan();
                        break;
                    default:
                        LOG.debug("Unhandled document status '" + documentStatus);
                        break;
                }
                break;
            case "command":
                String command = json.getMember("command").toString();
                //mDict.TryGetValue("command", out command);
                switch (command) {
                    case "scan":
                        String scanState = json.getMember("scanState").toString();
                        //mDict.TryGetValue("scanState", out scanState);
                        switch (scanState) {
                            case "on":
                                robotManager.startDiscovery();
                                break;
                            case "off":
                                robotManager.stopDiscovery();
                                break;
                            default:
                                LOG.debug("UNHANDLED SCAN MESSAGE: " + json.toString());
                                break;
                        }
                        break;
                    case "connect":
                        String nameToConnect = json.getMember("name").toString();
                        requestConnection(nameToConnect);
                        //mDict.TryGetValue("address", out address);
                        //MainPage.Current.RobotManager.ConnectToRobot(addressToConnect);
                        break;
                    case "disconnect":
                        String addressToDisconnect = json.getMember("address").toString();
                        robotManager.disconnectFromRobot(addressToDisconnect);
                        //MainPage.Current.RobotManager.DisconnectFromRobot(addressToDisconnect);
                        break;
                    case "calibrate":
                        String deviceLetter = json.getMember("devLetter").toString();
                        robotManager.calibrate(deviceLetter);
                        break;
                    case "openSnap":
                        String projectName = json.getMember("project").toString();
                        String lang = json.getMember("language").toString();
                        boolean local = json.getMember("online").toString().equals("false");
                        String url;
                        if (local) {
                            url = "http://127.0.0.1:30061/snap.html#open:/snapProjects/" + projectName + ".xml&editMode&noRun&lang=" + lang;
                        } else {
                            url = "https://snap.berkeley.edu/snapsource/snap.html#present:Username=birdbraintech&ProjectName=" + projectName + "&editMode&noRun&lang=" + lang;
                        }

                        openURLinBrowser(url);
                        break;
                    default:
                        LOG.debug("UNHANDLED COMMAND: '" + command + "'.");
                        break;
                }
                break;
            case "tts":
                String message = json.getMember("say").toString();
                RobotManager.getSharedInstance().tts.say(message);
                break;
            default:
                LOG.debug("Message of type '" + type + "' not implemented. ");
                break;

        }
    }

    private static void openURLinBrowser (String urlString) {
        LOG.info("Opening " + urlString);
        //Snap is best used in chrome. Try to open chrome first.
        try {
            final String dir = System.getProperty("user.dir");
            String osName = System.getProperty("os.name");
            LOG.debug("OS = {}; user dir = {}" , osName, dir);

            if (osName.contains("Win")) {
                Runtime.getRuntime().exec(new String[]{"cmd", "/c","start chrome \"" + urlString + "\""});
            } else { //Linux
                Runtime.getRuntime().exec(new String[] { "chromium-browser", urlString });
            }
        } catch (Exception exception) {
            LOG.info("Could not open url in chrome. Trying the default browser.");
            try {
                Desktop.getDesktop().browse(new URL(urlString).toURI());
            } catch (Exception e) {
                LOG.error("Failed to open url {}", urlString);
                e.printStackTrace();
            }
        }
    }

    private static boolean internetIsAvailable() {
        try {
            final URL url = new URL("https://snap.berkeley.edu/");
            final URLConnection conn = url.openConnection();
            conn.connect();
            conn.getInputStream().close();
            return true;
        } catch (MalformedURLException e) {
            //throw new RuntimeException(e);
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
