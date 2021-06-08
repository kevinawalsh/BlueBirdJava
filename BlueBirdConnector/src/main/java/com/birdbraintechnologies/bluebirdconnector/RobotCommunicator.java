package com.birdbraintechnologies.bluebirdconnector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Deque;

public abstract class RobotCommunicator {

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
    protected Deque<RobotInfo> connectionQueue = new ArrayDeque<RobotInfo>();

    /*public RobotCommunicator(RobotCommListener listener) {
        this.listener = listener;
        notificationStartTime = 0;
    }*/
    public RobotCommunicator(RobotManager manager) {
        robotManager = manager;
    }

    abstract void requestConnection(RobotInfo deviceInfo); //connect to specified robot
    abstract void requestDisconnect(String address, int connection); //disconnect from specified robot
    abstract void startDiscovery(); //Start looking for robots
    abstract void stopDiscovery(); //Stop looking for robots
    abstract void startCalibration(int connection); //Start calibration for connection
    abstract void stopCalibration(); //Stop the currently running calibration
    abstract void stopFirmwareUpgrade(); //Stop the currently running firmware upgrade
    abstract void cancelConnectionRequest(); //Cancel the current connection request
    abstract void sendCommand(byte[] command, int connection); //Send command to specified device
    abstract void kill(); //shut down the communicator
    abstract boolean isRunning(); //is this communicator prepared to communicate
    abstract boolean robotFound(); //has the communicator found a robot it can connect to

    protected static String stackTraceToString(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString(); // stack trace as a string
    }
    public String bytesToString(byte[] bytes) {
        StringBuffer result = new StringBuffer();
        result.append("[ ");
        for(byte b : bytes) result.append( Integer.toHexString(b & 0xFF) + " ");
        result.append("]");
        return result.toString();
    }
}
