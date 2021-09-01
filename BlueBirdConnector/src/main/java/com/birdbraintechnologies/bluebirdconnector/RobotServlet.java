package com.birdbraintechnologies.bluebirdconnector;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.util.Arrays;

import static com.birdbraintechnologies.bluebirdconnector.RobotManager.MAX_LED_PRINT_WORD_LEN;

public class RobotServlet extends HttpServlet {
    static final Logger LOG = LoggerFactory.getLogger(RobotServlet.class);

    private RobotManager robotManager = RobotManager.getSharedInstance();

    //Hummingbird specific service UUIDs and characteristics
    /*static final String  HB_WRITE_SVC_UUID  = "6e4002b5a3f393e0a9e5e24dcca9e";
    static final String  HB_NOTIFY_SVC_UUID = "6e4003b5a3f393e0a9e5e24dcca9e";
    static final int  HB_NOTIFY_CTL_CHAR = 0x2902;*/

    static final String hIn = "/hummingbird/in/";
    static final String hOut = "/hummingbird/out/";

    //protected DeviceIdObj deviceIdObj = new DeviceIdObj();
    //long startTime = 0;

    //byte [] buzzerCommand = new byte [5];  // not used in favor of SetAll array

    byte [] symbolCommand = new byte [6];
    byte [] clearLED = new byte [] {(byte)0xCC, (byte)0x00, (byte)0xFF, (byte)0xFF, (byte)0xFF};  // This shouldn't be needed
    byte [] stopAllCommand = new byte [4];




