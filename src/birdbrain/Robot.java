package birdbrain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.HashSet;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;

/**
 * This is class is the superclass of Microbit.java, Hummingbird.java and Finch.java.
 * It includes common methods to print on the micro:bit LED array or set those LEDs individually. It also
 * contains methods to read the values of the micro:bit accelerometer and magnetometer.
 * 
 * Mike Yuan and Bambi Breewer, BirdBrain Technologies LLC
 * November 2018
 */
public class Robot {
    private static String baseUrl = "http://127.0.0.1:30061/hummingbird/";
    
    protected String deviceInstance; // "A", "B", or "C"
    
    protected String connectionStatus = NO_CONNECTION;
    protected static final String NO_CONNECTION = "BlueBird Connector app communication not yet initialized";
    protected static final String BLUEBIRD_CONNECTOR_UNREACHABLE = "BlueBird Connector app not detected";
    protected static final String BLUEBIRD_CONNECTOR_INCOMPATIBLE = "BlueBird Connector app not compatible";
    protected static final String BLUEBIRD_CONNECTOR_FAILED = "BlueBird Connector app communication failure";
    protected static final String BLUEBIRD_CONNECTOR_OK = "BlueBird Connector app appears to be working";

    // constants used for orientation of the micro:bit
    private static final String SCREEN_UP = "Screen%20Up";
    private static final String SCREEN_DOWN = "Screen%20Down";
    private static final String TILT_LEFT = "Tilt%20Left";
    private static final String TILT_RIGHT = "Tilt%20Right";
    private static final String LOGO_UP = "Logo%20Up";
    private static final String LOGO_DOWN = "Logo%20Down";
    private static final String SHAKE = "Shake";

    // status of the 25 LEDs on the micro:bit
	protected String[] displayStatus = new String[25]; // "true" and "false" values

    // default keywords used in requests, subclasses can change these
	protected String magRequest = "Magnetometer";
	protected String accelRequest = "Accelerometer";
	protected String compassRequest = "Compass";


    /* Ensure contact with BlueBird Connector, else maybe launch it or print
     * help messages.
     * Then probe the desired robot to ensure it is connected, else print help
     * messages.
     * This returns on success, or exits the program on failure.
     */
    protected Robot(String model, String device) {
        // Sanity check parameters
        if (device != null && !device.equals("A") && !device.equals("B") && !device.equals("C")) {
            System.out.printf("Error: Could not connect to %s \"%s\", that name is not legal.\n", model, device);
            System.out.printf("When calling `new %s(...)`, instead use \"A\", \"B\", or \"C\" as the parameter to\n", model);
            System.out.printf("specify which robot device to connect to. Also make sure you are running the BlueBird Connector\n");
            System.out.printf("app and have connected via bluetooth to a %s device. Within that app you can\n", model);
            System.out.printf("connect up to three devices, which will be listed as device \"A\", \"B\", and \"C\".\n");
            throw new IllegalArgumentException(String.format("When calling `new %s(\"%s\")`, the argument \"%s\" is invalid. "
                        + "Make sure you are running the BlueBird Connector app and have connected some %s devices, then use "
                        + "\"A\", \"B\", or \"C\" to specify which device to connect to.", model, device, device, model));
        }
        // Establish communication
        connect(device);
        // Verify model of connected device
        if (!httpRequestInBoolean("in/is%s/static/%s", model, deviceInstance)) {
            System.out.printf("Error: Connected to device \"%s\", but it is not a %s device.\n", deviceInstance, model);
            System.out.printf("Within the BlueBird Connector app, ensure you connect to a %s\n", model);
            System.out.printf("device as \"%s\". Within that app you can connect up to three devices, which\n", deviceInstance);
            System.out.printf("will be listed as device \"A\", \"B\", and \"C\".\n");
            System.exit(1);
        }
    }

