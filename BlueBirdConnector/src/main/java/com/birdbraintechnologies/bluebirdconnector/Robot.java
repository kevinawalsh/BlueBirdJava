package com.birdbraintechnologies.bluebirdconnector;

import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

import static com.birdbraintechnologies.bluebirdconnector.RobotManager.*;

public abstract class Robot {
    static final Log LOG = Log.getLogger(Robot.class);
    private TextToSpeech tts;

    public final String name;
    public final String fancyName;
    public final String ttsName;
    public final String type;
    private final RobotCommunicator communicator;
    private boolean isConnected;
    public boolean hasV2;
    private boolean isCalibrating;
    private byte[] currentData;
    private String currentBattery;
    private String currentRSSI;

    static final byte[] CALIBRATE_CMD = {(byte) 0xCE, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    //Outgoing BLE Data. 20 bytes
    public static final int  SET_ALL_LENGTH = 20;
    public byte[] setAllData = new byte[SET_ALL_LENGTH];
    public byte[] ledDisplayData = new byte[SET_ALL_LENGTH];
    public byte[] ledPrintData = new byte[SET_ALL_LENGTH];
    public byte[] motorsData = new byte[SET_ALL_LENGTH];
    //BLE data going out from deviceGTH];

    private static final class SetAllDataLock { }
    private Object setAllDataChannelLock;
    private boolean setAllChanged;  //Change indicator

    private static final class ledPrintLock { }
    private Object ledPrintChannelLock; // each channel has its own lock
    private boolean ledPrintChanged;  //Change indicator
    private PrintMonitor printMonitor = null;
    Thread printThread = null;

    private static final class ledDisplayLock { }
    private Object ledDisplayChannelLock; // each channel has its own lock
    private boolean ledDisplayChanged;  //Change indicator

    //finch command lock
    private static final class motorsLock { }
    private Object motorsChannelLock; // each channel has its own lock
    private boolean motorsChanged;

    private SetAllThread setAllThread;
    private static final int COMMAND_INTERVAL = 30;

    //Device Specific Constants
    int calibrationIndex;
    int batteryIndex;
    double fullThresh;
    double greenThresh;
    double yellowThresh;
    double rawToVoltage;
    double voltageConst;
    int batteryMask; //TODO: is this the right type?
    double batteryTolerance;
    int FREQ_INDEX_MSB;
    int FREQ_INDEX_LSB;
    int DURATION_INDEX_MSB;
    int DURATION_INDEX_LSB;
    byte setAllCmd;
    byte[] stopAllCmd;


    public Robot(String robotName, RobotCommunicator rc) {
        name = robotName;
        fancyName = FancyNames.getDeviceFancyName(name);
        ttsName = fancyName.substring(0, fancyName.lastIndexOf(" "));
        type = robotName.substring(0, 2);
        communicator = rc;

        tts = RobotManager.getSharedInstance().tts;

        isConnected = false;
        hasV2 = false;
        currentBattery = "unknown";
        currentRSSI = "";

        currentData = new byte[20];
        setAllChanged = false;
        ledPrintChanged = false;
        ledDisplayChanged = false;
        motorsChanged = false;

        setAllThread = new SetAllThread();
    }

    public static Robot Factory(String name, RobotCommunicator rc) {
        String prefix = name.substring(0, 2);
        switch (prefix) {
            case "FN":
                return new Finch(name, rc);
            case "BB":
                return new Hummingbird(name, rc);
            case "MB":
                return new Microbit(name, rc);
            default:
                return null;
        }
    }

    private void sendCommand(byte[] command) {
        if (communicator != null && communicator.isRunning()) {
            communicator.sendCommand(name, command);
        }
    }

    private void initializeSetAllChannel() {
        setAllDataChannelLock = new SetAllDataLock();
        ledPrintChannelLock = new ledPrintLock();
        ledDisplayChannelLock = new ledDisplayLock();
        motorsChannelLock = new motorsLock();

        /*for (int j=0; j < SET_ALL_LENGTH; j++) {
            setAllData[channel][j] = 0;
            ledDisplayData[channel][j] = 0;
            ledPrintData[channel][j] = 0;
            motorsData[channel][j] = 0;
        }*/
        setAllData = new byte[SET_ALL_LENGTH];
        ledDisplayData = new byte[SET_ALL_LENGTH];
        ledPrintData = new byte[SET_ALL_LENGTH];
        motorsData = new byte[SET_ALL_LENGTH];

        //assign only non-zero bytes:
        setAllData[0] = setAllCmd;
        if (!type.equals("FN")) {
            //Servo off command
            setAllData[9] = (byte) 0xFF;
            setAllData[10] = (byte) 0xFF;
            setAllData[11] = (byte) 0xFF;
            setAllData[12] = (byte) 0xFF;
        }

        setAllChanged = false;
        ledPrintChanged = false;
        ledDisplayChanged = false;
        motorsChanged = false;
        //masterDisconnect = false;

        LOG.debug("setAll initialized with {} to {}", setAllCmd, Utilities.bytesToString(setAllData));
    }


    public void setHasV2(boolean robotHasV2) {
        hasV2 = robotHasV2;
    }

    public void setConnected(boolean connected) {
        if (tts != null) {
            String msg = connected ? " connected" : " disconnected";
            tts.say(ttsName + msg);
        }

        isConnected = connected;
        if (connected) {
            initializeSetAllChannel();
            setAllThread.start();
        }
    }
    public boolean isConnected() {
        return isConnected;
    }


    public void startCalibration() {
        sendCommand(CALIBRATE_CMD);

        if (tts != null) {
            tts.say("Beginning calibration of " + ttsName);
        }

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

    public byte[] getCurrentBeak() {
        return Arrays.copyOfRange(setAllData, 1, 4);
    }

    public byte getNotificationDataByte(int index) {
        if (index >= 0 && index < currentData.length) {
            return currentData[index];
        } else {
            return 0;
        }
    }
    public int getNotificationDataUInt(int index) {
        return (getNotificationDataByte(index) & 0xFF); //convert to unsigned int
    }

    public void updateSetAll(int index, byte value) {
        synchronized (setAllDataChannelLock) {
            //setAllData[connection][0] = (byte)0xCA; // op code. this should never get overwritten but playing safe
            setAllData[index] = value;
            setAllChanged = true;
        }
    }

    public void updateSetAllLED(String port, byte rVal, byte gVal, byte bVal) {
        boolean finch = type.equals("FN");
        int ri = 0;
        int gi = 0;
        int bi = 0;
        boolean setAllTail = false;
        if (finch) {
            if (port.equals("all")) {
                setAllTail = true;
            } else {
                int portNum = Integer.parseInt(port) - 1;
                ri = portNum * 3 + 1;
                gi = ri + 1;
                bi = gi + 1;
            }

        } else {
            switch (port) { //LED number to byte mapping
                case "1":
                    ri = 3;
                    gi = 4;
                    bi = 5;
                    break;
                case "2":
                    ri = 6;
                    gi = 7;
                    bi = 8;
                    break;
                default:
                    LOG.error("TriLED port does not exist");
                    return;
            }
        }
        synchronized (setAllDataChannelLock) {
            if (setAllTail) {
                setAllData[4] = rVal;
                setAllData[5] = gVal;
                setAllData[6] = bVal;
                setAllData[7] = rVal;
                setAllData[8] = gVal;
                setAllData[9] = bVal;
                setAllData[10] = rVal;
                setAllData[11] = gVal;
                setAllData[12] = bVal;
                setAllData[13] = rVal;
                setAllData[14] = gVal;
                setAllData[15] = bVal;
            } else {
                //setAllData[connection][0] = (byte)0xCA; // op code. this should never get overwritten but playing safe
                setAllData[ri] = rVal;
                setAllData[gi] = gVal;
                setAllData[bi] = bVal;
            }
            setAllChanged = true;
        }
    }

    public void updateBuzzer(int note, int ms) {
        // Switch off note by default
        byte period_msb = 0;
        byte period_lsb = 0;
        byte duration_msb = 0;
        byte duration_lsb = 0;

        if (!((note == 0) || ms == 0)) { //Valid note to play
            LOG.debug("HummingbirdServlet: playnote parameters: note: {}  ms: {}", note, ms);

            //Calculate the frequency from the MIDI note: https://newt.phys.unsw.edu.au/jw/notes.html
            double exp = (note - 69.0) / 12.0;
            //System.out.println ("exp = " + exp);
            double frequency = Math.pow(2.0, exp) * 440;  //Hz
            //frequencyInt = (int)Math.round(frequency);
            int period_us = (int) Math.round(1000000 / frequency);
            LOG.debug("Hz = {},  period(us) = {}", frequency, period_us);

            //Convert ints to bytes
            period_msb = (byte) (period_us >> 8);
            period_lsb = (byte) period_us;

            duration_msb = (byte) (ms >> 8);
            duration_lsb = (byte) ms;

        }

        //String connType = getConnectionType(connection);
        //boolean finch = connType.equals("FN");
        synchronized (setAllDataChannelLock) {
            setAllData[FREQ_INDEX_MSB] = period_msb;
            setAllData[FREQ_INDEX_LSB] = period_lsb;
            setAllData[DURATION_INDEX_MSB] = duration_msb;
            setAllData[DURATION_INDEX_LSB] = duration_lsb;
            setAllChanged = true;
        }
    }

    private void clearBuzzerBytes () {
        setAllData[FREQ_INDEX_MSB] = 0;
        setAllData[FREQ_INDEX_LSB] = 0;
        setAllData[DURATION_INDEX_MSB] = 0;
        setAllData[DURATION_INDEX_LSB] = 0;
    }

    public void setSymbol(byte[] data) {
        killPrintThread();
        synchronized (ledDisplayChannelLock) {
            //data copy is an atomic operation under the lock
            for(int i = 0; i < data.length; i++)
                ledDisplayData[i] = data[i];
            ledDisplayChanged = true;
            LOG.debug("displayToHummingbird: {}", Utilities.bytesToString(ledDisplayData));
        }
    }

    public void resetEncoders() {
        byte [] command = {(byte)0xD5};
        sendCommand(command);
    }

    public void updateMotors(int speedL, int ticksL, int speedR, int ticksR) {
        byte[] left = getMotorArray(speedL, ticksL);
        byte[] right = getMotorArray(speedR, ticksR);

        synchronized (motorsChannelLock) {

            for (int i = 0; i < 4; i++) {
                motorsData[i] = left[i];
                motorsData[i + 4] = right[i];
            }

            motorsChanged = true;
            LOG.debug("updateMotors: {}", Utilities.bytesToString(motorsData));
        }
    }
    private byte[] getMotorArray(int speed, int ticks){
        int absSpeed = Math.abs(speed);
        int sSpeed = (int) Math.round(absSpeed * FINCH_SPEED_SCALING);
        if (sSpeed < 3 && sSpeed != 0) { sSpeed = 3; }
        byte scaledSpeed = (byte) sSpeed;
        LOG.debug("speed scaling... {} -> {} -> {} -> {}", speed, absSpeed, sSpeed, scaledSpeed);
        //byte scaledSpeed = (byte) Math.abs(Math.round(speed * FINCH_SPEED_SCALING));
        if (speed > 0) { scaledSpeed += 128;}
        byte ticksMSB = (byte) ((ticks & 0xFF0000) >> 16);
        byte ticksSSB = (byte) ((ticks & 0x00FF00) >> 8);
        byte ticksLSB = (byte) (ticks & 0x0000FF);
        byte[] array = new byte[] {scaledSpeed, ticksMSB, ticksSSB, ticksLSB};

        return array;
    }

    public void stopAll() {
        killPrintThread(); // kill printThread if running
        //ScratchME.blueBirdDriver.sendStopAllCommand(connection);

        /*byte [] command = {(byte)0xCB, (byte)0xFF, (byte)0xFF, (byte)0xFF}; // stop All command
        String devType = getConnectionType(connection);
        if (devType.equals("FN")) {
            command[0] = (byte)0xDF;
        }
        LOG.debug("sendStopAllCommand: Channel {},  command: {}", connection, bytesToString(command));

        sendCommand(command, connection);*/
        sendCommand(stopAllCmd);

        //Clear set all array back to initial state
        initializeSetAllChannel();

        killPrintThreadDelay(200);
    }


    public void receiveNotification(byte[] bytes, Short rssi) {
        currentData = bytes;

        if (isCalibrating) {
            //get byte containing calibration bits
            byte calibrationByte = bytes[calibrationIndex];
            byte calibrationStatus = (byte) (calibrationByte & (byte) 0x0C);
            LOG.debug("Calibration Status: {}", calibrationStatus);

            switch (calibrationStatus) {
                case 4:
                    LOG.info("Calibration Successful");
                    if (tts != null) { tts.say("calibration successful"); }
                    isCalibrating = false;
                    FrontendServer.getSharedInstance().showCalibrationResult(true);
                    break;
                case 8:
                    LOG.info("Calibration Failed");
                    if (tts != null) { tts.say("calibration failed"); }
                    isCalibrating = false;
                    FrontendServer.getSharedInstance().showCalibrationResult(false);
                    break;
            }
        }

        //Check battery state
        byte battByte = bytes[batteryIndex];
        int battInt = battByte;
        int battUInt = battByte & batteryMask;
        // LOG.debug("Battery: Byte = 0x{}, mask = 0x{}, result = 0x{}", 
        //         Integer.toHexString((int)(battByte & 0xff)),
        //         Integer.toHexString(batteryMask),
        //         Integer.toHexString(battUInt));
        double voltage = (battUInt + voltageConst) * rawToVoltage;
        // LOG.debug("Robot status: {} volts, RSSI {} dBm", voltage, rssi);
        String battLevel = "unknown";
        switch (currentBattery) {
            case "unknown":
                if (voltage > fullThresh) {
                    battLevel = "full";
                } else if (voltage > greenThresh) {
                    battLevel = "green";
                } else if (voltage > yellowThresh) {
                    battLevel = "yellow";
                } else {
                    battLevel = "red";
                }
                break;
            case "full":
                if (voltage > fullThresh - batteryTolerance) {
                    battLevel = "full";
                } else if (voltage > greenThresh) {
                    battLevel = "green";
                } else if (voltage > yellowThresh) {
                    battLevel = "yellow";
                } else {
                    battLevel = "red";
                }
                break;
            case "green":
                if (voltage > fullThresh + batteryTolerance) {
                    battLevel = "full";
                } else if (voltage > greenThresh - batteryTolerance) {
                    battLevel = "green";
                } else if (voltage > yellowThresh) {
                    battLevel = "yellow";
                } else {
                    battLevel = "red";
                }
                break;
            case "yellow":
                if (voltage > fullThresh) {
                    battLevel = "full";
                } else if (voltage > greenThresh + batteryTolerance) {
                    battLevel = "green";
                } else if (voltage > yellowThresh - batteryTolerance) {
                    battLevel = "yellow";
                } else {
                    battLevel = "red";
                }
                break;
            case "red":
                if (voltage > fullThresh) {
                    battLevel = "full";
                } else if (voltage > greenThresh) {
                    battLevel = "green";
                } else if (voltage > yellowThresh + batteryTolerance) {
                    battLevel = "yellow";
                } else {
                    battLevel = "red";
                }
                break;
        }

        String rssiLevel = (rssi == null ? "" : String.valueOf(rssi));

        boolean rssiChanged = !rssiLevel.equals(currentRSSI);
        boolean battChanged = !battLevel.equals(currentBattery);
        if (rssiChanged || battChanged) {
            currentRSSI = rssiLevel;
            currentBattery = battLevel;
            FrontendServer.getSharedInstance().updateBatteryState(name, battLevel, rssiLevel);

            if (battChanged && tts != null) {
                tts.say(ttsName + " battery " + battLevel);
            }
        }


        //hashtable.put(devLetter, battLevel);
        //LOG.debug("getBatteryData for {}: {} -> {} -> {}", i, battUInt, battData, battLevel);
    }

    public void startPrint (char[] charBuf) {
        try {
            // kill printThread before starting a new one.
            killPrintThread ();

            printMonitor = new PrintMonitor (charBuf);
            printThread = new Thread(printMonitor);
            printThread.start();

        } catch (Exception e) {
            LOG.info("startPrint(): Exception: {}", e.toString());
        }
    }

    private void sendPrintCommand(char[] cArray) {
        byte[] flashCommand = new byte [20];
        int wordLength = 0;
        int j = 2;
        for (int i = 0; i < cArray.length; i++) {
            if (i >= MAX_LED_PRINT_WORD_LEN)
                break;
            LOG.debug("Char[{}]: {}, Word Length: {}", i, cArray[i], wordLength);
            flashCommand[j++] = (byte)(cArray[i]);
            wordLength++;
        }

        flashCommand[0] = (byte)0xCC;
        byte flashLength = (byte)(wordLength);
        byte flashByte = (byte)(flashLength & 0x0F);
        flashByte |= (4 << 4) & 0xFF;
        flashCommand[1] = flashByte;

        /*LOG.debug("Microbit print bytes: {}" , ScratchME.blueBirdDriver.bytesToString(flashCommand));
        ScratchME.blueBirdDriver.printToHummingbird(connection, flashCommand);*/

        synchronized (ledPrintChannelLock) {
            //data copy is an atomic operation under the lock
            for(int i = 0; i < flashCommand.length; i++)
                ledPrintData[i] = flashCommand[i];
            ledPrintChanged = true;
            LOG.debug("printToHummingbird {}", Utilities.bytesToString(ledPrintData));
        }
    }

    private class PrintMonitor extends Thread {
        boolean quit = false;
        char [] cArray;
        //int connection;

        public PrintMonitor(char[] charBuf) {
            cArray = charBuf;
            //connection = conn;
        }

        @Override
        public void run() {
            LOG.debug("Thread PrintMonitor" + this.getName() + " started");
            try {
                // beg and end are indices, not length.
                int beg = 0;
                int end = 0;

                while (((end) < (cArray.length)) && (!Thread.currentThread().isInterrupted())) {
                    if ((beg + MAX_LED_PRINT_WORD_LEN) < cArray.length) {
                        end = (beg + MAX_LED_PRINT_WORD_LEN) -1;
                        LOG.debug("Mid-bucket");
                    }
                    else {
                        end = beg + (cArray.length - beg) -1;
                        LOG.debug("Last-bucket");
                    }

                    LOG.debug ("Beginning: {}, End: {}", beg, end);

                    char [] charBuffer = Utilities.subArray(cArray, beg, end);

                    beg = end+1;
                    end = beg;

                    // Don't send command if thread got interrupted mid loop.
                    if (Thread.currentThread().isInterrupted())
                        break;
                    sendPrintCommand(charBuffer);

                    Thread.sleep(charBuffer.length * 600); // number of chars * 600ms per char
                }

                LOG.debug("Thread PrintMonitor {} ended", this.getName());

            } catch (InterruptedException e) {
                LOG.debug("HummingbirdServelet {} Interrupted : {}" , this.getName(), e.toString());
            }
        }

        public void quit () {
            LOG.info("PrintMonitor() Timeout: Sending quit signal");
            //this.interupt();
        }
    }

    public void killPrintThread () {
        // kill the print thread.
        if (printThread != null)
            if (printThread.isAlive()) {
                printThread.interrupt(); // Kill the thread before displaying LED matrix
            }
    }

    public void killPrintThreadDelay (long delay) {
        // kill the print thread.
        if (printThread != null)
            try {
                Thread.sleep(delay);
                if (printThread.isAlive()) {
                    printThread.interrupt(); // Kill the thread before displaying LED matrix
                }

            } catch (InterruptedException e) {
                LOG.debug("killPrintThreadDelay {} Interrupted : {}", e.toString());
            }
    }

    private class SetAllThread extends Thread {
        @Override
        public void run() {
            while(isConnected) {

                boolean firstCommandSent = false;
                //Send set all
                synchronized (setAllDataChannelLock) {
                    if (setAllChanged) {
                        try {
                            LOG.debug("sendSetAllWriteCommand: sending SetAll data to {}", name);
                            LOG.debug("{}", Utilities.bytesToString(setAllData));
                            sendCommand(setAllData);
                            clearBuzzerBytes();
                            firstCommandSent = true;
                        } catch (Exception e) {
                            LOG.error("SetAll ERROR: " + e.toString());
                            e.printStackTrace();
                        } finally {
                            setAllChanged = false;
                        }
                    }
                }

                if (firstCommandSent) {
                    try {
                        Thread.sleep(COMMAND_INTERVAL);
                    } catch (InterruptedException e) {
                        LOG.error("Error sleeping");
                        e.printStackTrace();
                    }
                }

                boolean secondCommandSent = false;
                if (type.equals("FN")) {
                    synchronized (motorsChannelLock) { //TODO: lock other channels?
                        byte[] ledDisplay = ledDisplayData;
                        byte[] ledPrint = ledPrintData;
                        byte[] motors = motorsData;
                        int printlength = ledPrint[1] - 64;
                        byte[] command = new byte[20];
                        command[0] = (byte)0xD2;

                        byte mode = 0;
                        if (motorsChanged){
                            for (int i = 0; i < 8; i++){
                                command[i+2] = motors[i];
                            }
                            if (ledPrintChanged){
                                mode = (byte)(0x80 + printlength);
                                for (int i = 0; i < printlength; i++){
                                    command[i+10] = ledPrint[i+2];
                                }
                            } else if (ledDisplayChanged){
                                mode = 0x60;
                                for (int i = 0; i < 4; i++){
                                    command[i+10] = ledDisplay[i+2];
                                }
                            } else {
                                mode = 0x40;
                            }
                        } else if (ledPrintChanged) {
                            mode = (byte)printlength;
                            for (int i = 0; i < printlength; i++){
                                command[i+2] = ledPrint[i+2];
                            }
                        } else if (ledDisplayChanged) {
                            mode = 0x20;
                            for (int i = 0; i < 4; i++){
                                command[i+2] = ledDisplay[i+2];
                            }
                        }
                        command[1] = mode;

                        if (mode != 0) {
                            LOG.debug("sendFinchMotorsCommand printlength={} ledPrint={}", printlength, Utilities.bytesToString(ledPrint));
                            sendCommand(command);
                            ledDisplayChanged = false;
                            ledPrintChanged = false;
                            motorsChanged = false;
                            secondCommandSent = true;
                        }

                    }
                } else {
                    synchronized (ledDisplayChannelLock) {
                        if (ledDisplayChanged) {
                            try {
                                LOG.debug("Sending ledDisplayData Data to {}", name);
                                sendCommand(ledDisplayData);
                                secondCommandSent = true;
                            } catch (Exception e) {
                                LOG.error("ERROR: ledDisplay Timer: {}" , e.toString());
                                e.printStackTrace();
                            } finally {
                                ledDisplayChanged = false;
                            }
                        }
                    }
                    synchronized (ledPrintChannelLock) {
                        if (ledPrintChanged) {
                            try {
                                LOG.debug("Sending ledPrint Data to {}, Print bytes: {}", name, Utilities.bytesToString(ledPrintData));
                                sendCommand(ledPrintData);
                                secondCommandSent = true;
                            } catch (Exception e) {
                                LOG.error("ERROR: ledPrint Timer: {}" , e.toString());
                                e.printStackTrace();
                            } finally {
                                ledPrintChanged = false;
                            }
                        }
                    }
                }


                if (secondCommandSent || !firstCommandSent) {
                    try {
                        Thread.sleep(COMMAND_INTERVAL);
                    } catch (InterruptedException e) {
                        LOG.error("Error sleeping");
                        e.printStackTrace();
                    }
                }

            }
        }
    }

}


