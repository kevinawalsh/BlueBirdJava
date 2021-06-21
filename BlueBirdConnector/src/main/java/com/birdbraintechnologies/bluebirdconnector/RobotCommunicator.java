package com.birdbraintechnologies.bluebirdconnector;

import java.util.ArrayDeque;
import java.util.Deque;

public abstract class RobotCommunicator { //TODO: turn into interface

    //public RobotCommListener listener;
    protected RobotManager robotManager;
    protected FrontendServer frontendServer = FrontendServer.getSharedInstance();

    //public CommType type;
    long notificationStartTime;
    //protected ObjectMapper mapper = new ObjectMapper();

    //is a robot currently in the process of connecting?
    protected boolean deviceConnecting;
    //queue of robots waiting to connect
    //protected Deque<ConnectionRequestObj> connectionQueue = new ArrayDeque<ConnectionRequestObj>();
    //protected Deque<RobotInfo> connectionQueue = new ArrayDeque<RobotInfo>();
    protected Deque<String> connectionQueue = new ArrayDeque<String>();

    /*public RobotCommunicator(RobotCommListener listener) {
        this.listener = listener;
        notificationStartTime = 0;
    }*/
    public RobotCommunicator(RobotManager manager) {
        robotManager = manager;
    }

    abstract void requestConnection(String name); //connect to specified robot
    abstract void requestDisconnect(String address); //disconnect from specified robot
    abstract void startDiscovery(); //Start looking for robots
    abstract void stopDiscovery(); //Stop looking for robots
    //abstract void startCalibration(int connection); //Start calibration for connection
    //abstract void stopCalibration(); //Stop the currently running calibration
    //abstract void stopFirmwareUpgrade(); //Stop the currently running firmware upgrade
    abstract void cancelConnectionRequest(); //Cancel the current connection request
    abstract void sendCommand(String robotName, byte[] command); //Send command to specified device
    abstract void kill(); //shut down the communicator
    abstract boolean isRunning(); //is this communicator prepared to communicate
    //abstract boolean robotFound(); //has the communicator found a robot it can connect to


}
