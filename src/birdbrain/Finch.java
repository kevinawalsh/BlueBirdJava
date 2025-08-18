package birdbrain;

/**
 * This class extends the Robot class to incorporate functions to control the inputs and outputs
 * of the Finch. It includes methods to set the values of motors and LEDs, as well
 * as methods to read the values of the sensors.
 *
 * Krissie Lauwers, BirdBrain Technologies LLC
 * October 2019
 */
public class Finch extends Robot {

    // String variables used to return the orientation of the finch
    private static final String BEAK_UP = "Beak%20Up";
    private static final String BEAK_DOWN = "Beak%20Down";
    private static final String TILT_LEFT = "Tilt%20Left";
    private static final String TILT_RIGHT = "Tilt%20Right";
    private static final String LEVEL = "Level";
    private static final String UPSIDE_DOWN = "Upside%20Down";

    /**
     * Default constructor for the library. Set the default device to be A.
     */
    public Finch() {
        this(null);
    }

    /**
     * General constructor for the library. Set the device to be "A", "B", or "C".
     *
     * @param device The letter corresponding to the Hummingbird device, which much be "A", "B", or "C".
     * The letter that identifies the Hummingbird device is assigned by the BlueBird Connector.
     */
    public Finch(String device) {
        super("Finch", device);
        // The finch has separate requests for these so that the results can be
        // adjusted to match the finch reference frame, which is angled slightly
        // due to the angled mounting bracket on the robot's tail.
        magRequest = "finchMag";
        accelRequest = "finchAccel";
        compassRequest = "finchCompass/static";
    }

    /**
     * Formats the direction string for sending to the bluebird connector.
     * If the selection made is not acceptable, returns 'Neither'.
     * @param direction
     * @return
     */
    private String formatForwardBackward(String direction) {
        switch (direction) {
            case "F":
            case "f":
            case "Forward":
            case "forward":
                return "Forward";
            case "B":
            case "b":
            case "Backward":
            case "backward":
                return "Backward";
            default:
                return null;
        }
    }

    private String formatRightLeft(String direction, String method) {
        switch (direction) {
            case "R":
            case "r":
            case "Right":
            case "right":
                return "Right";
            case "L":
            case "l":
            case "Left":
            case "left":
                return "Left";
            default:
                warn("When calling `%s(...)`, using \"%s\" for direction is invalid. It must be \"L\", \"R\", \"Left\", or \"Right\".", method, direction);
                return null;
        }
    }

    /**
     * Send a command to move the finch and wait until the finch has finished
     * its motion to return. Used by setMove and setTurn.
     * @param motion - Move or turn
     * @param direction - forward, backward, right or left
     * @param length - Length of travel (distance or angle)
     * @param speed - Speed as a percent (Range: 0 to 100)
     */
    private void moveFinchAndWait(String motion, String direction, double length, double speed){
        boolean isMoving = httpRequestInBoolean("in/finchIsMoving/static/%s", deviceInstance);
        boolean wasMoving = isMoving;
        long commandSendTime = System.currentTimeMillis();

        httpRequestOut("out/%s/%s/%s/%s/%s", motion, deviceInstance, direction, length, speed);

        while (!((System.currentTimeMillis() > commandSendTime + 500 || wasMoving) && !isMoving)) {
            wasMoving = isMoving;
            pause(0.01); // 10ms
            isMoving = httpRequestInBoolean("in/finchIsMoving/static/%s", deviceInstance);
        }
    }

    /**
     * Sends a request for the finch to move forward or backward a given distance
     * at a given speed. Direction should be specified as "F" or "B".
     * @param direction - F or B for forward or backward
     * @param distance - Distance to travel in cm. (Range: 0 to 500)
     * @param speed - Speed as a percent (Range: 0 to 100)
     */
    public void setMove(String direction, double distance, double speed) {
        String dir = formatForwardBackward(direction);
        if (dir == null) {
            warn("When calling `setMove(...)`, using \"%s\" for direction is invalid. It must be \"F\", \"B\", \"Forward\", or \"Backward\".", direction);
            return;
        }

        distance = clampParameterToBounds(distance, -10000, 10000, "setMove", "distance");
        speed = clampParameterToBounds(speed, 0, 100, "setMove", "speed");

        moveFinchAndWait("move", dir, distance, speed);
    }

    /**
     * Sends a request for the finch to turn right or left to the give angle
     * at the given speed.
     * @param direction - R or L for right or left
     * @param angle - Angle of the turn in degrees (Range: 0 to 360)
     * @param speed - Speed of the turn as a percent (Range: 0 to 100)
     */
    public void setTurn(String direction, double angle, double speed) {
        direction = formatRightLeft(direction, "setTurn");
        if (direction == null)
            return;

        angle = clampParameterToBounds(angle, -360000, 360000, "setTurn", "angle");
        speed = clampParameterToBounds(speed, 0, 100, "setTurn", "speed");

        moveFinchAndWait("turn", direction, angle, speed);
    }

    /**
     * Set the right and left motors of the finch to the speeds given.
     * @param leftSpeed - Speed as a percent (Range: 0 to 100)
     * @param rightSpeed - Speed as a percent (Range: 0 to 100)
     */
    public void setMotors(double leftSpeed, double rightSpeed) {
        leftSpeed = clampParameterToBounds(leftSpeed, -100, 100, "setMotors", "leftSpeed");
        rightSpeed = clampParameterToBounds(rightSpeed, -100, 100, "setMotors", "rightSpeed");
        httpRequestOut("out/wheels/%s/%s/%s", deviceInstance, leftSpeed, rightSpeed);
    }