    private void connect(String device) {
        // First, establish communication with BlueBird Connector
        connectionStatus = NO_CONNECTION;
        String desiredResponse = "Not Connected";
        String msg = fetch("in/orientation/Shake/Z"); // should return "Not Connected"
        if (msg == null && connectionStatus == BLUEBIRD_CONNECTOR_UNREACHABLE) {
            if (BlueBirdLauncher.isAvailable()) {
                System.out.println("IMPORTANT: The BlueBird Connector program must be running");
                System.out.println("to enable communication with robots.");
                System.out.println("");
                System.out.println("Attempting to launch it for you... (press control-C to cancel)...");
                if (!BlueBirdLauncher.launch()) {
                    System.out.println("... Oops. Failed to launch the BlueBird Connector program.");
                    System.out.println("");
                    System.out.println("Please run it yourself, then use it to connect to a robot,");
                    System.out.println("before trying again.");
                    System.out.println("");
                    System.out.println("For example, in a separate terminal, try typing:");
                    System.out.println("    bluebirdconnector");
                    System.exit(1);
                } else {
                    msg = fetch("in/orientation/Shake/Z");
                    for (int i = 1; i < 10 && msg == null && connectionStatus == BLUEBIRD_CONNECTOR_UNREACHABLE; i++) {
                        System.out.print(".");
                        System.out.flush();
                        delay(1.0);
                        msg = fetch("in/orientation/Shake/Z");
                    }
                    if (msg == null && connectionStatus == BLUEBIRD_CONNECTOR_UNREACHABLE) {
                        System.out.println("... Something is wrong (Failed to detect the BlueBird");
                        System.out.println("");
                        System.out.println("Connector program). Please run the BlueBird Connector");
                        System.out.println("yourself, then use it to connect to a robot, before trying");
                        System.out.println("again.");
                        System.out.println("");
                        System.out.println("For example, in a separate terminal, try typing:");
                        System.out.println("    bluebirdconnector");
                        System.exit(1);
                    }
                    System.out.println("... Successfully launched BlueBird Connector.");
                }
            } else {
                System.out.println("IMPORTANT: The BlueBird Connector program must be running");
                System.out.println("to enable communication with robots.");
                System.out.println("");
                System.out.println("Please run the BlueBird Connector program, then use it to");
                System.out.println("connect to a robot, before trying again.");
                System.out.println("");
                System.out.println("For example, in a separate terminal, try typing:");
                System.out.println("    bluebirdconnector");
                System.exit(1);
            }
        }
        if (msg == null || !msg.equals(desiredResponse)) {
            System.out.println("Something is wrong with BlueBird Connector...");
            System.out.println("   ("+connectionStatus+", response="+msg+").");
            System.out.println("");
            System.out.println("Please quit and re-start BlueBird Connector, then use it to");
            System.out.println("connect to a robot, before trying again.");
            System.exit(1);
        }
        connectionStatus = BLUEBIRD_CONNECTOR_OK;

        // Next, probe the desired robot. If none was specified, try A, B, then C.
        if (device == null) {
            String[] choices = { "A", "B", "C" };
            for (String choice : choices) {
                msg = fetch("in/orientation/Shake/" + choice);
                if ("true".equals(msg) || "false".equals(msg)) {
                    System.out.println("Connected to robot " + choice + ".");
                    deviceInstance = choice;
                    return;
                }
                if (msg == null || !msg.equals("Not Connected")) {
                    System.out.println("Something is wrong with BlueBird Connector...");
                    System.out.println("   ("+connectionStatus+", response="+msg+").");
                    System.out.println("");
                    System.out.println("Please quit and re-start BlueBird Connector, then use it to");
                    System.out.println("connect to a robot, before trying again.");
                    System.exit(1);
                }
            }

        } else {
            msg = fetch("in/orientation/Shake/" + device);
            if ("true".equals(msg) || "false".equals(msg)) {
                deviceInstance = device;
                return;
            }
            if (msg == null || !msg.equals("Not Connected")) {
                System.out.println("Something is wrong with BlueBird Connector...");
                System.out.println("   ("+connectionStatus+", response="+msg+").");
                System.out.println("");
                System.out.println("Please quit and re-start BlueBird Connector, then use it to connect to a");
                System.out.println("robot, before trying again.");
                System.exit(1);
            }
        }
        
        // Let's wait a while to see if user connects to a robot (or the chosen robot).
        String choice;
        if (device == null) {
            System.out.println("IMPORTANT: You aren't currently connected to any robots.");
            System.out.println("  Within BlueBird Connector, scan for robots and select one.");
            choice = "A"; // only check for first robot, "A"
        } else {
            System.out.println("IMPORTANT: You aren't currently connected to robot " + device +".");
            System.out.println("  Within BlueBird Connector, scan for robots and select one as " + device + ".");
            choice = device;
        }
        System.out.println("");
        System.out.println("Waiting up to 30 seconds... (press control-C to cancel)...");
        for (int i = 0; i < 30; i++) {
            delay(1.0);
            System.out.print(".");
            System.out.flush();
            msg = fetch("in/orientation/Shake/" + choice);
            if ("true".equals(msg) || "false".equals(msg)) {
                System.out.println("Connected to robot " + choice + ".");
                deviceInstance = choice;
                return;
            }
            if (msg == null || !msg.equals("Not Connected")) {
                System.out.println(" error.");
                System.out.println("Something is wrong with BlueBird Connector...");
                System.out.println("   ("+connectionStatus+", response="+msg+").");
                System.out.println("");
                System.out.println("Please quit and re-start BlueBird Connector, then use it to");
                System.out.println("connect to a robot, before trying again.");
                System.exit(1);
            }
        }
        System.out.println(" timeout.");
        if (device == null) {
            System.out.println("");
            System.out.println("Connect to a robot within BlueBird Connector, then try");
            System.out.println("running this program again.");
        } else {
            System.out.println("");
            System.out.println("Connect to a robot (as robot " + device + ") within BlueBird");
            System.out.println("COnnector, then try running this program again.");
        }
        System.exit(1);
    }
    
