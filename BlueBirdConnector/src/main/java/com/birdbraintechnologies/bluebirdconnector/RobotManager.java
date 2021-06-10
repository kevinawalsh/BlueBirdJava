package com.birdbraintechnologies.bluebirdconnector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Hashtable;

public class RobotManager {

    static final Logger LOG = LoggerFactory.getLogger(RobotManager.class);
    static byte[] CALIBRATE_CMD = {(byte) 0xCE, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

    //public Hashtable connectionTable = new Hashtable();
    private Robot[] selectedRobots = new Robot[3]; //Limit to 3 connections at a time.
    private Hashtable<String, Integer> robotIndexes = new Hashtable<>();

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
        //LOG.debug("returning robotmanager instance...");
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
        LOG.debug("connectToRobot {}", name);
        stopDiscovery();
        if (robotCommunicator == null || !robotCommunicator.isRunning()) {
            LOG.error("Requesting robot connection while no communicator is running");
            return;
        }

        //TODO: lock or something to protect from multiple requests?
        int index = -1;
        for (int i = selectedRobots.length; i > 0; i--) {
            if (selectedRobots[i-1] == null) { index = i-1; }
        }
        if (index == -1) {
            LOG.error("Max connections already reached! Cannot connect {}.", name);
            return;
        }
        Robot connecting = Robot.Factory(name);
        selectedRobots[index] = connecting;
        robotIndexes.put(name, index);
        LOG.debug("Connecting {} at index {}", name, index);
        robotCommunicator.requestConnection(name);
    }

    public void disconnectFromRobot(String name) {
        LOG.debug("disconnectFromRobot {}", name);
        if (robotCommunicator == null || !robotCommunicator.isRunning()) {
            LOG.error("Requesting robot connection while no communicator is running");
            return;
        }

        robotCommunicator.requestDisconnect(name);
    }

    public void calibrate(String deviceLetter) {
        int index = ((int)deviceLetter.charAt(0) - 65);
        LOG.debug("Calibrating device {} at index {}", deviceLetter, index);
        if (index < 0 || index >= selectedRobots.length || selectedRobots[index] == null) {
            LOG.error("Calibrate: invalid robot selection.");
            return;
        }

        Robot robot = selectedRobots[index];
        robotCommunicator.sendCommand(robot.name, CALIBRATE_CMD);
        robot.setCalibrating();
    }

    public void close(){
        if(robotCommunicator != null) {
            robotCommunicator.kill();
        }
    }

    public void receiveNotification(String robotName, byte[] bytes) {
        Robot robot = getRobotByName(robotName);
        if (robot != null) { robot.receiveNotification(bytes); }
    }
    public void receiveScanResponse(RobotInfo activeDevice) {

    }
    public void updateBleStatus(String bleStatus) {

    }

    public void receiveConnectionEvent(String robotName, boolean hasV2) {
        Integer index = robotIndexes.get(robotName);
        if (index == null) {
            LOG.error("{} not found in selectedRobots.", robotName);
            return;
        }
        Robot robot = selectedRobots[index];
        robot.isConnected = true;
        robot.setHasV2(hasV2);
        LOG.debug("receiveConnectionEvent {} {}", robotName, hasV2);
        FrontendServer.getSharedInstance().updateGUIConnection(robot, index);
    }
    public void receiveDisconnectionEvent(String robotName, boolean userInitiated) {
        //TODO: autoreconnect if not userinitialted

        Integer index = robotIndexes.get(robotName);
        if (index == null) {
            LOG.error("{} not found in selectedRobots.", robotName);
            return;
        }
        Robot robot = selectedRobots[index];
        robot.isConnected = false;
        if (userInitiated) {
            selectedRobots[index] = null;
        }
        FrontendServer.getSharedInstance().updateGUIConnection(robot, index);
    }
    //The communicator has been unintentionally disconnected
    public void receiveCommDisconnectionEvent() {

    }
    //calibration is finished
    void receiveCalibrationDone(boolean gotResult) {

    }

    private Robot getRobotByName(String name) {
        Integer index = robotIndexes.get(name);
        if (index == null) {
            LOG.error("{} not found in selectedRobots.", name);
            return null;
        }
        return selectedRobots[index];
    }

}
