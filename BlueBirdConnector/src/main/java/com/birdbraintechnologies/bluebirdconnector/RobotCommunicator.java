package com.birdbraintechnologies.bluebirdconnector;

public interface RobotCommunicator {

    void requestConnection(String name); //connect to specified robot
    void requestDisconnect(String address); //disconnect from specified robot
    void startDiscovery(); //Start looking for robots
    void stopDiscovery(); //Stop looking for robots
    void cancelConnectionRequest(); //Cancel the current connection request
    void sendCommand(String robotName, byte[] command); //Send command to specified device
    void kill(); //shut down the communicator
    boolean isRunning(); //is this communicator prepared to communicate

}
