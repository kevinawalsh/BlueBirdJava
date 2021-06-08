package com.birdbraintechnologies.bluebirdconnector;

import netscape.javascript.JSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FrontendServer {

    static final Logger LOG = LoggerFactory.getLogger(FrontendServer.class);

    private static FrontendServer sharedInstance;
    private JSObject callbackManager;

    private FrontendServer(){

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


    public void handleMessage(JSObject json) {

        Object type = json.getMember("type");
        LOG.debug("From frontend: " + type.toString());

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
                                //MainPage.Current.RobotManager.StartScan();
                                break;
                            case "off":
                                //MainPage.Current.RobotManager.StopScan();
                                break;
                            default:
                                LOG.debug("UNHANDLED SCAN MESSAGE: " + json.toString());
                                break;
                        }
                        break;
                    case "connect":
                        String addressToConnect = json.getMember("address").toString();
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