    /**
     * Stop the finch motors
     */
    public void stop() {
        httpRequestOut("out/stopFinch/%s", deviceInstance);
    }

    private void setTriLED(String port, int red, int green, int blue, String method) {
        red = clampParameterToBounds(red,0,100, method, "red");
        green = clampParameterToBounds(green,0,100, method, "green");
        blue = clampParameterToBounds(blue,0,100, method, "blue");

        // Scale
        red = (int) (red * 255.0 / 100.0);
        green = (int) (green * 255.0 / 100.0);
        blue = (int) (blue * 255.0 / 100.0);

        httpRequestOut("out/triled/%s/%d/%d/%d/%s", port, red, green, blue, deviceInstance);
    }

    /**
     * Set the finch beak to the given rgb color.
     * @param red - red intensity (Range: 0 to 100)
     * @param green - green intensity (Range: 0 to 100)
     * @param blue - blue intensity (Range: 0 to 100)
     */
    public void setBeak(int red, int green, int blue) {
        setTriLED("1", red, green, blue, "setBeak");
    }

    /**
     * Set the specified tail led to the specified rgb color.
     * @param ledNum - led to set (Range: 1 to 4)
     * @param red - red intensity (Range: 0 to 100)
     * @param green - green intensity (Range: 0 to 100)
     * @param blue - blue intensity (Range: 0 to 100)
     */
    public void setTail(int ledNum, int red, int green, int blue) {
        ledNum = clampParameterToBounds(ledNum, 1, 4, "setTail", "LED number");
        setTriLED(String.valueOf(ledNum + 1), red, green, blue, "setTail");
    }

    /**
     * Set all tail leds to the specified rgb color.
     * @param ledNum - String which must be specified as 'all'
     * @param red - red intensity (Range: 0 to 100)
     * @param green - green intensity (Range: 0 to 100)
     * @param blue - blue intensity (Range: 0 to 100)
     */
    public void setTail(String ledNum, int red, int green, int blue) {
        if (!ledNum.equals("all") && !ledNum.equals("All") && !ledNum.equals("ALL")) {
            warn("When calling `setTail(...)` with a string as the first argument, the string must be \"all\". No other values are permitted.");
            return;
        }
        setTriLED("all", red, green, blue, "setTail");
    }

    /**
     * Reset the finch encoder values to 0.
     */
    public void resetEncoders() {
        httpRequestOut("out/resetEncoders/%s", deviceInstance);
        pause(0.2); // Give the finch a chance to reset before moving on
    }

    /**
     * Private function to get the value of a sensor
     * @param sensor - Light, Distance, Line, or Encoder
     * @param direction - Right, Left, or static
     * @return - sensor value returned by bluebird connector or -1 in the case of a problem.
     */
    private double getSensor(String sensor, boolean isStatic, String direction) {
        String port;
        if (isStatic) {
            port = "static";
        } else {
            port = formatRightLeft(direction, "get"+sensor);
            if (port == null)
                return -1;
        }
        return httpRequestInDouble("in/%s/%s/%s", sensor, port, deviceInstance);
    }

    /**
     * Get the current value of the right or left encoder
     * @param direction - R or L to specify right or left
     * @return - encoder value in rotations
     */
    public double getEncoder(String direction) {
        double value = getSensor("Encoder", false, direction);
        value = Math.round(value * 100.0)/100.0;
        return value;
    }

    /**
     * Get the current value of the finch distance sensor
     * @return - the distance to the closest obstacle in cm
     */
    public int getDistance() {
        return (int) Math.round(getSensor("Distance", true, null));
    }

    /**
     * Get the current value of the specified finch light sensor
     * @param direction - R or L to specify right or left
     * @return - brightness as a value 0-100
     */
    public int getLight(String direction) {
        return (int) Math.round(getSensor("Light", false, direction));
    }

    /**
     * Get the current value of the specified finch line sensor.
     * Return value is inverted (100 - value) so that more reflected
     * light = bigger number
     * @param direction - R or L to specify right or left
     * @return - brightness as a value 0-100
     */
    public int getLine(String direction) {
        return (int) getSensor("Line", false, direction);
    }

    private boolean getOrientationBoolean(String orientation) {
        return httpRequestInBoolean("in/finchOrientation/%s/%s", orientation, deviceInstance);
    }

    /**
     * getOrientation() provides information about the finch's current orientation.
     * This function overrides the function in the Robot class so that results are
     * in the finch reference frame.
     *
     * @return the orientation of the finch. (Range: Beak up, Beak down, Tilt left, Tilt right, Level, Upside down)
     */
    public String getOrientation() {
        boolean beakUp = getOrientationBoolean(BEAK_UP);
        boolean beakDown = getOrientationBoolean(BEAK_DOWN);
        boolean tiltLeft = getOrientationBoolean(TILT_LEFT);
        boolean tiltRight = getOrientationBoolean(TILT_RIGHT);
        boolean level = getOrientationBoolean(LEVEL);
        boolean upsideDown = getOrientationBoolean(UPSIDE_DOWN);

        if (beakUp) return "Beak up";
        else if (beakDown) return "Beak down";
        else if (tiltLeft) return "Tilt left";
        else if (tiltRight) return "Tilt right";
        else if (level) return "Level";
        else if (upsideDown) return "Upside down";
        return "In between";
    }
}
