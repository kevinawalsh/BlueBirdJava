package com.birdbraintechnologies.bluebirdconnector;

public class Finch extends Robot {

    public Finch(String name){
        super(name);

        //Set constants
        calibrationIndex = 16;
        batteryIndex = 6;
        batteryMask = 0xFF;
        greenThresh = 3.51375;
        yellowThresh = 3.3732;
        rawToVoltage = 0.00937;
        voltageConst = 320;
        batteryTolerance = 0.05;
    }


    @Override
    public void setHasV2(boolean hasV2) {
        super.setHasV2(hasV2);
        LOG.debug("Updating finch battery constants for hasV2=" + hasV2);
        if (hasV2) {
            batteryMask = 0x3;
            greenThresh = 1;
            yellowThresh = 0;
            rawToVoltage = 1;
            voltageConst = 0;
            batteryTolerance = 0;
        } else {
            batteryMask = 0xFF;
            greenThresh = 3.51375;
            yellowThresh = 3.3732;
            rawToVoltage = 0.00937;
            voltageConst = 320;
            batteryTolerance = 0.05;
        }
    }


}
