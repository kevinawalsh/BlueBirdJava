package com.birdbraintechnologies.bluebirdconnector;

public class Finch extends Robot {

    public Finch(String name, RobotCommunicator rc){
        super(name, rc);

        //Set constants
        calibrationIndex = 16;
        batteryIndex = 6;
        batteryMask = 0xFF;
        greenThresh = 3.51375;
        yellowThresh = 3.3732;
        rawToVoltage = 0.00937;
        voltageConst = 320;
        batteryTolerance = 0.05;
        FREQ_INDEX_MSB = 16;
        FREQ_INDEX_LSB = 17;
        DURATION_INDEX_MSB = 18;
        DURATION_INDEX_LSB = 19;
        setAllCmd = (byte) 0xD0;
        stopAllCmd = new byte[] {(byte)0xDF, (byte)0xFF, (byte)0xFF, (byte)0xFF};
    }


    @Override
    public void setHasV2(boolean hasV2) {
        super.setHasV2(hasV2);
        LOG.debug("Updating finch battery constants for hasV2=" + hasV2);
        if (hasV2) {
            batteryMask = 0x3;
            fullThresh = 2.9;
            greenThresh = 1.9;
            yellowThresh = 0.9;
            rawToVoltage = 1;
            voltageConst = 0;
            batteryTolerance = 0;
        } else {
            batteryMask = 0xFF;
            fullThresh = 3.9354;   // = (320 + 100)* 0.00937 = (voltageConst + BATT_THRESH1) * rawToVoltage 
            greenThresh = 3.51375; // = (320 + 55) * 0.00937 = (voltageConst + BATT_THRESH1) * rawToVoltage 
            yellowThresh = 3.3732; // = (320 + 40) * 0.00937 = (voltageConst + BATT_THRESH2) * rawToVoltage
            rawToVoltage = 0.00937;
            voltageConst = 320;
            batteryTolerance = 0.05;
        }
    }


}