    /* This function checks whether an input parameter is within the given bounds. If not, it prints
	   a warning and returns a value of the input parameter that is within the required range.
	   Otherwise, it just returns the initial value. */
    protected int clampParameterToBounds(int parameter, int inputMin, int inputMax, String func, String paramName) {
    	if ((parameter < inputMin) || (parameter > inputMax)) {
    		warn("When calling `%s(...)`, using %d for %s is invalid. It must be an int between %d and %d, inclusive.",
                    func, parameter, paramName, inputMin, inputMax);
    		return Math.max(inputMin, Math.min(inputMax,  parameter));
    	} else
    		return parameter;
    }
    
    /* This function checks whether an input parameter is within the given bounds. If not, it prints
	   a warning and returns a value of the input parameter that is within the required range.
	   Otherwise, it just returns the initial value. */
	protected double clampParameterToBounds(double parameter, double inputMin, double inputMax, String func, String paramName) {
	 	if ((parameter < inputMin) || (parameter > inputMax)) {
    		warn("When calling `%s(...)`, using %f for %s is invalid. It must be a double between %f and %f, inclusive.",
                    func, parameter, paramName, inputMin, inputMax);
	 		return Math.max(inputMin, Math.min(inputMax,  parameter));
	 	} else
	 		return parameter;
	}

