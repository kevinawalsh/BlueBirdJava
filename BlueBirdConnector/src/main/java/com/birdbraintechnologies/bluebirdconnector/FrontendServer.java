package com.birdbraintechnologies.bluebirdconnector;

import javafx.application.Platform;
import netscape.javascript.JSObject;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

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

    public void updateGUIScanStatus(boolean scanning) {
        if (scanning) {
            callbackManager.call("scanStarted");
        } else {
            callbackManager.call("scanEnded");
        }
    }

    public void receiveScanResponse(String name, JSONObject discoveryInfo){
        discoveryInfo.put("fancyName", FancyNames.getDeviceFancyName(name));
        availableRobots.put(name, discoveryInfo.toString());
        LOG.debug("blePacketReceived():discovery: {} {}", name, discoveryInfo.toString());

        /*List<JSONObject> list = new ArrayList<>(availableRobots.values());
        JSONObject[] newList = list.toArray(new JSONObject[0]);
        LOG.debug("first value of new list: " + newList[0].toString());*/
        //String string = discoveryInfo.toString();
        //String[] newList = new String[] { string };
        String[] newList = (new ArrayList<>(availableRobots.values())).toArray(new String[0]);
        LOG.debug(newList[0]);

        String[][] args = new String[1][];
        args[0] = newList;
        Platform.runLater(() -> {
            callbackManager.call("updateScanDeviceList", args);
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
                        RobotManager.getSharedInstance().connectToRobot(nameToConnect);
                        //mDict.TryGetValue("address", out address);
                        //MainPage.Current.RobotManager.ConnectToRobot(addressToConnect);
                        break;
                    case "disconnect":
                        String addressToDisconnect = json.getMember("address").toString();
                        //MainPage.Current.RobotManager.DisconnectFromRobot(addressToDisconnect);
                        break;
                    default:
                        LOG.debug("UNHANDLED COMMAND: '" + command + "'. Original message: " + json.toString());
                        break;
                }
                break;
            default:
                LOG.debug("Message of type '" + type + "' not implemented. Original message: " + json.toString());
                break;

        }
    }
}
