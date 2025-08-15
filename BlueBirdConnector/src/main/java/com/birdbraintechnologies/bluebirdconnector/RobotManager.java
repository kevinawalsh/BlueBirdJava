package com.birdbraintechnologies.bluebirdconnector;

import java.util.*;

public class RobotManager {

    static final Log LOG = Log.getLogger(RobotManager.class);

    static final double FINCH_TICKS_PER_CM = 49.7; //51;
    static final double FINCH_TICKS_PER_DEGREE = 4.335;
    static final int FINCH_TICKS_PER_ROTATION = 792;
    static final double FINCH_SPEED_SCALING = 0.36; //0.45;// 45/100;
    static final int MAX_LED_PRINT_WORD_LEN = 10;

    private Robot[] selectedRobots = new Robot[3]; //Limit to 3 connections at a time.
    //Keep a list of where the robot is located. Set to -1 if the robot has disconnected and should reconnect automatically.
    private Hashtable<String, Integer> robotIndexes = new Hashtable<>();

    private static RobotManager sharedInstance;
    private RobotCommunicator robotCommunicator;
    private boolean shouldScanWhenReady = true;
    private boolean winNativeBleAvailable = false;
    private boolean linuxNativeBleAvailable = false;
    private long lastSetupAttempt = 0;
    private boolean setupInProgress = false;

    //Accessibility options
    public TextToSpeech tts = null;
    public boolean autoConnect = false;
    private boolean autoCalibrate = false;

    private RobotManager() {
        //Thread managerThread = new Thread(this::setUpRobotCommunicator);
        //managerThread.start();
        String osName = System.getProperty("os.name");
        winNativeBleAvailable = osName.contains("Win");
        linuxNativeBleAvailable = osName.equals("Linux");

        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (!setupInProgress && System.currentTimeMillis() > lastSetupAttempt + 5000) {
                    //LOG.debug("Attempting setup...");
                    if (robotCommunicator == null || !robotCommunicator.isRunning()) {
                        setUpRobotCommunicator();
                    }
                }
            }
        }, 0, 5000);
    }

    private void setUpRobotCommunicator() {
        if (setupInProgress) {
            LOG.debug("Setup already in progress");
            return;
        }
        setupInProgress = true;
        lastSetupAttempt = System.currentTimeMillis();

        if (robotCommunicator != null) {
            if (robotCommunicator.isRunning()) {
                LOG.error("Tried to set up communicator while one is already running");
                setupInProgress = false;
                return;
            } else {
                robotCommunicator.kill();
            }
        }

        LOG.info("Attempting to set up bluetooth communications.");
        //TODO: Find best communications option...
        robotCommunicator = new DongleBLE();
        if (!robotCommunicator.isRunning()) {
            if (winNativeBleAvailable) {
                LOG.debug("No dongle. Trying Windows native ble...");
                robotCommunicator.kill();
                robotCommunicator = new WinBLE();
            } else if (linuxNativeBleAvailable) {
                LOG.debug("No dongle. Trying Linux bluez ble...");
                robotCommunicator.kill();
                robotCommunicator = new LinuxBluezBLE();
            } else {
                LOG.debug("Dongle not found and no native ble available.");
                updateCommunicatorStatus(false,true);
            }
        }

        LOG.debug("ROBOT COMMUNICATOR SETUP " + robotCommunicator.isRunning());
        if (robotCommunicator.isRunning() && shouldScanWhenReady) {
            startDiscovery();
        }

        /*if (!robotCommunicator.isRunning()) {
            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    setUpRobotCommunicator();
                }
            }, 2000);
        }*/
        setupInProgress = false;
    }
    public void updateCommunicatorStatus(boolean connected, boolean available) {
        LOG.debug("updateCommunicatorStatus {}", connected);
        //Currently, the only communicator that can be marked unavailable is native ble
        if (!available) {
            winNativeBleAvailable = false;
            linuxNativeBleAvailable = false;
        }

        FrontendServer.getSharedInstance().updateBleStatus(connected);
        if (!connected) {
            if (robotCommunicator != null) { robotCommunicator.kill(); }
            //robotCommunicator = null;
            FrontendServer.getSharedInstance().updateGUIScanStatus(false);
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

        if (robotCommunicator == null || !robotCommunicator.isRunning()) {
            LOG.error("Requesting robot connection while no communicator is running");
            return;
        }

        synchronized (this) {
            //Make sure the requested robot isn't already connected
            Integer oldIndex = robotIndexes.get(name);
            if (oldIndex != null && oldIndex >= 0) {
                LOG.error("{} is already connected or being connected at index {}.", name, oldIndex);
                return;
            }

            //Find an open position
            int index = -1;
            for (int i = selectedRobots.length; i > 0; i--) {
                if (selectedRobots[i - 1] == null) {
                    index = i - 1;
                }
            }
            if (index == -1) {
                LOG.error("Max connections already reached! Cannot connect {}.", name);
                return;
            }

            Robot connecting = Robot.Factory(name, robotCommunicator);
            selectedRobots[index] = connecting;
            robotIndexes.put(name, index);
            LOG.debug("Connecting {} at index {}", name, index);
        }

        // stopDiscovery();
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
        Integer index = robotIndexes.get(robotName);
        if(index != null && index == -1) {
            robotIndexes.remove(robotName); //to ensure we do not request reconnection more than once
            FrontendServer.getSharedInstance().requestConnection(robotName);
        }
    }

    public void receiveConnectionEvent(String robotName, boolean hasV2) {
        Integer index = robotIndexes.get(robotName);
        if (index == null || index == -1) {
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
        LOG.debug("Disconnection Event {}, {}", robotName, userInitiated ? "user initiated" : "connection failure");
        Integer index = robotIndexes.get(robotName);
        if (index == null || index == -1) {
            LOG.error("{} not found in selectedRobots.", robotName);
            return;
        }
        Robot robot = selectedRobots[index];
        selectedRobots[index] = null;
        robot.setConnected(false);
        if (userInitiated) {
            robotIndexes.remove(robotName);
        } else {
            robotIndexes.put(robotName, -1); //autoreconnect
            LOG.debug("{} will autoreconnect", robotName);
        }
        FrontendServer.getSharedInstance().updateGUIConnection(robot, index);
        if (!userInitiated){
            startDiscovery();
        }
    }

    private Robot getRobotByName(String name) {
        Integer index = robotIndexes.get(name);
        if (index == null || index == -1) {
            LOG.error("{} not found in selectedRobots.", name);
            return null;
        }
        return selectedRobots[index];
    }

}