    /**
     * Read data from URL. On failure returns null and sets connectionStatus.
     */
	protected String fetch(String fmt, Object... args) {
        String suffix = String.format(fmt, args);
        if (suffix.startsWith("/"))
            suffix = suffix.substring(1);
        String url = baseUrl + suffix;
        long requestStartTime = System.currentTimeMillis();
        HttpURLConnection connection = null;
        int responseCode;
        try {
            try {
                URI requestUrl = new URI(url);
                connection = (HttpURLConnection) requestUrl.toURL().openConnection();
                connection.setRequestMethod("GET");
                responseCode = connection.getResponseCode();
            } catch (Exception e) {
                // BlueBird Connector not reachable.
                connectionStatus = BLUEBIRD_CONNECTOR_UNREACHABLE;
                return null;
            }
            if (responseCode != 200) {
                warn("Received unexpected status code %d from %s", responseCode, url);
                connectionStatus = BLUEBIRD_CONNECTOR_INCOMPATIBLE;
                return null;
            }
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String line;
                StringBuffer buf = new StringBuffer();
                while ((line = in.readLine()) != null) {
                    buf.append(line);
                }
                in.close();
                return buf.toString();
            } catch (IOException e) {
                warn("Received unexpected error when accessing %s: %s", url, e.getMessage());
                connectionStatus = BLUEBIRD_CONNECTOR_FAILED;
                return null;
            }
        } finally {
            if (connection != null)
                connection.disconnect();

            // Rate limit: if macOS gets overwhelmed it will insert long pauses.
            while (System.currentTimeMillis() < requestStartTime + 5) {
                try { Thread.sleep(5); }
                catch (InterruptedException e) { }
            }

        }
    }

    /**
     * Send an http request without expecting any response data.
     */
	protected void httpRequestOut(String fmt, Object... args) {
        httpRequestInString(fmt, args);
    }

    /**
     * Send an http request and return the response data as a String.
     */
	protected String httpRequestInString(String fmt, Object... args) {
        String msg = fetch(fmt, args);
        if (msg == null) {
            System.out.println("ERROR: Lost connection to robot and/or BlueBird Connector");
            System.out.println("   ("+connectionStatus+").");
            System.exit(1);
        } else if (msg.equals("Not Connected")) {
            System.out.println("ERROR: Lost connection to robot.");
            System.exit(1);
        }
        // NOTE: BlueBird Connector sometimes returns "200" or "404" in error
        // cases, not as an http status code, but as the body of the response.
        // That's not great, but let's not try to catch those here. BlueBird
        // Connector should be fixed instead.
        return msg;
    }
    
    /**
     * Send an http request and return the response data as a double.
     */
    protected double httpRequestInDouble(String fmt, Object... args) {
        String stringResponse = httpRequestInString(fmt, args);
        try {
            return Double.parseDouble(stringResponse);
        } catch(Exception e) {
            warn("Expected a double value, but robot sent \"" + stringResponse + "\" instead.");
            return -1;
        }
    }
    
    /**
     * Send an http request and return the response data as a boolean.
     */
    protected boolean httpRequestInBoolean(String fmt, Object... args) {
        String stringResponse = httpRequestInString(fmt, args);
        if (stringResponse.equalsIgnoreCase("true")) {
            return true;
        } else if (stringResponse.equalsIgnoreCase("false")) {
            return false;
        } else {
            warn("Expected a true or false value, but robot sent \"" + stringResponse + "\" instead.");
            return false;
        }
    }
    
    /**
     * print() lets the LED Array display a given message.
     *
     * @param message The message that will be displayed on the LED Array.
     */
    public void print(String message) {
        // Warn the user if there are any unusual (e.g. non-ascii) characters.
        // The micro:bit can display ascii 32 up to ascii 126 just fine.
        // There may be a length limit we should probably enforce/warn for.
        for (int i = 0; i < message.length(); i++) {
            char letter = message.charAt(i);
            if ((int)letter < 32 || (int)letter > 126) {
                warn("Warning: The robot can't display '%c' (ascii char %d), it can only display plain ascii characters.", letter, (int)letter);
                message = new StringBuilder(message).replace(i, i + 1, " ").toString();
            }
        }

        Arrays.fill(displayStatus, "false");

        try {
            String encoded = URLEncoder.encode(message, "ISO-8859-1");
            httpRequestOut("out/print/%s/%s", encoded, deviceInstance);
        } catch (UnsupportedEncodingException e) {
            warn("Error encoding string for printing: %s", e.getMessage());
        }
    }

    /**
     * setDisplay lets the LED Array display a pattern based on an array of 1s and 0s.
     *
     * @param ledValues The list of integers that the function takes in to set the LED Array.
     *                1 means on and 0 means off.
     */
    public void setDisplay(int[] ledValues) {
        int ledLen = ledValues.length;
        
        if (ledLen != 25) {
        	warn("In `setDisplay(...)` you gave an array of %d ints. The array must have exactly 25 ints.", ledLen);
        	return;
        }

    	for (int i = 0; i < ledLen; i++) {
        	int value = clampParameterToBounds(ledValues[i], 0, 1, "setDisplay", "array value");
            displayStatus[i] = (value == 1 ? "true" : "false");
        }
    		
        httpRequestOut("out/symbol/%s/%s", deviceInstance, String.join("/", displayStatus));
    }
    
    /** This function turns on or off a single LED on the micro:bit LED array. 
     * 
     * @param row The row of the LED (1-5)
     * @param column The column of the LED (1-5)
     * @param value The value of the LED (0 for off, 1 for on)
     * */
    public void setPixel(int row, int column, int value) {
    	
    	row = clampParameterToBounds(row, 1, 5, "setPixel", "row number");
    	column = clampParameterToBounds(column, 1, 5, "setPixel", "column number");
    	value = clampParameterToBounds(value, 0, 1, "setPixel", "pixel value");
    		
		int position = (row - 1)*5 + (column - 1);

        displayStatus[position] = (value == 1 ? "true" : "false");
		
        httpRequestOut("out/symbol/%s/%s", deviceInstance, String.join("/", displayStatus));
    }

    /**
     * Set the buzzer to play the given note for the given duration
     * @param note - midi note number to play (Range: 32 to 135)
     * @param beats - duration in beats (Range: 0 to 16); each beat is one second
     */
    public void playNoteInBackground(int note, double beats) {
        note = clampParameterToBounds(note, 32, 135, "playNote", "note value");
        beats = clampParameterToBounds(beats, 0, 16, "playNote", "number of beats");
        httpRequestOut("out/playnote/%d/%d/%s", note, (int)(beats*1000), deviceInstance);
    }

    /** alternative to playNoteInBackground()
     * @param note - midi note number to play (Range: 32 to 135)
     * @param beats - duration in beats (Range: 0 to 16); each beat is one second
     */
    public void playNote(int note, double beats) {
        note = clampParameterToBounds(note, 32, 135, "playNote", "note value");
        beats = clampParameterToBounds(beats, 0, 16, "playNote", "number of beats");
        httpRequestOut("out/playnote/%d/%d/%s", note, (int)(beats*1000), deviceInstance);
        delay(beats);
    }

    /** alternative to playNote()
     * @param notes_and_beats the parameter
     */
    public void playSong(Object... notes_and_beats) {
        int len = notes_and_beats.length;
        if (len % 2 != 0) {
            warn("playSong(...) was given %d arguments, but it requires pairs of numbers, so you must provide an even numbrer of arguments.", len);
            return;
        }
        for (int i = 0; i < len; i++) {
            Object o = notes_and_beats[i];
            if (notes_and_beats[i] == null) {
                warn("playSong(...) was given null as parameter %d, it should be an integer instead.", i+1);
                return;
            }
            if (i % 2 == 0 && !(o instanceof Integer)) {
                warn("playSong(...) was given a %s as parameter %d, it should be an integer instead.", 
                        o.getClass(), i+1);
                return;
            }
            else if (i % 2 != 0 && !(o instanceof Integer || o instanceof Float || o instanceof Double)) {
                warn("playSong(...) was given a %s as parameter %d, it should be a double instead.", 
                        o.getClass(), i+1);
                return;
            }
        }
        for (int i = 0; i < len; i += 2) {
            int note = (int)notes_and_beats[i];
            double beats = ((Number)notes_and_beats[i+1]).doubleValue();
            playNote(note, beats);
        }
    }
   
    /**
     * getAccelerationInDirs returns acceleration value in a specified direction.
     *
     * @param dir The direction of which the acceleration will be returned.
     */
    private double getAccelerationInDirs(String dir) {
        return httpRequestInDouble("in/%s/%s/%s", accelRequest, dir, deviceInstance);     
    }

    /**
     * getMagnetometerValInDirs returns magnetometer value in a specified direction.
     *
     * @param dir The direction of which the magnetometer value will be returned.
     */
    private double getMagnetometerValInDirs(String dir) {
        return httpRequestInDouble("in/%s/%s/%s", magRequest, dir, deviceInstance);
    }

    /**
     * getAcceleration returns accelerations in 3 directions (X,Y,Z) in m/s^2.
     *
     * @return the accelerations in 3 directions (X,Y,Z) in m/s^2.
     */
    public double[] getAcceleration() {
        double[] accelerations = new double[3];
        double resX = getAccelerationInDirs("X");
        double resY = getAccelerationInDirs("Y");
        double resZ = getAccelerationInDirs("Z");
        accelerations[0] = resX;
        accelerations[1] = resY;
        accelerations[2] = resZ;
        return accelerations;
    }

    /**
     * getMagnetometer returns magnetometer values in 3 directions (X,Y,Z) in microT.
     *
     * @return the magnetometer values in 3 directions (X,Y,Z) in microT.
     */
    public int[] getMagnetometer() {
        int[] magnetometerVals = new int[3];
        double resX = getMagnetometerValInDirs("X");
        double resY = getMagnetometerValInDirs("Y");
        double resZ = getMagnetometerValInDirs("Z");
        magnetometerVals[0] = (int)Math.round(resX);
        magnetometerVals[1] = (int)Math.round(resY);
        magnetometerVals[2] = (int)Math.round(resZ);
        return magnetometerVals;
    }

    /**
     * getCompass returns the direction in degrees from north.
     *
     * @return the direction in degrees. (Range: 0-360)
     */
    public int getCompass() {
        return (int) httpRequestInDouble("in/%s/%s", compassRequest, deviceInstance);
    }

    /**
     * getButton() takes in a button and checks whether it is pressed.
     * The function shows a warning dialog if the inputs are not in the specified range.
     *
     * @param button the button that will be checked whether is it pressed or not. (Range: "A", "B")
     * @return true if the button is pressed and false otherwise.
     */
    public boolean getButton(String button) {
        button = button.toUpperCase();
        if (!(button.equals("A") || button.equals("B") || button.equals("LOGO"))) {
            warn("No such button named \"%s\". When calling `getButton(...)`, the argument must be \"A\", \"B\", or \"Logo\".", button);
            return false;
        }
        return httpRequestInBoolean("in/button/%s/%s", button, deviceInstance);
    }

    // /**
    //  * getButton() waits for one of the three buttons to be pressed, and returns
    //  * a string indicating which one was pressed.
    //  *playNote
    //  * @return Either "A", "B", or "Logo".
    //  */
    // public String waitForButton() {
    //     while (true) {
    //         if (getButton("A"))
    //             return "A";
    //         else if (getButton("B"))
    //             return "B";
    //         else if (getButton("Logo"))
    //             return "Logo";
    //         else
    //             delay(0.1);
    //     }
    // }

    /**
     * getSound() returns the current sound level from the micro:bit sound sensor
     * @return sound level
     */
    public int getSound() {
        return (int) Math.round(httpRequestInDouble("in/V2sensor/Sound/%s", deviceInstance));
    }

    /**
     * getTemperature() returns the current temperature in degrees Celcius from the micro:bit temperature sensor
     * @return temperature in degrees Celcius
     */
    public int getTemperature() {
        return (int) Math.round(httpRequestInDouble("in/V2sensor/Temperature/%s", deviceInstance));
    }

    /**
     * getOrientationBoolean checks whether the device currently being held to a specific orientation or shaken.
     *
     * @param orientation The orientation that will be checked.
     * @return "true" if the device is held to the orientation and false otherwise.
     */
    private boolean getOrientationBoolean(String orientation) {
        return httpRequestInBoolean("in/orientation/%s/%s", orientation, deviceInstance);  
    }

    /** isShaking() tells you whether the micro:bit is being shaken. 
     * 
     * @return a boolean value telling you the shake state
     * */
    public boolean isShaking() {
    	return getOrientationBoolean(SHAKE);
    }
    /**
     * getOrientation() provides information about the device's current orientation.
     *
     * @return the orientation of the device. (Range: Screen up, Screen down, Tilt left, Tilt right, Logo up, Logo down)
     */
    public String getOrientation() {
        boolean screenUp = getOrientationBoolean(SCREEN_UP);
        boolean screenDown = getOrientationBoolean(SCREEN_DOWN);
        boolean tiltLeft = getOrientationBoolean(TILT_LEFT);
        boolean tiltRight = getOrientationBoolean(TILT_RIGHT);
        boolean logoUp = getOrientationBoolean(LOGO_UP);
        boolean logoDown = getOrientationBoolean(LOGO_DOWN);
        
        if (screenUp) return "Screen up";
        else if (screenDown) return "Screen down";
        else if (tiltLeft) return "Tilt left";
        else if (tiltRight) return "Tilt right";
        else if (logoUp) return "Logo up";
        else if (logoDown) return "Logo down";
        return "In between";
    }

    /** Pauses the program for a time in seconds. */
    public static void delay(double numSeconds) {
    	double milliSeconds = 1000*numSeconds;
    	try {
            Thread.sleep(Math.round(milliSeconds));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
    /** Pauses the program for a time in seconds. */
    public static void carryOn(double numSeconds) { delay(numSeconds); }

    /** Pauses the program for a time in seconds. */
    public static void allowTime(double numSeconds) { delay(numSeconds); }
    
    /** stopAll() turns off all the outputs. */
    public void stopAll() {
    	delay(0.1);         // Give stopAll() time to act before the end of program
        httpRequestOut("out/stopall/%s", deviceInstance);
        Arrays.fill(displayStatus, "false");
    	delay(0.1);         // Give stopAll() time to act before the end of program
    }


    private static HashSet<String> alreadyWarned = new HashSet<>();
    protected static void warn(String fmt, Object... args) {
        String message = String.format(fmt, args);
        if (!alreadyWarned.contains(message)) {
            System.out.println("WARNING: " + message);
            alreadyWarned.add(message);
            delay(1.0);
        }
    }
}
