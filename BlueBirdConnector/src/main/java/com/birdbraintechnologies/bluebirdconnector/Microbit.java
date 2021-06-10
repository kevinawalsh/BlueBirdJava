package com.birdbraintechnologies.bluebirdconnector;

public class Microbit extends Robot {

    public Microbit(String name){
        super(name);

        //Set Constants
        calibrationIndex = 7;
        batteryIndex = 3; // 4th sensor value at index 3
        greenThresh = 4.75;
        yellowThresh = 4.4;
        rawToVoltage = 0.0406;
        voltageConst = 0;
        batteryMask = 0xFF;
        batteryTolerance = 0.05;
    }


}
