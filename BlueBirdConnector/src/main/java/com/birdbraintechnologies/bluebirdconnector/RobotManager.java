package com.birdbraintechnologies.bluebirdconnector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Hashtable;

public class RobotManager {

    static final Logger LOG = LoggerFactory.getLogger(RobotManager.class);

    public Hashtable connectionTable = new Hashtable();

    private static RobotManager sharedInstance;
    private RobotCommunicator robotCommunicator;

    private RobotManager() {
        setUpRobotCommunicator();
    }

    private void setUpRobotCommunicator() {
        if (robotCommunicator != null && robotCommunicator.isRunning()) {
            LOG.error("Tried to set up communicator while one is already running");
            return;
        }
        //TODO: Find best communications option...

        robotCommunicator = new WinBLE(this);
    }

    public static RobotManager getSharedInstance(){
        if (sharedInstance == null) {
            sharedInstance = new RobotManager();
        }
        return sharedInstance;
    }

    public void startDiscovery(){
        if (robotCommunicator == null || !robotCommunicator.isRunning()){
            LOG.error("Trying to start discovery without a running communicator.");
            return;
        }
        robotCommunicator.startDiscovery();
    }

    public void stopDiscovery(){
        if (robotCommunicator == null || !robotCommunicator.isRunning()){
            LOG.error("Trying to stop discovery without a running communicator.");
            return;
        }
        robotCommunicator.stopDiscovery();
    }

    public void connectToRobot(String name){
        LOG.error("NOT IMPLEMENTED");
    }

    public void close(){
        if(robotCommunicator != null) {
            robotCommunicator.kill();
        }
    }

    public void receiveNotification(int connection, byte[] bytes) {

    }
    public void receiveScanResponse(RobotInfo activeDevice) {

    }
    public void updateBleStatus(String bleStatus) {

    }

    public void receiveConnectionEvent(RobotInfo deviceInfo) {

    }
    public void receiveDisconnectionEvent(int connection, boolean userInitiated) {

    }
    //The communicator has been unintentionally disconnected
    public void receiveCommDisconnectionEvent() {

    }
    //calibration is finished
    void receiveCalibrationDone(boolean gotResult) {

    }
}
