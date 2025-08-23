package com.birdbraintechnologies.bluebirdconnector;

public class Hummingbird extends Robot {

    public Hummingbird(String name, RobotCommunicator rc){
        super(name, rc);

        //Set Constants
        calibrationIndex = 7;
        batteryIndex = 3; // 4th sensor value at index 3
        fullThresh = 4.75;
        greenThresh = 4.575;
        yellowThresh = 4.4;
        rawToVoltage = 0.0406;
        voltageConst = 0;
        batteryMask = 0xFF;
        batteryTolerance = 0.05;
        FREQ_INDEX_MSB = 15;
        FREQ_INDEX_LSB = 16;
        DURATION_INDEX_MSB = 17;
        DURATION_INDEX_LSB = 18;
        setAllCmd = (byte) 0xCA;
        stopAllCmd = new byte[] {(byte)0xCB, (byte)0xFF, (byte)0xFF, (byte)0xFF};
    }


}
