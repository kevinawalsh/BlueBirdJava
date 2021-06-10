package com.birdbraintechnologies.bluebirdconnector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Timer;
import java.util.TimerTask;

public abstract class Robot {
    static final Logger LOG = LoggerFactory.getLogger(Robot.class);

    public final String name;
    public final String fancyName;
    public boolean isConnected;
    public boolean hasV2;
    private boolean isCalibrating;
    private byte[] currentData;
    private String currentBattery;

    //Constants
    int calibrationIndex;
    int batteryIndex;
    double greenThresh;
    double yellowThresh;
    double rawToVoltage;
    double voltageConst;
    int batteryMask; //TODO: is this the right type?
    double batteryTolerance;


    public Robot(String robotName) {
        name = robotName;
        fancyName = FancyNames.getDeviceFancyName(name);
        isConnected = false;
        hasV2 = false;
        currentBattery = "unknown";
    }

    public static Robot Factory(String name) {
        String prefix = name.substring(0, 2);
        switch (prefix) {
            case "FN":
                return new Finch(name);
            default:
                return null;
        }
    }


    public void setHasV2(boolean robotHasV2) {
        hasV2 = robotHasV2;
    }


    public void setCalibrating() {
        LOG.debug("setCalibrating");
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                LOG.debug("Setting isCalibrating for {}", name);
                isCalibrating = true;
            }
        }, 500);
    }

    public void receiveNotification(byte[] bytes) {
        currentData = bytes;

        if (isCalibrating) {
            //get byte containing calibration bits
            byte calibrationByte = bytes[calibrationIndex];
            byte calibrationStatus = (byte) (calibrationByte & (byte) 0x0C);
            LOG.debug("Calibration Status: {}", calibrationStatus);

            switch (calibrationStatus) {
                case 4:
                    LOG.info("Calibration Successful");
                    isCalibrating = false;
                    FrontendServer.getSharedInstance().showCalibrationResult(true);
                    break;
                case 8:
                    LOG.info("Calibration Failed");
                    isCalibrating = false;
                    FrontendServer.getSharedInstance().showCalibrationResult(false);
                    break;
            }
        }

        //Check battery state
        byte battByte = bytes[batteryIndex];
        int battInt = battByte;
        int battUInt = battByte & batteryMask;
        //LOG.debug ("Battery Byte = {} (0x{}); Battery Int = {};  Battery UInt = {}", battByte, Integer.toHexString(battByte), battUInt);
        double voltage = (battUInt + voltageConst) * rawToVoltage;
        //battData = Math.abs(battData);
        //LOG.debug ("Battery level connection {}: {}", i, battData);
        String battLevel = "unknown";
        switch (currentBattery) {
            case "unknown":
                if (voltage > greenThresh) {
                    battLevel = "green";
                } else if (voltage > yellowThresh) {
                    battLevel = "yellow";
                } else {
                    battLevel = "red";
                }
                break;
            case "green":
                if (voltage < yellowThresh) {
                    battLevel = "red";
                } else if (voltage < greenThresh - batteryTolerance) {
                    battLevel = "yellow";
                } else {
                    battLevel = "green";
                }
                break;
            case "yellow":
                if (voltage > greenThresh + batteryTolerance) {
                    battLevel = "green";
                } else if (voltage < yellowThresh - batteryTolerance) {
                    battLevel = "red";
                } else {
                    battLevel = "yellow";
                }
                break;
            case "red":
                if (voltage > greenThresh) {
                    battLevel = "green";
                } else if (voltage > yellowThresh + batteryTolerance) {
                    battLevel = "yellow";
                } else {
                    battLevel = "red";
                }
                break;
        }

        if (!battLevel.equals(currentBattery)) {
            currentBattery = battLevel;
            FrontendServer.getSharedInstance().updateBatteryState(name, battLevel);
        }


        //hashtable.put(devLetter, battLevel);
        //LOG.debug("getBatteryData for {}: {} -> {} -> {}", i, battUInt, battData, battLevel);

        /* TODO:
        if (tts != null && (getConnectionFromDevLetter(devLetter) != -1) && (currentBatteryTable == null || currentBatteryTable.get(devLetter) != battLevel)) {
            LOG.info("Battery level for device " + devLetter + "(" + i + ") is " + battLevel + "; total connections: " + connectionTable.size());
            tts.say("Battery level " + battLevel);
        }*/
    }

}