    protected void doGet(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws ServletException, IOException
    {
        ServletOutputStream out = httpServletResponse.getOutputStream();
        httpServletResponse.setContentType("text/plain");

        /*if (ScratchME.blueBirdDriver == null)
            return;*/

        //http Delay in milliseconds used to slow down the http response to save CPU usage by Snap in the browser.
        //set to 0 to disable.
        //int http_delay = ScratchME.blueBirdDriver.http_delay;
        int http_delay = 0;
        String uri = httpServletRequest.getRequestURI();
        LOG.debug("Request URI = {}" , uri);

        //byte devId = -1;
        //byte devNum = 0;
        char devLetter = '\0';
        //String devName = null;
        //String devType = "";
        //int connection = -1;
        String value = null; //response value to request
        int index = -1; // Notification array index

        //playnote
        int note = 0;
        int ms = 0;
        //int frequencyInt = 0;
        //double frequency = 0;

        if (uri.startsWith(hIn)){  //BLE notifications
            String[] params;
            LOG.debug("Incoming hummingbird notification data");
            String parameterPath = uri.substring(hIn.length(), uri.length());
            LOG.debug("Incoming hummingbird parameterPath: {}", parameterPath);

            params  = parameterPath.split("/");

            //Keep things working with old compass blocks
            if (params[0].equals("Compass")) {
                String[] tmp = params;
                params = new String[tmp.length + 1];
                params[0] = tmp[0];
                params[1] = "static";
                if (params.length == 3) { params[2] = tmp[1]; }
            }

            for (int i=0; i<params.length; i++)
                LOG.debug("Parameter {}: {}" , i , params[i]);

            try {
                Thread.sleep(http_delay);
                LOG.debug("http_delay = {}", http_delay);
            } catch (InterruptedException e) {
                LOG.error("doGet sleep interrupted: {}", e.getMessage());
                e.printStackTrace();
            }

            if (params.length == 2) { // Single device only
                devLetter = 'A'; //Single device always is letter A
            } else if (params.length == 3) {
                devLetter = params[2].charAt(0);
            } else {
                LOG.error("HummingbirdServelet: Bad Block Parameters: {}" , parameterPath);
                value =  "null";
                out.print(value);
                return;
            }
/*
            if (!ScratchME.blueBirdDriver.isConnected(devLetter)) {
                out.print("Not Connected");
                return;
            }

            devType = ScratchME.blueBirdDriver.getConnectionType(ScratchME.blueBirdDriver.getConnectionFromDevLetter(devLetter));
*/
            Robot robot = robotManager.getConnectedRobot(devLetter, "Cannot get " + params[0]);
            if (robot == null) {
                out.print("Not Connected");
                return;
            }

            byte b;
            float g;
            switch (params[0]) {
                case "isMicrobit":
                    if (robot.type.equals("MB")) { out.print("true");
                    } else { out.print("false"); }
                    break;
                case "isHummingbird":
                    if (robot.type.equals("HB") || robot.type.equals("BB")) { out.print("true");
                    } else { out.print("false"); }
                    break;
                case "isFinch":
                    if (robot.type.equals("FN")) { out.print("true");
                    } else { out.print("false"); }
                    break;
                case "Distance" :
                    //In the case of the finch, Distance sensor is at one fixed place. 2 values are returned for V1 micro:bit and 1 value for V2
                    if (params[1].equals("static"))  {
                        int val;
                        if (robot.hasV2) {
                            val = robot.getNotificationDataUInt(1); //value already in cm
                        } else {
                            int msb = robot.getNotificationDataUInt(0); //convert signed byte to unsigned 8 bit int
                            int lsb = robot.getNotificationDataUInt(1);
                            val = (int) Math.round(((msb << 8) + lsb) * 0.0919); //return the value in cm
                        }
                        value = Integer.toString(val);
                        out.print(value);
                        break;
                    }
                    //case "Knob" :
                case "Light" :
                case "Dial" :
                case "Sound" :
                case "Other" :
                case "Line": //finch only
                case "sensor" : //old block compatibliity

                    //For finch, it is right and left sensor, not ports
                    switch (params[1]) {
                        case "Right":
                            switch (params[0]) {
                                case "Light":
                                    index = 3;
                                    break;
                                case "Line":
                                    index = 5;
                                    break;
                                default:
                                    LOG.error("Unsupported Right sensor " + params[0]);
                                    out.print("Unsupported sensor error");
                                    return;
                            }
                            break;
                        case "Left":
                            switch (params[0]) {
                                case "Light":
                                    index = 2;
                                    break;
                                case "Line":
                                    index = 4;
                                    break;
                                default:
                                    LOG.error("Unsupported Left sensor " + params[0]);
                                    out.print("Unsupported sensor error");
                                    return;
                            }
                            break;
                        default:
                            index = Integer.parseInt(params[1]) - 1;
                            if (index < 0) {
                                out.print("Index error");
                                return;  //To user
                            }
                    }


                    /*value = ScratchME.blueBirdDriver.getNotificationDataString(index, devLetter); //Sensor port number - 1

                    if (value.equals("Not Connected")){
                        out.print(value);
                    } else {//Send value of data as response
                        int v = Integer.parseInt(value);*/

                    int v = robot.getNotificationDataUInt(index);
                    value = String.valueOf(v);
                    if ((v > 230) && (params[0].equals("Dial"))) {
                        out.print("230");
                    } else if (params[0].equals("Line")) {
                        int val = v;
                        if (params[1].equals("Left") && (v > 127)) {
                            //Must remove the finch move finished flag
                            val = v - 128;
                        }
                        int realVal = 100 - ((val - 6) * 100 / 121);
                        realVal = Math.max(0, Math.min(100, realVal));
                        out.print(Integer.toString(realVal));
                    } else if (params[0].equals("Light")) {
                        //byte[] beak = ScratchME.blueBirdDriver.getCurrentBeak(devLetter);
                        byte[] beak = robot.getCurrentBeak();
                        long R = Math.round((beak[0] & 0xFF) / 2.55);
                        long G = Math.round((beak[1] & 0xFF) / 2.55);
                        long B = Math.round((beak[2] & 0xFF) / 2.55);

                        Double raw = Double.valueOf(value);
                        Double correction;
                        if (params[1].equals("Right")) {
                            correction = 6.40473070e-03*R +  1.41015162e-02*G +  5.05547817e-02*B +  3.98301391e-04*R*G +  4.41091223e-04*R*B +  6.40756862e-04*G*B + -4.76971242e-06*R*G*B;
                        } else {
                            correction = 1.06871493e-02*R +  1.94526614e-02*G +  6.12409825e-02*B +  4.01343475e-04*R*G + 4.25761981e-04*R*B +  6.46091068e-04*G*B + -4.41056971e-06*R*G*B;
                        }
                        LOG.debug("Correcting " + params[1] + " light sensor raw value " + raw + " by " + Math.round(correction) + " : " + R + "," + G + "," + B);
                        out.print(Integer.toString(Math.min(100, Math.max(0, (int)Math.round(raw - correction)))));
                    } else {
                        out.print(value);
                    }
                    //}
                    break;
                case "Encoder": //finch only
                    index = 7;
                    if (params[1].equals("Right")) index = 10;

                    int msb = robot.getNotificationDataUInt(index);
                    int ssb = robot.getNotificationDataUInt( (index+1) );
                    int lsb = robot.getNotificationDataUInt( (index+2) );

                    int unsigned = (msb << 16) + (ssb << 8) + lsb;
                    int signed = (unsigned << 8) >> 8;
                    LOG.debug("Creating the encoder value from {}, {}, {}; unsigned is {}, signed is {}", msb, ssb, lsb, unsigned, signed);
                    double rotations = Math.round(((double)signed/RobotManager.FINCH_TICKS_PER_ROTATION)*100.0)/100.0;
                    out.print(Double.toString(rotations));
                    //out.print(Integer.toString(signed));
                    break;
                case "Accelerometer" :
                    int xIndex = 4;
                    if (robot.type.equals("FN")) { xIndex = 13; }
                    switch (params[1]) { //XYZ number to byte mapping
                        case "X":
                            index = xIndex;
                            break;
                        case "Y":
                            index = xIndex + 1;
                            break;
                        case "Z":
                            index = xIndex + 2;
                            break;
                        case "All":
                            //TODO query all and return list
                            break;
                        default:
                            LOG.error("Accelerometer dimension does not exist at given input {}", parameterPath);
                    }

                    if (index < 0) {
                        out.print("Error");
                        return;  //To user
                    }

                    b = robot.getNotificationDataByte(index);
                    g = getGravity(b);
                    out.print(roundToString(g * (float)9.8));  //gravity expressed in 9.8 ms/s
                    break;
                case "finchAccel": //accelerometer values in finch reference frame
                    double accel = getFinchAcceleration(params[1], devLetter);
                    g = getGravity(accel);
                    out.print(roundToString(g * (float)9.8));  //gravity expressed in 9.8 ms/s
                    break;
                case "finchMag":
                    double magVal = getFinchMagnetometer(params[1], devLetter);
                    int mv = (int)Math.round(magVal);
                    out.print(String.valueOf(mv));
                    break;
                case "finchCompass":
                    int headingDeg = (int) Math.round(rawToCompass(devLetter, robot.type, true));
                    headingDeg = (headingDeg + 180) % 360; //turn it around so that the finch beak points north at 0
                    LOG.debug("Rounded Finch Compass Heading: {}", headingDeg);
                    out.print(String.valueOf(headingDeg));
                    break;
                case "Magnetometer" :
                    short magValue = 0;

                    if (robot.type.equals("FN")) {
                        switch (params[1]) {
                            case "X":
                                index = 17;
                                break;
                            case "Y":
                                index = 18;
                                break;
                            case "Z":
                                index = 19;
                                break;
                            default:
                                LOG.error("Finch Magnetometer dimension does not exist at given input {} ", parameterPath);
                                value = "Error";
                                out.print(value);
                                return;
                        }
                        magValue = (short)robot.getNotificationDataByte(index);
                        LOG.debug("Finch magnetometer {} value: {}", params[1], magValue);
                    } else {

                        switch (params[1]) { //XYZ number to byte mapping
                            case "X":
                                magValue = bytes2short(8, 9, devLetter);
                                LOG.debug("Magnetometer X value: {}", magValue);
                                break;
                            case "Y":
                                magValue = bytes2short(10, 11, devLetter);
                                LOG.debug("Magnetometer Y value: {}", magValue);
                                break;
                            case "Z":
                                magValue = bytes2short(12, 13, devLetter);
                                LOG.debug("Magnetometer Z value: {}", magValue);
                                break;
                            case "All":
                                //TODO query all and return list
                                break;
                            default:
                                LOG.error("Magnetometer dimension does not exist at given input {} ", parameterPath);
                                value = "Error";
                                out.print(value);
                                return;
                        }

                        magValue = (short)Math.round(magValue * 0.1); //convert value to uT.
                    }
                    out.print(String.valueOf(magValue));

                    break;
                case "button" : //old block compatibliity

                    index = 7;
                    if (robot.type.equals("FN")) { index = 16; }

                    //byte buttonState = (byte)(ScratchME.blueBirdDriver.getNotificationDataByte(index, devLetter) & (byte)0xF0); //Button Byte position = 7, clear LS bits as it is for shake and calibrate
                    byte buttonState = robot.getNotificationDataByte(index);

                    //Get the button letter
                    String buttonLetter = params[1].toUpperCase();
                    LOG.debug("ButtonLetter = {}, buttonState = {} (0x{})", buttonLetter, buttonState, Integer.toHexString(buttonState));

                    //Both buttons pressed give value 0
                    /*if (buttonState == 0x00) {
                        out.print("true");
                        break;
                    }*/

                    if (buttonLetter.equals("A")) {
                        LOG.debug("Button A selected");
                        //if (buttonState == 0x20)
                        if ((buttonState & 0x10) == 0)
                            out.print("true");
                        else
                            out.print("false");
                    }
                    else if (buttonLetter.equals("B")) {
                        LOG.debug("Button B selected");
                        //if (buttonState == 0x10)
                        if ((buttonState & 0x20) == 0)
                            out.print("true");
                        else
                            out.print("false");
                    }
                    else if (buttonLetter.equals("LOGO")) {
                        LOG.debug("Button LOGO selected");
                        //if (ScratchME.blueBirdDriver.getMicrobitVersion(devLetter) != 2) {
                        if (!robot.hasV2) {
                            out.print("micro:bit V2 required");
                        } else if ((buttonState & 0x02) == 0){
                            out.print("true");
                        } else {
                            out.print("false");
                        }

                    }
                    else out.print("No Button Selected");

                    //Send value of data as response
                    //out.print(Integer.toHexString(buttonState & 0xFE));  //Mask LSB as it is for shake.
                    break;
                case "V2sensor":
                    //if (ScratchME.blueBirdDriver.getMicrobitVersion(devLetter) != 2) {
                    if (!robot.hasV2) {
                        out.print("micro:bit V2 required");
                    } else {
                        String sensor = params[1].toUpperCase();
                        if (sensor.equals("SOUND")) {
                            index = 14;
                            if (robot.type.equals("FN")) { index = 0; }
                            int sound = robot.getNotificationDataUInt(index);
                            out.print(String.valueOf(sound));
                        } else if (sensor.equals("TEMPERATURE")) {
                            int temp = robot.getNotificationDataUInt(15);
                            if (robot.type.equals("FN")) {
                                temp = robot.getNotificationDataUInt(6);
                                temp = temp >> 2;
                            }
                            out.print(String.valueOf(temp));
                        } else {
                            out.print("invalid V2 sensor " + sensor);
                        }
                    }
                    break;
                case "finchOrientation" :
                    switch (params[1]) {
                        case "Tilt%20Right":   //X axis
                            g = getGravity(getFinchAcceleration("X", devLetter));
                            if (g > 0.8)
                                out.print("true");
                            else
                                out.print("false");
                            break;
                        case "Tilt%20Left":
                            g = getGravity(getFinchAcceleration("X", devLetter));
                            if (g < -0.8)
                                out.print("true");
                            else
                                out.print("false");
                            break;
                        case "Beak%20Down":     // Y axis
                            g = getGravity(getFinchAcceleration("Y", devLetter));
                            if (g < -0.8)
                                out.print("true");
                            else
                                out.print("false");
                            break;
                        case "Beak%20Up":
                            g = getGravity(getFinchAcceleration("Y", devLetter));
                            if (g > 0.8)
                                out.print("true");
                            else
                                out.print("false");
                            break;
                        case "Level":     // Z axis
                            g = getGravity(getFinchAcceleration("Z", devLetter));
                            if (g < -0.8)
                                out.print("true");
                            else
                                out.print("false");
                            break;
                        case "Upside%20Down":
                            g = getGravity(getFinchAcceleration("Z", devLetter));
                            if (g > 0.8)
                                out.print("true");
                            else
                                out.print("false");
                            break;
                        case "Shake":
                            index = 16; //Button/shake Byte position = 16 for finch
                            b = robot.getNotificationDataByte(index);
                            byte shakebyte = (byte) (b & 0x01); //LSB is shake T/F
                            if (shakebyte > 0)
                                out.print("true");
                            else
                                out.print("false");
                            break;
                        default:
                            LOG.error("Finch Orientation does not exist at given input {}", parameterPath);
                    }
                    break;
                case "orientation" :

                    int accXindex = 4;
                    if (robot.type.equals("FN")) { accXindex = 13; }
                    switch (params[1]) { //XYZ number to byte mapping
                        case "Tilt%20Left":   //X axis
                            index = accXindex;
                            b = robot.getNotificationDataByte(index);
                            g = getGravity(b);
                            if (g > 0.8)
                                out.print("true");
                            else
                                out.print("false");
                            break;
                        case "Tilt%20Right":
                            index = accXindex;
                            b = robot.getNotificationDataByte(index);
                            g = getGravity(b);
                            if (g < -0.8)
                                out.print("true");
                            else
                                out.print("false");
                            break;
                        case "Logo%20Up":     // Y axis
                            index = accXindex + 1;
                            b = robot.getNotificationDataByte(index);
                            g = getGravity(b);
                            if (g < -0.8)
                                out.print("true");
                            else
                                out.print("false");
                            break;
                        case "Logo%20Down":
                            index = accXindex + 1;
                            b = robot.getNotificationDataByte(index);
                            g = getGravity(b);
                            if (g > 0.8)
                                out.print("true");
                            else
                                out.print("false");
                            break;
                        case "Screen%20Up":     // Z axis
                            index = accXindex + 2;
                            b = robot.getNotificationDataByte(index);
                            g = getGravity(b);
                            if (g < -0.8)
                                out.print("true");
                            else
                                out.print("false");
                            break;
                        case "Screen%20Down":
                            index = accXindex + 2;
                            b = robot.getNotificationDataByte(index);
                            g = getGravity(b);
                            if (g > 0.8)
                                out.print("true");
                            else
                                out.print("false");
                            break;
                        case "Shake" :

                            index = 7; //Button/shake Byte position = 7
                            if (robot.type.equals("FN")) { index = 16; }

                            b = robot.getNotificationDataByte(index);
                            byte shakebyte = (byte)(b & 0x01); //LSB is shake T/F
                            if (shakebyte > 0)
                                out.print("true");
                            else
                                out.print("false");
                            break;
                        default:
                            LOG.error("Shake dimension does not exist at given input {}", parameterPath);
                            break;
                    }

                    break;
                case "Compass" :
                    try {
                        LOG.debug("Compass algorithm:");
                        //round the double and convert to int
                        int headingDegrees = (int) Math.round(rawToCompass(devLetter, robot.type, false));

                        LOG.debug("Rounded Compass Heading: {}", headingDegrees);
                        out.print(String.valueOf(headingDegrees));
                    }catch (Exception e){
                        LOG.warn("HummingbirdServelet Compass Error: Usually this occurs when notifications are not enabled. {}", Utilities.stackTraceToString(e));
                    }
                    break;
                case "finchIsMoving":
                    byte data = robot.getNotificationDataByte(4);
                    boolean finchIsMoving = (data < 0);
                    LOG.debug("finchIsMoving {} from {}", finchIsMoving, data);
                    if (finchIsMoving) {
                        out.print("true");
                    } else {
                        out.print("false");
                    }
                    break;
                default:
                    LOG.debug("Unknown device");
                    break;
            }
        } else if (uri.startsWith(hOut)) {

            LOG.debug("Outgoing hummingbird command");
            String parameterPath = uri.substring(hOut.length(), uri.length());
            LOG.debug("Outgoing hummingbird parameterPath: {} ", parameterPath);

            String[] params = parameterPath.split("/");
            for (int i = 0; i < params.length; i++)
                LOG.debug("Parameter {}: {}", i, params[i]);

            switch (params[0]) {
                case "led":
                    try {
                        byte level = 0;  //LED level
                        if (params.length == 3) {  // Single device only
                            //devName = "BB";
                            //devNum = 1; //devId is always 1 for single device
                            devLetter = 'A';
                        } else if (params.length == 4) {
                            //deviceIdObj = getDevNameFromDropdown(params[3]);
                            //devName = deviceIdObj.name;
                            //devNum = (byte)(deviceIdObj.devNum);
                            devLetter = params[3].charAt(0);
                        } else
                            LOG.error("HummingbirdServelet: led command Block Bad Parameters: {}", parameterPath);
                        switch (Integer.parseInt(params[1])) { //LED number to byte mapping
                            case 1:
                                index = 1;
                                break;
                            case 2:
                                index = 13;
                                break;
                            case 3:
                                index = 14;
                                break;
                            default:
                                LOG.error("led data does not exist at given index");
                                return;
                        }

                        level = (byte) (Integer.parseInt(params[2]));
                        /*connection = ScratchME.blueBirdDriver.getConnectionFromDevLetter(devLetter);
                        if (connection > -1) {
                            ScratchME.blueBirdDriver.updateSetAll(connection, index, level);
                        } else LOG.error("HummingbirdServelet: Nothing connected");*/
                        robotManager.updateSetAll(devLetter, index, level);
                    } catch (Exception e) {
                        LOG.error("HummingbirdServelet Error: {}" + e.toString());
                    }
                    break;
                case "triled":
                    int r, g, b;    // SetAll array indices for TriLED rgb values
                    if (params.length == 5) {  // Single device only
                        //devName = "BB";
                        //devNum = 1; //devId is always 1 for single device
                        devLetter = 'A';
                    } else if (params.length == 6) {
                        //deviceIdObj = getDevNameFromDropdown(params[5]);
                        //devName = deviceIdObj.name;
                        //devNum = (byte)(deviceIdObj.devNum);
                        devLetter = params[5].charAt(0);
                    } else
                        LOG.debug("HummingbirdServelet: Tri-LED command Block Bad Parameters: {}", parameterPath);

                    //byte rlevel, glevel, blevel;
                    //int port = Integer.parseInt(params[1]);

                    /*connection = ScratchME.blueBirdDriver.getConnectionFromDevLetter(devLetter);
                    if (connection > -1) {
                        //rgb levels
                        ScratchME.blueBirdDriver.updateSetAllLED(connection, params[1], (byte) (Integer.parseInt(params[2])), (byte) (Integer.parseInt(params[3])), (byte) (Integer.parseInt(params[4])));
                    } else LOG.error("HummingbirdServelet: tri-LED Nothing connected");*/
                    robotManager.updateSetAllLED(devLetter, params[1], (byte) (Integer.parseInt(params[2])), (byte) (Integer.parseInt(params[3])), (byte) (Integer.parseInt(params[4])));
                    break;
                case "servo":
                case "motor":
                case "rotation":
                    if (params.length == 3) {  // Single device only
                        //devName = "BB";
                        // devNum = 1; //devId is always 1 for single device
                        devLetter = 'A';
                    } else if (params.length == 4) {
                        //deviceIdObj = getDevNameFromDropdown(params[3]);
                        //devName = deviceIdObj.name;
                        //devNum = (byte)(deviceIdObj.devNum);
                        devLetter = params[3].charAt(0);
                    } else
                        LOG.error("HummingbirdServelet: led motor Block Bad Parameters: {}", parameterPath);

                    switch (Integer.parseInt(params[1])) { //servo port number to byte mapping
                        case 1:
                            index = 9;
                            break;
                        case 2:
                            index = 10;
                            break;
                        case 3:
                            index = 11;
                            break;
                        case 4:
                            index = 12;
                            break;
                        default:
                            LOG.error("servo port does not exist");
                            return;
                    }
                    /*connection = ScratchME.blueBirdDriver.getConnectionFromDevLetter(devLetter);
                    if (connection > -1) {
                        ScratchME.blueBirdDriver.updateSetAll(connection, index, (byte) (Integer.parseInt(params[2])));
                    } else LOG.error("HummingbirdServelet: Servo: Nothing connected");*/
                    robotManager.updateSetAll(devLetter, index, (byte) (Integer.parseInt(params[2])));
                    break;
                case "playnote":
                    try {
                        if (params.length == 3) {  // Single device only
                            //devName = "BB";
                            //devNum = 1; //devId is always 1 for single device
                            devLetter = 'A';
                        } else if (params.length == 4) {
                            //deviceIdObj = getDevNameFromDropdown(params[3]);
                            //devName = deviceIdObj.name;
                            // devNum = (byte)(deviceIdObj.devNum);
                            devLetter = params[3].charAt(0);
                        } else
                            LOG.error("HummingbirdServelet: playnote command Block Bad Parameters: {}", parameterPath);

                        //note and ms will take 2 bytes when xferring to humminbird
                        note = Integer.parseInt(params[1]);
                        ms = Integer.parseInt(params[2]);

                        robotManager.updateBuzzer(devLetter, note, ms);

/*                        byte period_msb = 0;
                        byte period_lsb = 0;
                        byte duration_msb = 0;
                        byte duration_lsb = 0;

                        if (!((note == 0) || ms == 0)) { //Valid note to play
                            LOG.debug("HummingbirdServelet: playnote parameters: note: {}  ms: {}", note, ms);

                            //Calculate the frequency from the MIDI note: https://newt.phys.unsw.edu.au/jw/notes.html
                            double exp = (note - 69.0) / 12.0;
                            //System.out.println ("exp = " + exp);
                            frequency = Math.pow(2.0, exp) * 440;  //Hz
                            //frequencyInt = (int)Math.round(frequency);
                            int period_us = (int) Math.round(1000000 / frequency);
                            LOG.debug("Hz = {},  period(us) = {}", frequency, period_us);

                            //Convert ints to bytes
                            period_msb = (byte) (period_us >> 8);
                            period_lsb = (byte) period_us;

                            duration_msb = (byte) (ms >> 8);
                            duration_lsb = (byte) ms;

                        } else {  // Switch off note
                            period_msb = 0;
                            period_lsb = 0;

                            duration_msb = 0;
                            duration_lsb = 0;
                        }

                        connection = ScratchME.blueBirdDriver.getConnectionFromDevLetter(devLetter);
                        if (connection > -1) {
                            buzzerCommand[0] = (byte) 0xCD;
                            buzzerCommand[1] = period_msb;
                            buzzerCommand[2] = period_lsb;
                            buzzerCommand[3] = duration_msb;
                            buzzerCommand[4] = duration_lsb;
                            LOG.debug("Buzzer bytes: {}", ScratchME.blueBirdDriver.bytesToString(buzzerCommand));

                            //Set All Experiment
                            //writeToHummingbird(connection, buzzerCommand);  // Independent command

                            ScratchME.blueBirdDriver.updateSetAllPlayNote(connection, period_msb, period_lsb, duration_msb, duration_lsb);

                            LOG.debug("{}", ScratchME.blueBirdDriver.bytesToString(ScratchME.blueBirdDriver.setAllData[connection]));


                        } else LOG.error("HummingbirdServelet: Buzzer: Nothing connected");*/

                    } catch (Exception e) {
                        LOG.error("HummingbirdServelet playnote Error: {}", e.toString());
                        e.printStackTrace();
                    }
                    break;
                case "print":
                    try {
                        if (params.length == 2) {  // Single device only
                            //devName = "BB";
                            //devNum = 1; //devId is always 1 for single device
                            devLetter = 'A';
                        } else if (params.length == 3) {
                            //deviceIdObj = getDevNameFromDropdown(params[3]);
                            //devName = deviceIdObj.name;
                            // devNum = (byte)(deviceIdObj.devNum);
                            devLetter = params[2].charAt(0);
                        } else
                            LOG.error("HummingbirdServelet: print command Block Bad Parameters: {}", parameterPath);

                        /*connection = ScratchME.blueBirdDriver.getConnectionFromDevLetter(devLetter);
                        if (connection < 0) {
                            LOG.error("Microbit print bytes: Bad connection number: {}", connection);
                            break;
                        }*/


                        //Process the string into chars. Convert any non alphabetic chars.
                        String inputStr = URLDecoder.decode(params[1], "ISO-8859-1");
                        char[] cArray = inputStr.toCharArray();

                        LOG.debug("Input Length = {}", cArray.length);

                        //startPrint(cArray, connection);
                        robotManager.startPrint(devLetter, cArray);

                    } catch (Exception e) {
                        LOG.error("HummingbirdServelet print Error: {}", e.toString());
                    }
                    break;
                case "symbol":
                    try {
                        devLetter = params[1].charAt(0);
                        /*connection = ScratchME.blueBirdDriver.getConnectionFromDevLetter(devLetter);
                        LOG.debug("symbol: DevLetter: {}, Connection: {}", devLetter, connection);
                        if (connection > -1) {*/

                            // kill the print thread (if it exists).
                            //killPrintThread();

                            //Set up patern based on params.
                        symbolCommand[0] = (byte) 0xCC;
                        symbolCommand[1] = (byte) 0x80;

                        symbolCommand[2] = params[26].equals("true") ? (byte) (symbolCommand[2] | (1 << 0)) : (byte) (symbolCommand[2] & ~(1 << 0)); //25

                        symbolCommand[3] = params[25].equals("true") ? (byte) (symbolCommand[3] | (1 << 7)) : (byte) (symbolCommand[3] & ~(1 << 7)); //24
                        symbolCommand[3] = params[24].equals("true") ? (byte) (symbolCommand[3] | (1 << 6)) : (byte) (symbolCommand[3] & ~(1 << 6)); //24
                        symbolCommand[3] = params[23].equals("true") ? (byte) (symbolCommand[3] | (1 << 5)) : (byte) (symbolCommand[3] & ~(1 << 5)); //24
                        symbolCommand[3] = params[22].equals("true") ? (byte) (symbolCommand[3] | (1 << 4)) : (byte) (symbolCommand[3] & ~(1 << 4)); //24
                        symbolCommand[3] = params[21].equals("true") ? (byte) (symbolCommand[3] | (1 << 3)) : (byte) (symbolCommand[3] & ~(1 << 3)); //24
                        symbolCommand[3] = params[20].equals("true") ? (byte) (symbolCommand[3] | (1 << 2)) : (byte) (symbolCommand[3] & ~(1 << 2)); //24
                        symbolCommand[3] = params[19].equals("true") ? (byte) (symbolCommand[3] | (1 << 1)) : (byte) (symbolCommand[3] & ~(1 << 1)); //24
                        symbolCommand[3] = params[18].equals("true") ? (byte) (symbolCommand[3] | (1 << 0)) : (byte) (symbolCommand[3] & ~(1 << 0)); //24

                        symbolCommand[4] = params[17].equals("true") ? (byte) (symbolCommand[4] | (1 << 7)) : (byte) (symbolCommand[4] & ~(1 << 7)); //24
                        symbolCommand[4] = params[16].equals("true") ? (byte) (symbolCommand[4] | (1 << 6)) : (byte) (symbolCommand[4] & ~(1 << 6)); //24
                        symbolCommand[4] = params[15].equals("true") ? (byte) (symbolCommand[4] | (1 << 5)) : (byte) (symbolCommand[4] & ~(1 << 5)); //24
                        symbolCommand[4] = params[14].equals("true") ? (byte) (symbolCommand[4] | (1 << 4)) : (byte) (symbolCommand[4] & ~(1 << 4)); //24
                        symbolCommand[4] = params[13].equals("true") ? (byte) (symbolCommand[4] | (1 << 3)) : (byte) (symbolCommand[4] & ~(1 << 3)); //24
                        symbolCommand[4] = params[12].equals("true") ? (byte) (symbolCommand[4] | (1 << 2)) : (byte) (symbolCommand[4] & ~(1 << 2)); //24
                        symbolCommand[4] = params[11].equals("true") ? (byte) (symbolCommand[4] | (1 << 1)) : (byte) (symbolCommand[4] & ~(1 << 1)); //24
                        symbolCommand[4] = params[10].equals("true") ? (byte) (symbolCommand[4] | (1 << 0)) : (byte) (symbolCommand[4] & ~(1 << 0)); //24


                        symbolCommand[5] = params[9].equals("true") ? (byte) (symbolCommand[5] | (1 << 7)) : (byte) (symbolCommand[5] & ~(1 << 7)); //24
                        symbolCommand[5] = params[8].equals("true") ? (byte) (symbolCommand[5] | (1 << 6)) : (byte) (symbolCommand[5] & ~(1 << 6)); //24
                        symbolCommand[5] = params[7].equals("true") ? (byte) (symbolCommand[5] | (1 << 5)) : (byte) (symbolCommand[5] & ~(1 << 5)); //24
                        symbolCommand[5] = params[6].equals("true") ? (byte) (symbolCommand[5] | (1 << 4)) : (byte) (symbolCommand[5] & ~(1 << 4)); //24
                        symbolCommand[5] = params[5].equals("true") ? (byte) (symbolCommand[5] | (1 << 3)) : (byte) (symbolCommand[5] & ~(1 << 3)); //24
                        symbolCommand[5] = params[4].equals("true") ? (byte) (symbolCommand[5] | (1 << 2)) : (byte) (symbolCommand[5] & ~(1 << 2)); //24
                        symbolCommand[5] = params[3].equals("true") ? (byte) (symbolCommand[5] | (1 << 1)) : (byte) (symbolCommand[5] & ~(1 << 1)); //24
                        symbolCommand[5] = params[2].equals("true") ? (byte) (symbolCommand[5] | (1 << 0)) : (byte) (symbolCommand[5] & ~(1 << 0)); //24


                            /*LOG.debug("Microbit Symbol bytes: {}", ScratchME.blueBirdDriver.bytesToString(symbolCommand));
                            ScratchME.blueBirdDriver.displayToHummingbird(connection, symbolCommand);  // Independent  command*/

                        robotManager.setSymbol(devLetter, symbolCommand);
                        //}

                    } catch (Exception e) {
                        LOG.error("HummingbirdServelet display Error: {}", e.toString());
                    }
                    break;

                case "stopall":
                    try {
                        if (params.length == 1) {  // Single device only
                            //devName = "BB";
                            //devNum = 1; //devId is always 1 for single device
                            devLetter = 'A';
                        } else if (params.length == 2) {
                            //deviceIdObj = getDevNameFromDropdown(params[3]);
                            //devName = deviceIdObj.name;
                            //devNum = (byte)(deviceIdObj.devNum);
                            devLetter = params[1].charAt(0);
                        } else {
                            LOG.error("HummingbirdServelet: stopall command bad URL");
                            out.print("404");
                            return;
                        }

                        /*connection = ScratchME.blueBirdDriver.getConnectionFromDevLetter(devLetter);
                        LOG.debug("Stop All: DevLetter: {}, Connection: {}", devLetter, connection);
                        if (connection > -1) {
                            killPrintThread(); // kill printThread if running
                            ScratchME.blueBirdDriver.sendStopAllCommand(connection);
                            killPrintThreadDelay(200);
                        }*/
                        robotManager.robotStopAll(devLetter);

                    } catch (Exception e) {
                        LOG.error("HummingbirdServelet stopAll Error: {}", e.toString());
                    }
                    break;

                case "resetEncoders":
                    devLetter = params[1].charAt(0);
                    //ScratchME.blueBirdDriver.sendResetEncodersCommand(devLetter);
                    robotManager.resetEncoders(devLetter);
                    break;
                case "turn":
                    devLetter = params[1].charAt(0);
                    String direction = params[2];
                    double angle = Double.parseDouble(params[3]);//Integer.parseInt(params[3]);
                    int speed = (int) Math.round(Double.parseDouble(params[4]));//Integer.parseInt(params[4]);

                    //int ticks = (int) Math.round(angle * ScratchME.blueBirdDriver.FINCH_CM_PER_DEGREE * ScratchME.blueBirdDriver.FINCH_TICKS_PER_CM);
                    int ticks = (int) Math.round(angle * RobotManager.FINCH_TICKS_PER_DEGREE);

                    if (ticks != 0) { //ticks=0 is the command for continuous motion
                        boolean shouldTurnRight = direction.equals("Right");
                        if (ticks < 0) {
                            shouldTurnRight = !shouldTurnRight;
                            ticks = Math.abs(ticks);
                        }
                        if (shouldTurnRight) {
                            //ScratchME.blueBirdDriver.updateMotors(devLetter, speed, ticks, -speed, ticks);
                            robotManager.updateMotors(devLetter, speed, ticks, -speed, ticks);
                        } else {
                            //ScratchME.blueBirdDriver.updateMotors(devLetter, -speed, ticks, speed, ticks);
                            robotManager.updateMotors(devLetter, -speed, ticks, speed, ticks);
                        }
                    }
                    break;
                case "move":
                    devLetter = params[1].charAt(0);
                    String dir = params[2];
                    double dist = Double.parseDouble(params[3]);//Integer.parseInt(params[3]);
                    int spd = (int) Math.round(Double.parseDouble(params[4]));//Integer.parseInt(params[4]);

                    int tks = (int) Math.round(dist * RobotManager.FINCH_TICKS_PER_CM);

                    if (dir.equals("Backward")) { spd = -spd; }
                    if (tks < 0) {
                        spd = -spd;
                        tks = Math.abs(tks);
                    }
                    LOG.debug("move {} {}", spd, tks);
                    if (tks != 0) { //tks=0 is the command for continuous motion
                        //ScratchME.blueBirdDriver.updateMotors(devLetter, spd, tks, spd, tks);
                        robotManager.updateMotors(devLetter, spd, tks, spd, tks);
                    }
                    break;
                case "wheels":
                    devLetter = params[1].charAt(0);
                    int left = (int) Math.round(Double.parseDouble(params[2]));//Integer.parseInt(params[2]);
                    int right = (int) Math.round(Double.parseDouble(params[3]));//Integer.parseInt(params[3]);

                    //ScratchME.blueBirdDriver.updateMotors(devLetter, left, 0, right, 0);
                    robotManager.updateMotors(devLetter, left, 0, right, 0);
                    break;
                case "stopFinch":
                    devLetter = params[1].charAt(0);
                    //ScratchME.blueBirdDriver.updateMotors(devLetter, 0, 0, 0, 0);
                    robotManager.updateMotors(devLetter, 0, 0, 0, 0);
                    break;
            }
            // No response to process. Return 200 anyway.
            out.print("200");
        } else {
            LOG.error("Invalid hummingbird block URL");
            out.print("404");
        }


    }

    public short bytes2short (int msb_index, int lsb_index, char devLetter) {
        Robot robot = robotManager.getConnectedRobot(devLetter, "Cannot get bytes2short value.");
        if (robot == null) {  return 0; }

        byte msb, lsb = 0;

        lsb = robot.getNotificationDataByte(lsb_index);
        msb = robot.getNotificationDataByte(msb_index);

        short value = (short)(lsb & 0x00FF);  //lsb
        value |= (msb << 8) & 0xFFFF;  //msb

        return value;
    }

    public float getGravity (double b) {
        LOG.debug("Finch Orientation Accel = {}" , b);
        float gravity = (float)(b * (2.0/127.0));
        BigDecimal bd = new BigDecimal(gravity);
        bd = bd.setScale(2, BigDecimal.ROUND_HALF_UP);  //Round to 2 decimal places
        LOG.debug("Orientation gravity = {}", bd.floatValue());
        return bd.floatValue();
    }
    public float getGravity (byte b) {
        LOG.debug("Orientation Accel Byte = {}" , Integer.toHexString(b));
        int i = (int)b;
        float gravity = (float)(i * (2.0/127.0));
        BigDecimal bd = new BigDecimal(gravity);
        bd = bd.setScale(2, BigDecimal.ROUND_HALF_UP);  //Round to 2 decimal places
        LOG.debug("Orientation gravity = {}", bd.floatValue());
        return bd.floatValue();
    }


    private String roundToString(double value) {
        BigDecimal bd = new BigDecimal(value);  //Scaling factor of 0.1
        bd = bd.setScale(2, BigDecimal.ROUND_HALF_UP);  //Round to 2 decimal places
        return Double.toString(bd.doubleValue());
    }

    /*
    These should be the equations to convert for the accelerometer:
    X-finch = x-micro:bit
    Y-finch = y-micro:bit*cos 40° - z-micro:bit*sin 40°
    Z-finch = y-micro:bit*sin 40° + z-micro:bit* cos 40°
    */
    public double getFinchAcceleration(String axis, char devLetter) {
        Robot robot = robotManager.getConnectedRobot(devLetter, "Cannot get acceleration value.");
        if (robot == null) { return 0; }

        switch (axis) {
            case "X":
                float x = robot.getNotificationDataByte(13);
                return x;
            case "Y":
            case "Z":
                float y = robot.getNotificationDataByte(14);
                float z = robot.getNotificationDataByte(15);

                switch (axis){
                    case "Y":
                        return (y * Math.cos(Math.toRadians(40)) - z * Math.sin(Math.toRadians(40)));
                    case "Z":
                        return (y * Math.sin(Math.toRadians(40)) + z * Math.cos(Math.toRadians(40)));
                }
                break;
            case "All":
                //TODO query all and return list
                LOG.error("Finch Accelerometer: axis 'All' is currently not implemented.");
                break;
            default:
                LOG.error("Finch Accelerometer: invalid axis {}", axis);
        }
        return 0;
    }
    /*
    These should be the equations to convert for the magnetometer:
    X-finch = x-micro:bit
    Y-finch = y-micro:bit*cos 40° + z-micro:bit*sin 40°
    Z-finch = z-micro:bit* cos 40° - y-micro:bit*sin 40°
    */
    public double getFinchMagnetometer(String axis, char devLetter) {
        Robot robot = robotManager.getConnectedRobot(devLetter, "Cannot get magnetometer value.");
        if (robot == null) { return 0; }

        switch (axis) {
            case "X":
                double x = robot.getNotificationDataByte(17);
                return x;
            case "Y":
            case "Z":
                double y = robot.getNotificationDataByte(18);
                double z = robot.getNotificationDataByte(19);

                switch (axis){
                    case "Y":
                        return (y * Math.cos(Math.toRadians(40)) + z * Math.sin(Math.toRadians(40)));
                    case "Z":
                        return (z * Math.cos(Math.toRadians(40)) - y * Math.sin(Math.toRadians(40)));
                }
                break;
            case "All":
                //TODO query all and return list
                LOG.error("Finch Accelerometer: axis 'All' is currently not implemented.");
                break;
            default:
                LOG.error("Finch Accelerometer: invalid axis {}", axis);
        }
        return 0;
    }



    public double rawToCompass(char devLetter, String devType, boolean finchReference) {
        Robot robot = robotManager.getConnectedRobot(devLetter, "Cannot get compass value.");
        if (robot == null) {
            LOG.error("Cannot get compass value. Device {} not connected.", devLetter);
            return 0;
        }
        short mx, my, mz;
        double ax, ay, az;

        if (finchReference) { //raw values moved to reference frame of finch
            mx = (short)Math.round(getFinchMagnetometer("X", devLetter));
            my = (short)Math.round(getFinchMagnetometer("Y", devLetter));
            mz = (short)Math.round(getFinchMagnetometer("Z", devLetter));
            ax = getFinchAcceleration("X", devLetter);
            ay = getFinchAcceleration("Y", devLetter);
            az = getFinchAcceleration("Z", devLetter);
        } else {
            int accXindex = 4;
            if (devType.equals("FN")) { //the finch returns values already converted to uT.
                accXindex = 13;
                mx = (short)(robot.getNotificationDataByte(17) * 10);
                my = (short)(robot.getNotificationDataByte(18) * 10);
                mz = (short)(robot.getNotificationDataByte(19) * 10);
            } else {
                mx = bytes2short (8, 9, devLetter);
                my = bytes2short (10, 11, devLetter);
                mz = bytes2short (12, 13, devLetter);
            }
            ax = robot.getNotificationDataByte(accXindex);
            ay = robot.getNotificationDataByte((accXindex+1));
            az = robot.getNotificationDataByte((accXindex+2));
        }


        LOG.debug("x:" + ax + "   y" + ay + "   z" + az);
        LOG.debug("mx:" + mx + " my" + my + "  mz" + mz);

        double phi = Math.atan(-ay / az);
        double theta = Math.atan(ax / (ay * Math.sin(phi) + az * Math.cos(phi)));

        double xp = mx;
        double yp = my * Math.cos(phi) - mz * Math.sin(phi);
        double zp = my * Math.sin(phi) + mz * Math.cos(phi);

        double xpp = xp * Math.cos(theta) + zp * Math.sin(theta);
        double ypp = yp;

        double angle = 180.0 + Math.toDegrees(Math.atan2(xpp, ypp));

        return angle;
    }


}
