package com.birdbraintechnologies.bluebirdconnector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class RobotManager {

    static final Logger LOG = LoggerFactory.getLogger(RobotManager.class);

    static final double FINCH_TICKS_PER_CM = 49.7; //51;
    static final double FINCH_TICKS_PER_DEGREE = 4.335;
    static final int FINCH_TICKS_PER_ROTATION = 792;
    static final double FINCH_SPEED_SCALING = 0.36; //0.45;// 45/100;
    static final int MAX_LED_PRINT_WORD_LEN = 10;

    //public Hashtable connectionTable = new Hashtable();
    private Robot[] selectedRobots = new Robot[3]; //Limit to 3 connections at a time.
    private Hashtable<String, Integer> robotIndexes = new Hashtable<>();
    private Hashtable<String, Boolean> autoreconnectList = new Hashtable<>();

    private static RobotManager sharedInstance;
    private RobotCommunicator robotCommunicator;
    private boolean shouldScanWhenReady = false;

    //Text to speech
    public TextToSpeech tts = null;
    public boolean autoConnect = false;
    private boolean autoCalibrate = false;

    private RobotManager() {
        Thread managerThread = new Thread(this::setUpRobotCommunicator);
        managerThread.start();
    }

    private void setUpRobotCommunicator() {
        if (robotCommunicator != null) {
            if (robotCommunicator.isRunning()) {
                LOG.error("Tried to set up communicator while one is already running");
                return;
            } else {
                robotCommunicator.kill();
            }
        }

        LOG.info("Attempting to set up bluetooth communications.");
        //TODO: Find best communications option...
        robotCommunicator = new DongleBLE();
        if (!robotCommunicator.isRunning()) {
            robotCommunicator.kill();
            robotCommunicator = new WinBLE();
        }

        if (shouldScanWhenReady) {
            startDiscovery();
        }

        if (!robotCommunicator.isRunning()) {
            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    setUpRobotCommunicator();
                }
            }, 2000);
        }
    }
    public void updateCommunicatorStatus(boolean connected) {
        LOG.debug("updateCommunicatorStatus {}", connected);
        if (!connected) {
            //if (robotCommunicator != null) { robotCommunicator.kill(); }
            //robotCommunicator = null;
            FrontendServer.getSharedInstance().updateGUIScanStatus(false);
            FrontendServer.getSharedInstance().updateBleStatus(true, false);
            setUpRobotCommunicator();
        }
    }

    public static RobotManager getSharedInstance(){
        if (sharedInstance == null) {
            sharedInstance = new RobotManager();
        }
        //LOG.debug("returning robotmanager instance...");
        return sharedInstance;
    }

    public void setupTTS(String[] args) {
        tts = new TextToSpeech("Starting BlueBird Connector");
        autoConnect = true;
        if (args.length > 1) { autoCalibrate = true; }//prop.setProperty("TTS", "autoCalibrate"); }

    }

    public void startDiscovery(){
        if (robotCommunicator == null || !robotCommunicator.isRunning()){
            LOG.error("Trying to start discovery without a running communicator.");
            shouldScanWhenReady = true;
            return;
        }
        robotCommunicator.startDiscovery();
    }

    public void stopDiscovery(){
        shouldScanWhenReady = false;
        if (robotCommunicator == null || !robotCommunicator.isRunning()){
            LOG.error("Trying to stop discovery without a running communicator.");
            return;
        }
        robotCommunicator.stopDiscovery();
    }

    public void connectToRobot(String name){
        LOG.debug("connectToRobot {}", name);
        LOG.debug("currently connected robots: {}, {}, {}", selectedRobots[0], selectedRobots[1], selectedRobots[2]);
        autoreconnectList.put(name, true);
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
        Robot connecting = Robot.Factory(name, robotCommunicator);
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

    public Robot getConnectedRobot(char devLetter, String errorMsg){
        int index = (int)devLetter - 65;

        if (index < 0 || index >= selectedRobots.length || selectedRobots[index] == null || !selectedRobots[index].isConnected()) {
            LOG.error("No robot connected at {}. {}", devLetter, errorMsg);
            return null;
        }
        return selectedRobots[index];
    }

    public void calibrate(String deviceLetter) {
        Robot robot = getConnectedRobot(deviceLetter.charAt(0), "Cannot calibrate.");
        if (robot != null) { robot.startCalibration(); }
    }

    public void updateSetAll(char devLetter, int index, byte value) {
        Robot robot = getConnectedRobot(devLetter, "Cannot update setAll.");
        if (robot != null) {
            robot.updateSetAll(index, value);
        }
    }

    public void updateSetAllLED(char devLetter, String port, byte rVal, byte gVal, byte bVal) {
        Robot robot = getConnectedRobot(devLetter, "Cannot update LED.");
        if (robot != null) {
            robot.updateSetAllLED(port, rVal, gVal, bVal);
        }
    }

    public void updateBuzzer(char devLetter, int note, int ms) {
        Robot robot = getConnectedRobot(devLetter, "Cannot update buzzer.");
        if (robot != null) {
            robot.updateBuzzer(note, ms);
        }
    }

    public void startPrint(char devLetter, char[] charBuf) {
        Robot robot = getConnectedRobot(devLetter, "Cannot start print.");
        if (robot != null) {
            robot.startPrint(charBuf);
        }
    }

    public void setSymbol(char devLetter, byte[] symbolCommand){
        Robot robot = getConnectedRobot(devLetter, "Cannot set symbol.");
        if (robot != null) {
            robot.setSymbol(symbolCommand);
        }
    }

    public void resetEncoders(char devLetter) {
        Robot robot = getConnectedRobot(devLetter, "Cannot reset encoders.");
        if (robot != null) {
            robot.resetEncoders();
        }
    }

    public void updateMotors(char devLetter, int speedL, int ticksL, int speedR, int ticksR){
        Robot robot = getConnectedRobot(devLetter, "Cannot update motors.");
        if (robot != null) {
            robot.updateMotors(speedL, ticksL, speedR, ticksR);
        }
    }

    public void robotStopAll(char devLetter) {
        Robot robot = getConnectedRobot(devLetter, "Cannot stop all.");
        if (robot != null) {
            robot.stopAll();
        }
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
    public void receiveScanResponse(String robotName) {
        if(autoreconnectList.get(robotName) != null) {
            FrontendServer.getSharedInstance().requestConnection(robotName);
        }
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
        robot.setHasV2(hasV2);
        robot.setConnected(true);
        LOG.debug("receiveConnectionEvent {} {}", robotName, hasV2);
        FrontendServer.getSharedInstance().updateGUIConnection(robot, index);

        if (autoCalibrate) {
            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    LOG.debug("auto calibrating " + robotName);
                    calibrate(Utilities.indexToDevLetter(index));
                }
            }, 1000);
        }
    }
    public void receiveDisconnectionEvent(String robotName, boolean userInitiated) {
        Integer index = robotIndexes.get(robotName);
        if (index == null) {
            LOG.error("{} not found in selectedRobots.", robotName);
            return;
        }
        Robot robot = selectedRobots[index];
        selectedRobots[index] = null;
        robot.setConnected(false);
        if (userInitiated) {
            autoreconnectList.remove(robotName);
        }
        FrontendServer.getSharedInstance().updateGUIConnection(robot, index);
        if (!userInitiated){
            startDiscovery();
        }
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
