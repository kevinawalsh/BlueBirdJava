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

    public Robot(String robotName) {
        name = robotName;
        fancyName = FancyNames.getDeviceFancyName(name);
        isConnected = false;
        hasV2 = false;
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

    protected abstract int getCalibrationIndex();

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
            /*int index = 7;
            if (devType.equals("FN")) {
                index = 16;
            }*/
            int index = getCalibrationIndex();
            //get byte containing calibration bits
            //byte calibrationByte = getNotificationDataByte(index, devLetter); //Bit 7 is button byte
            byte calibrationByte = bytes[index];
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
    }

}


