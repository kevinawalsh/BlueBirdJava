package com.birdbraintechnologies.bluebirdconnector;

import javafx.application.Platform;
import netscape.javascript.JSObject;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;
import java.awt.Desktop;

public class FrontendServer {

    static final Logger LOG = LoggerFactory.getLogger(FrontendServer.class);

    private static FrontendServer sharedInstance;
    private JSObject callbackManager;
    private Hashtable<String, String> availableRobots;

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
        callbackManager = cbManager;
    }

    public void setTranslationTable(String language) {
        sendToGUI("setTranslationTable", language);
    }

    public void updateBleStatus(boolean isAvailable, boolean isOn) {
        if(isAvailable && !isOn) {
            sendToGUI("bleDisabled");
        }

        //TODO: add video for turning ble on
        //TODO: implement other possibilities
    }

    public void updateGUIScanStatus(boolean scanning) {
        if (scanning) {
            sendToGUI("scanStarted");
        } else {
            sendToGUI("scanEnded");
        }
    }

    public void updateGUIConnection(Robot robot, int index) {
        //deviceDidConnect = function(address, name, fancyName, devLetter, hasV2) {
        if (robot.isConnected) {
            String devLetter = Character.toString((char)(index + 65));
            String[] args = new String[] {robot.name, robot.name, robot.fancyName, devLetter, String.valueOf(robot.hasV2) };
            sendToGUI("deviceDidConnect", args);
        } else {
            sendToGUI("deviceDidDisconnect", robot.name);
        }

    }

    public void receiveScanResponse(String name, JSONObject discoveryInfo){
        discoveryInfo.put("fancyName", FancyNames.getDeviceFancyName(name));
        //Remove first 2 characters so that the name can change while advertising...
        availableRobots.put(name.substring(2), discoveryInfo.toString());
        LOG.debug("blePacketReceived():discovery: {} {}", name, discoveryInfo.toString());

        updateGuiDeviceList();
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

    private void sendToGUI(String methodName, Object... args) {
        Platform.runLater(() -> {
            callbackManager.call(methodName, args);
        });
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
                                RobotManager.getSharedInstance().startDiscovery();
                                break;
                            case "off":
                                RobotManager.getSharedInstance().stopDiscovery();
                                break;
                            default:
                                LOG.debug("UNHANDLED SCAN MESSAGE: " + json.toString());
                                break;
                        }
                        break;
                    case "connect":
                        String nameToConnect = json.getMember("name").toString();
                        LOG.debug("Requesting connection to " + nameToConnect);
                        availableRobots.remove(nameToConnect.substring(2));
                        updateGuiDeviceList();
                        RobotManager.getSharedInstance().connectToRobot(nameToConnect);
                        //mDict.TryGetValue("address", out address);
                        //MainPage.Current.RobotManager.ConnectToRobot(addressToConnect);
                        break;
                    case "disconnect":
                        String addressToDisconnect = json.getMember("address").toString();
                        RobotManager.getSharedInstance().disconnectFromRobot(addressToDisconnect);
                        //MainPage.Current.RobotManager.DisconnectFromRobot(addressToDisconnect);
                        break;
                    case "calibrate":
                        String deviceLetter = json.getMember("devLetter").toString();
                        RobotManager.getSharedInstance().calibrate(deviceLetter);
                        break;
                    case "openSnap":
                        /*command: "openSnap",
                        project: projectName,
                        online: shouldOpenOnline,
                        language: language*/
                        String projectName = json.getMember("project").toString();
                        String lang = json.getMember("language").toString();
                        boolean local = json.getMember("online").toString().equals("false");
                        String url;
                        if (local) {
                            url = "http://127.0.0.1:30061/snap/snap.html#open:/snap/snapProjects/" + projectName + ".xml&editMode&noRun&lang=" + lang;
                        } else {
                            url = "https://snap.berkeley.edu/snapsource/snap.html#present:Username=birdbraintech&ProjectName=" + projectName + "&editMode&noRun&lang=" + lang;
                        }

                        openURLinDefaultBrowser(url);
                        break;
                    default:
                        LOG.debug("UNHANDLED COMMAND: '" + command + "'.");
                        break;
                }
                break;
            default:
                LOG.debug("Message of type '" + type + "' not implemented. ");
                break;

        }
    }

    private static void openURLinDefaultBrowser (String urlString) {
        try {
            Desktop.getDesktop().browse(new URL(urlString).toURI());
        } catch (Exception e) {
            LOG.error("Failed to open url {}", urlString);
            e.printStackTrace();
        }

        /*try {
            final String dir = System.getProperty("user.dir");
            System.out.println("current dir = " + dir);

            String osName = System.getProperty("os.name");
            LOG.debug("OS = {}" , osName);

            String chromePath = null;

            if (osName.contains("Win")) {
                try {
                    LOG.info("Opening Chrome Browser. Looking in HKEY_LOCAL_MACHINE...");
                    chromePath = Advapi32Util.registryGetStringValue(
                            WinReg.HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\chrome.exe", "");
                    LOG.info("Chrome Path: {}", chromePath);
                    Process p = Runtime.getRuntime().exec(chromePath + " " + url);
                } catch (Exception e) {
                    try {
                        LOG.info("Chrome Browser not found. Looking in HKEY_CURRENT_USER...");
                        chromePath = Advapi32Util.registryGetStringValue(
                                WinReg.HKEY_CURRENT_USER, "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\chrome.exe", "");
                        LOG.info("Chrome Path: {}", chromePath);
                        Process p = Runtime.getRuntime().exec(chromePath + " " + url);
                    } catch (Exception ex){
                        LOG.info("Chrome installation not found, notifying user");
                        String message = "\nGoogle Chrome web browser has not been detected on your system. Please install Chrome in order to code with Snap!";
                        showErrorDialog("Google Chrome Browser Required", "Cannot find Google Chrome  browser on system.", message, false);
                    }
                }





                //Process p = Runtime.getRuntime().exec("rundll32 url.dll,FileProtocolHandler " + url);
                //p.waitFor();
            }

            if (osName.contains("OS X")) {
                //Command line properties assigned by bash shell script on app start
                //String chrome_path = System.getProperty("chrome_path");
                //String firefox_path = System.getProperty("firefox_path");
                String cmd = "";

                //LOG.info ("chrome_path: {}", chrome_path);
                //LOG.info ("firefox_path: {}", firefox_path);


                String as_command = prop.getProperty("as_command");
                LOG.info("as_command: {}", as_command);

                String [] args = new String[3];
                args[0] = "osascript";
                args[1] = "-e";
                args[2] = as_command + " \"" + url + "\"";

                LOG.info ("Lauhching Chrome browser: {}, {}, {}", args);
                Process p = Runtime.getRuntime().exec(args);

                String lsString, lsConsoleString;
                BufferedReader bufferedReader = new BufferedReader(
                        new InputStreamReader(p.getErrorStream()));
                while ((lsString = bufferedReader.readLine()) != null) {
                    LOG.error(lsString);
                    String message = "\nGoogle Chrome web browser has not been detected on your system. Please install Chrome in order to code with Snap! \n\n Error: " + lsString;
                    showErrorDialog("Google Chrome Browser Required", "Cannot find Google Chrome  browser on system.", message, false);
                }


                BufferedReader bufferedConsoleReader = new BufferedReader(
                        new InputStreamReader(p.getInputStream()));
                while ((lsConsoleString = bufferedReader.readLine()) != null) {
                    LOG.info(lsString);
                }

            }
        } catch(Exception e) {
            LOG.error ("{}", e.toString());
            LOG.error ("{}", stackTraceToString(e));
        }*/

    }
}
