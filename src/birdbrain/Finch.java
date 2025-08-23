package birdbrain;

/**
 * This class provides mothods to control and query a Finch robot. It includes
 * methods to set the values of motors and LEDs, print strings to the 25-LED
 * panel, and to read the values of all sensors.
 *
 * @author Krissie Lauwers, BirdBrain Technologies LLC, October 2019
 * @author Kevin Walsh, kwalsh@holycross.edu, August 2025
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
     * Constructor to initialize a Finch object, without specifying any name.
     * This will try to control robot "A", "B", or "C" in turn, until one of
     * them succeeds. Use this if you only have one robot connected, or you
     * don't care which one you control.
     * <b>NOTE:</b> You must first run the <b>BlueBird Connector</b> program,
     * and use that program to scan for bluetooth devices. That program can
     * connect to up to three robots simultaneously, which will be labeled "A,
     * "B", and "C". Normally you'll just connect to one robot, and it will be
     * "A".
     *
     * <p><b>Example:</b>
     * <pre>
     *    Finch bot = new Finch();     // Connect to some robot.
     *    bot.straight("F", 10, 50.0); // Move it forward 10 cm distance at 50% speed.
     *    bot.setBeak(0, 75, 50);      // Set its beak to 0% red, 75% green, 50% blue.
     * </pre>
     */
    public Finch() {
        this(null);
    }

    /**
     * Constructor to initialize a Finch, by name.
     * <b>NOTE:</b> You must first run the <b>BlueBird Connector</b> program,
     * and use that program to scan for bluetooth devices. That program can
     * connect to up to three robots simultaneously, which will be labeled "A,
     * "B", and "C". Normally you'll just connect to one robot, and it will be
     * "A". But if you want to control multiple robots, this constructor will
     * let you specify which one each Finch object controls.
     *
     * @param device  A device letter ("A", "B", or "C") to indicate which robot
     * to control. The available device letters can be seen within the
     * <b>BlueBird Connector</b> program after you have scanned for bluetooth
     * devices and connected to some robots.
     * 
     * <p><b>Example:</b>
     * <pre>
     *    Finch bot1 = new Finch("A");  // Connect to robot "A".
     *    Finch bot2 = new Finch("C");  // Connect to robot "C".
     *    bot1.straight("F", 10, 50.0); // Move A forward 10 cm distance at 50% speed.
     *    bot2.setBeak(0, 75, 50);      // Set B's beak to 0% red, 75% green, 50% blue.
     * </pre>
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
     * its motion to return. Used by straight(), spin(), and curve().
     * @param motion  Move or turn
     * @param direction forward, backward, right or left
     * @param length  Length of travel (distance or angle)
     * @param speed  Speed as a percent (range: 0 to 100)
     */
    private void moveFinchAndWait(String motion, String direction, double length, double speed){
        boolean isMoving = httpRequestInBoolean("in/finchIsMoving/static/%s", deviceInstance);
        boolean wasMoving = isMoving;
        long commandSendTime = System.currentTimeMillis();

        httpRequestOut("out/%s/%s/%s/%s/%s", motion, deviceInstance, direction, length, speed);

        while (!((System.currentTimeMillis() > commandSendTime + 500 || wasMoving) && !isMoving)) {
            wasMoving = isMoving;
            delay(0.01); // 10ms
            isMoving = httpRequestInBoolean("in/finchIsMoving/static/%s", deviceInstance);
        }
    }

    /**
     * Move the finch forward or backwards a specified distance and speed. This
     * <b>pauses your program</b> until the finch has finished moving the
     * desired distance. <b>Note:</b> The finch can't always measure distances
     * perfectly, it can be affected if the floor is slippery, or bumpy, etc.
     * 
     * <p><b>Example:</b>
     * <pre>
     *    Finch bot = new Finch("A");
     *    bot.straight("F", 10, 50.0);  // First, move forward 10 cm distance at 50% speed.
     *    bot.straight("B", 5.5, 33.3); // Then, move backwards 5.5 cm distance at 33.3% speed.
     * </pre>
     *
     * @param direction  Use "F" or "B" for forward or backward.
     * @param distance  Distance to travel, in centimeters (range: 0 to 500).
     * @param speed  Speed, as a percentage (range: 0 to 100).
     */
    public void straight(String direction, double distance, double speed) {
        String dir = formatForwardBackward(direction);
        if (dir == null) {
            warn("When calling `straight(...)`, using \"%s\" for direction is invalid. It must be \"F\", \"B\", \"Forward\", or \"Backward\".", direction);
            return;
        }

        distance = clampParameterToBounds(distance, -10000, 10000, "straight", "distance");
        speed = clampParameterToBounds(speed, 0, 100, "straight", "speed");

        moveFinchAndWait("move", dir, distance, speed);
    }

    /**
     * Turn the finch right or left, rotating in place by the specified angle
     * and speed. This <b>pauses your program</b> until the finch has finished
     * turning the desired angle. <b>Note:</b> The finch can't always measure
     * angles perfectly, it can be affected if the floor is slippery, or bumpy,
     * etc. Uusually you should use positive numbers for the angle. Using a
     * negative number causes finch to spin in the opposite direction you
     * specified.
     *
     * <p><b>Example:</b>
     * <pre>
     *    Finch bot = new Finch("A");
     *    bot.spin("R", 90, 100);  // First, spin right (clockwise) 90 degrees, at 100% speed.
     *    bot.spin("L", 45, 25.0); // Then, spin left (counter-clockwise) 45 degrees, at 25% speed.
     *    bot.spin("L", -45, 25.0); // Then, surprise! It spins right, becuse negative angle means opposite direction.
     * </pre>
     *
     * @param direction  Use "R" or "L" for right (clockwise) or left (counter-clockwise).
     * @param angle  Angle to turn, in degrees, where negatives go in the reverse direction (range: -360000 to 360000 ... up to ten full rotations, though Finch might get a bit dizzy).
     * @param speed  Speed, as a percentage (range: 0 to 100).
     */
    public void spin(String direction, double angle, double speed) {
        direction = formatRightLeft(direction, "spin");
        if (direction == null)
            return;

        angle = clampParameterToBounds(angle, -360000, 360000, "spin", "angle");
        speed = clampParameterToBounds(speed, 0, 100, "spin", "speed");

        moveFinchAndWait("turn", direction, angle, speed);
    }

    /**
     * Drive the finch by specifying wheel speeds directly. Using different
     * combinations of positive, negative, or zero values, you can make the
     * finch move straight, forwards or backwards, or turn in place. You can
     * even make it curve in an arc or circle, by having the wheels go at
     * different speeds.
     * Unlike {@link birdbrain.Finch#straight(String direction, double distance, double speed) straight(...)},
     * and {@link birdbrain.Finch#spin(String direction, double angle, double speed) spin(...)},
     * this function does <b>not</b> pause your program. Instead, your program
     * will continue executing, allowing you to command the finch to do other
     * things while it moves. 
     *
     * <p><b>Note:</b> The robot will continue moving indefinitely. Your code
     * must call {@link birdbrain.Finch#stop() stop()},
     * {@link birdbrain.Finch#stopAll() stopAll()}, or some other motion
     * command, at some later point in the program. Otherwise the robot will
     * keep driving forever, even after your program exits.
     *
     * <p><b>Example:</b>
     * <pre>
     *    Finch bot = new Finch("A");
     *    bot.setMotors(25, 25);    // Straight forward, both wheels at 25% speed.
     *    bot.allowTime(1.5);       // Delay for 1.5 seconds... the robot keeps driving forward.
     *    bot.setBeak(100, 0, 0);   // Change the beak lights... the robot is still driving forward.
     *    bot.allowTime(1.5);       // Delay for 1.5 seconds... the robot keeps driving forward.
     *    bot.setMotors(-100, 10);  // Move in a backwards curve, left wheel full reverse, right wheel 10% forward.
     *    bot.allowTime(1.5);       // Delay for 1.5 seconds... the robot keeps curving back.
     *    bot.stop();               // Stop moving.
     * </pre>
     *
     * @param leftSpeed  Speed for the left wheel, as a percentage (range: -100 to 100).
     * @param rightSpeed  Speed for the right wheel, as a percentage (range: -100 to 100).
     */
    public void setMotors(double leftSpeed, double rightSpeed) {
        leftSpeed = clampParameterToBounds(leftSpeed, -100, 100, "setMotors", "leftSpeed");
        rightSpeed = clampParameterToBounds(rightSpeed, -100, 100, "setMotors", "rightSpeed");
        httpRequestOut("out/wheels/%s/%s/%s", deviceInstance, leftSpeed, rightSpeed);
    }

    /**
     * Stop the finch wheel motors. This can be useful to stop the robot after
     * calling {@link birdbrain.Finch#setMotors(double leftSpeed, double rightSpeed) setMotors(...)} at some earlier
     * point in your program.
     * <p><b>Example:</b>
     * <pre>
     *    Finch bot = new Finch("A");
     *    bot.setMotors(25, 25);    // Starts moving.
     *    ...                       // ... delay or do other things while moving ...
     *    bot.stop();               // Stop moving.
     * </pre>
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
     * Change the finch's beak light color. You can achieve most any color,
     * using combinations of red, green, and blue. 
     * Use all zeros to turn the beak light off, or all 100's to make the beak
     * light bright white.
     *
     * <p><b>Example:</b>
     * <pre>
     *    Finch bot = new Finch("A");
     *    bot.setBeak(100, 100, 100);  // Bright white.
     *    bot.allowTime(1.0);          // Delay 1 second.
     *    bot.setBeak(0, 100, 0);      // Then bright green.
     *    bot.allowTime(1.0);          // Delay 1 second.
     *    bot.setBeak(75, 0, 75);      // Then medium violet (75% red, 0% green, 75% blue).
     *    bot.allowTime(1.0);          // Delay 1 second.
     *    bot.setBeak(0, 0, 0);        // Then turn the beak light off.
     * </pre>
     *
     * @param red  Red intensity, as a percentage (range: 0 to 100).
     * @param green  Green intensity, as a percentage (range: 0 to 100).
     * @param blue  Blue intensity, as a percentage (range: 0 to 100).
     */
    public void setBeak(int red, int green, int blue) {
        setTriLED("1", red, green, blue, "setBeak");
    }

    /**
     * Change one of the finch's tail light colors. There are four lights on the
     * tail, numbered 1 to 4 from left to right, and you can achieve most any
     * color for each one using combinations of red, green, and blue. Use all
     * zeros to a light off, or all 100's to make the light bright
     * white.
     * See also {@link birdbrain.Finch#setTail(String ledChoice, int red, int green, int blue) setTail(String, ...)}
     * which lets you change all four lights with one line of code.
     *
     * <p><b>Example:</b>
     * <pre>
     *    Finch bot = new Finch("A");
     *    bot.setTail(1, 100, 100, 100);  // Make tail light #1 full bright white.
     *    bot.setTail(4, 50, 0, 0);       // Make tail light #4 red, at 50% brightness.
     *    bot.setTail(3, 30, 30, 30);     // Make tail light #3 dull white, 30% brightness.
     * </pre>
     *
     * @param ledChoice  choice of led to set (range: 1 to 4).
     * @param red  red intensity, as a percentage (range: 0 to 100).
     * @param green  green intensity, as a percentage (range: 0 to 100).
     * @param blue  blue intensity, as a percentage (range: 0 to 100).
     */
    public void setTail(int ledChoice, int red, int green, int blue) {
        ledChoice = clampParameterToBounds(ledChoice, 1, 4, "setTail", "LED number");
        setTriLED(String.valueOf(ledChoice + 1), red, green, blue, "setTail");
    }

    /**
     * Change all of the finch's tail lights to some color. You can achieve most
     * any color using combinations of red, green, and blue.
     * Use all zeros to turn the lights off, or all 100's to make the lights
     * bright white.
     * See also {@link birdbrain.Finch#setTail(int ledChoice, int red, int green, int blue) setTail(int, ...)}
     * which lets you change the four tail lights individually, using different
     * colors for each.
     *
     * <p><b>Example:</b>
     * <pre>
     *    Finch bot = new Finch("A");
     *    bot.setTail("all", 100, 100, 100);  // Make all tail lights full bright white.
     *    bot.delay(1.0);
     *    bot.setTail("all", 50, 50, 0);      // Make all tail lights dull yellow (50% red, 50% green, 0% blue).
     *    bot.delay(1.0);
     *    bot.setTail("all", 0, 0, 0);        // Turn off all tail lights.
     * </pre>
     *
     * @param ledChoice  String which must be specified as "all".
     * @param red  red intensity, as a percentage (range: 0 to 100).
     * @param green  green intensity, as a percentage (range: 0 to 100).
     * @param blue  blue intensity, as a percentage (range: 0 to 100).
     */
    public void setTail(String ledChoice, int red, int green, int blue) {
        if (ledChoice == null) {
            warn("When calling `setTail(...)` the first parameter can't be null");
            return;
        }
        // undocumented feature: allow "1", "2", "3", and "4"
        if (ledChoice.equals("1")) {
            setTriLED("2", red, green, blue, "setTail");
        } else if (ledChoice.equals("2")) {
            setTriLED("3", red, green, blue, "setTail");
        } else if (ledChoice.equals("3")) {
            setTriLED("4", red, green, blue, "setTail");
        } else if (ledChoice.equals("4")) {
            setTriLED("5", red, green, blue, "setTail");
        } else if (!ledChoice.equals("all") && !ledChoice.equals("All") && !ledChoice.equals("ALL")) {
            warn("When calling `setTail(...)` with a string as the first argument, the string must be \"all\". No other values are permitted.");
        } else {
            setTriLED("all", red, green, blue, "setTail");
        }
    }

    /**
     * Reset the finch's wheel encoder values to zero. In combination with
     * {@link birdbrain.Finch#getEncoder(String direction) getEncoder(...)},
     * this function can be useful to keep track of distances traveled or angles
     * turned.
     *
     * <p>Each finch wheel has an encoder, which works like a car's odometer,
     * keeping track of how far each wheel has moved. This method resets the
     * values back to zero, just like pressing the "reset trip" button next to
     * car's odometer. After reset, the encoder values will count upwards
     * whenever the wheels are moving forward. If the robot moves backwards, the
     * encoder values will count downwards, potentially becoming negative (this
     * is different from a car odometer, which only counts up). You can get the
     * current odometer reading for each wheel by calling
     * {@link birdbrain.Finch#getEncoder(String direction) getEncoder(...)}.
     *
     * <p><b>Example:</b>
     * <pre>
     *    Finch bot = new Finch("A");
     *    bot.resetEncoders();          // Reset the odometers
     *    bot.setMotors(50, 50);        // Move straight forward at 50% speed.
     *    while (getLight("L") &gt; 30) {  // keep checking the left side light sensor...
     *       bot.delay(1.0);            // ... and delay while it is above 30%
     *    }
     *    bot.stop();                   // Stop moving once we reach a nice shady spot.
     *    int d = bot.getEncoder("L");  // Get the encoder value for the left wheel.
     *    System.out.println("Wheels moved " + d + " rotations!");
     * </pre>
     */
    public void resetEncoders() {
        httpRequestOut("out/resetEncoders/%s", deviceInstance);
        delay(0.2); // Give the finch a chance to reset before moving on
    }

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
     * Get the current value of the right or left wheel encoder.
     *
     * <p>Each finch wheel has an encoder, which works like a car's odometer,
     * keeping track of how far each wheel has moved. This method gets the
     * current value, just like peeking at a car's odometer to see how many
     * miles it has gone. The encoder values will count upwards whenever the
     * wheels are moving forward, in units of "wheel rotations". If the robot
     * moves backwards, the encoder values will count downwards, potentially
     * becoming negative (this is different from a car odometer, which only
     * counts up). If you call this function once, then call it again later, and
     * subtract the two resulting values, you can learn how far the finch wheel
     * went during that interval. Or, call
     * {@link birdbrain.Finch#resetEncoders() resetEncoders()} to reset the
     * values to zero before you begin some movement, then call {@code getEncoder(...)}
     * to see how far you have moved since the reset.
     *
     * <p><b>Note:</b> Finch wheels have diameter of 5cm, so each rotation
     * corresponds to about {@code 5 * PI = 15.94} centimeters of distance. In
     * other words, {@code distance = rotations * 15.94} and vice-versa,
     * {@code rotations = distance / 15.94} approximately.
     *
     * <p><b>Example:</b>
     * <pre>
     *    Finch bot = new Finch("A");
     *    int x = bot.getEncoder("R");  // Get the right wheel encoder value.
     *    ...
     *    ...                           // Do movements or other things here...
     *    ...
     *    int y = bot.getEncoder("R");  // Get the right wheel encoder value again.
     *    int total = (y - x) * 15.94;  // Calculate total distance moved so far.
     *    System.out.println("We moved " + total + " centimeters so far!");
     * </pre>
     *
     * @param direction  Use "R" or "L" for right or left wheel.
     * @return  encoder value in rotations
     */
    public double getEncoder(String direction) {
        double value = getSensor("Encoder", false, direction);
        value = Math.round(value * 100.0)/100.0;
        return value;
    }

    /**
     * Get the current value of the finch ultrasonic distance sensor. This
     * function is useful for checking if the finch is about to run into a wall
     * or other obstacle in front of the robot. It uses a pair of ultrasound
     * transmitters and sensors located just below the beak of the finch, like a
     * bat uses sonar, attempting measure the approximate distance to the
     * nearest obstacle in front of the robot. 
     *
     * <b>Note:</b> This function has nothing to do with "distance travelled" or
     * the wheel encoders, but instead is used to check for obstacles in front
     * of the robot. Also, tthe sensors used for this are not very accurate,
     * they give only a rough approximation of distance, and they are only able
     * to see a short distance ahead, about one meter or so at best.
     *
     * <p><b>Example:</b>
     * <pre>
     *    Finch bot = new Finch("A");
     *    bot.setMotors(30, 30);          // Start driving forwards, 30% speed.
     *    while (true) {
     *      int d = bot.getDistance();    // Check the sonar!
     *      System.out.println("Distance to impact: " + d + " centimeters!");
     *      if (d &lt; 10) {
     *        bot.stop();
     *        System.out.println("boop!");
     *        System.exit(0);
     *      }
     *   }
     * </pre>
     *
     * @return  the approximate distance to the closest obstacle in front of the
     * robot, in centimeters.
     */
    public int getDistance() {
        return (int) Math.round(getSensor("Distance", true, null));
    }

    /**
     * Get the current value of one of the finch's light sensors. There are two
     * light sensors, located roughly where you'd expect the eyes to be, just
     * behind the beak, one to each side. These are focused upwards, so they are
     * useful for checking if the finch is sitting below a bright light or is
     * currently in shadow. <b>Note:</b> These sensors are not very accurate,
     * they only give a rough estimate of the brightness of the light, where
     * lower numbers mean "dark" and higher numbers mean "bright".
     *
     * <p><b>Example:</b>
     * <pre>
     *    Finch bot = new Finch("A");
     *    while (true) {
     *      int x = bot.getLight("L");    // Check left eye brightness.
     *      int y = bot.getLight("R");    // Check right eye brightness.
     *      if (x &gt; y)                    // If left side is brighter...
     *        bot.spin("L", 5, 100);      // ... then turn 5 degrees left
     *      else
     *        bot.spin("R", 5, 100);      // ... otherwise turn 5 degrees right
     *      bot.straight("F", 5, 100);    // Move forward a few centimeters.
     *   }
     * </pre>
     *
     * @param direction  Use "R" or "L" to specify right or left side light sensor.
     * @return  approximate brightness, as a percentage (range: 0-100).
     */
    public int getLight(String direction) {
        return (int) Math.round(getSensor("Light", false, direction));
    }

    /**
     * Get the current value of one of the finch's floor-facing infrared "line"
     * sensors. There are two line sensors, located on the bottom of the robot,
     * on either side of the black power button. These are focused downwards
     * towards the floor, so they are useful for checking if the finch is
     * sitting on a light or dark colored carpet or paper. Or, they can be used
     * to follow a painted line on the floor. For example, if the finch is on a
     * white floor, sitting between two dark black stripes painted on the floor,
     * then moving forward while carefully checking the two line sensors can
     * help keep the finch tracking between the lines. If the finch begins to
     * meander off-center, it would cause one of the line sensors to register a
     * dark spot, and the program could detect this and adjust the wheel speeds
     * accordingly.
     *
     * <b>Note:</b> These sensors are not very accurate, they only give a rough
     * estimate of the brightness of the floor, where lower numbers mean "dark"
     * and higher numbers mean "bright". Also note: these don't actually measure
     * color, they measure <b>reflectance</b>. The robot uses a small infrared
     * light facing downwards, and a small sensor to detect the amount of
     * infrared light that is reflected back up off the floor. So a very shiny
     * surface will probably register as "bright" no matter what color it is.
     *
     * <p><b>Example:</b>
     * <pre>
     *    Finch bot = new Finch("A");
     *    bot.setMotors(30, 30);         // Start moving, straight forward, 30% speed.
     *    while (true) {
     *      int x = bot.getLine("L");    // Check left line brightness.
     *      int y = bot.getLine("R");    // Check right line brightness.
     *      if (x &lt; 50)                  // If left side detects a dark line ...
     *        bot.setMotors(30, 20);     // ... Start curving rightwards (right wheel 20%).
     *      else if (y &lt; 50)             // Else if right side detects a dark line ...
     *        bot.setMotors(20, 30);     // ... Start curving leftwards (left wheel 20%).
     *   }
     * </pre>
     *
     * @param direction  Use "R" or "L" to specify right or left side line sensor.
     * @return  approximate brightness, as a percentage (range: 0-100).
     */
    public int getLine(String direction) {
        return (int) getSensor("Line", false, direction);
    }

    private boolean getOrientationBoolean(String orientation) {
        return httpRequestInBoolean("in/finchOrientation/%s/%s", orientation, deviceInstance);
    }

    /**
     * Get information about the finch's physical orientation, or "tilt". The
     * finch contains a tiny multi-axis MEMS gyroscope, which it can use to
     * detect tilting and rotation of its body, much like a human's inner ear
     * gives us a sense of balance. While driving on the floor, this function is
     * probably not useful, as the finch will probably always be "Level". But
     * this function can be used to detect if the finch has been picked up, or
     * fallen upside down. Or, this function can be used to make programs where
     * the finch works like a wand or game controller, where tilting and
     * pointing the finch cause different effects in the program.
     * 
     * <p><b>Example:</b>
     * <pre>
     *    Finch bot = new Finch("A");
     *    while (true) {
     *      String x = bot.getOrientation();
     *      if (x.equals("Beak up")) {       // If tilted up...
     *        bot.setBeak(100, 0, 0);        // ... make the beak red
     *        bot.stop();                    // ... and turn off the wheels
     *      } else if (x.equals("Level")) {  // Else, if on level surface
     *        bot.setBeak(0, 100, 0);        // ... make the beak green
     *        bot.setMotors(30, 30);         // ... and start moving forward, 30% speed.
     *      }
     *   }
     * </pre>
     *
     * @return the orientation of the finch (range: "Beak up", "Beak down", "Tilt left", "Tilt right", "Level", "Upside down")
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
  
    /**
     * Print a short text message on the finch's 5x5 LED panel (the letters
     * scroll, flashing across the tiny screen). Any plain ASCII characters work
     * fine. You can't use emoji or other special characters like em-dashes or
     * smart-quotes. This does <b>not</b> pause your program -- instead, your
     * program will continue executing, allowing you to command the finch to do
     * other things while the text is printing. If you call print again, before
     * the first printing is done, it will cancel the previous message and
     * immediately start printing the new message. So between print calls, you
     * might want to do other actions, or maybe call
     * {@link birdbrain.Finch#delay(double numSeconds) delay(...)} (or
     * {@link birdbrain.Finch#carryOn(double numSeconds) carryOn(...)} or
     * {@link birdbrain.Finch#allowTime(double numSeconds) allowTime(...)}, all
     * of which do the same thing).
     *
     *
     * <p><b>Example:</b>
     * <pre>
     *   Finch bot = new Finch("A");
     *   bot.print("Hello 123");   // Scrolls “Hello 123” on the panel.
     *   bot.spin("L", 45, 30);    // Slow left spin, plenty of time to show the message.
     *   bot.print("Bye!");        // Next, scrolls “Bye!" on the panel.
     *   bot.delay(3.0);           // Delay 3 seconds, should be enough for "Bye!" to finish.
     * </pre>
     *
     * @param message  text to immediately begin scrolling along the LED panel
     */
    @Override
    public void print(String message) { super.print(message); }

    /**
     * Set the entire 5x5 LED panel at once using a 25-element array of 0/1 values
     * (where 1 means on, 0 means off). The array is in row-major order (first
     * five values are the top row, next five values are the second row, etc.).
     * Anything previuosly put on the screen, including any previous print
     * messages, is cancelled.
     *
     * <p><b>Example:</b>
     * <pre>
     *   Finch bot = new Finch("A");
     *   int[] smile = {
     *     0,0,0,0,0,           // top row: all off
     *     0,1,0,1,0,           // 2nd row: two eyes
     *     0,0,0,0,0,           // 3rd row: nothing here, we have no "nose"
     *     1,0,0,0,1,           // 4th row: top of smile
     *     0,1,1,1,0            // 4th row: bottom of smile
     *   };
     *   bot.setDisplay(smile);
     * </pre>
     *
     * @param ledValues  an array of 25 integers (each 0 or 1)
     */
    @Override
    public void setDisplay(int[] ledValues) { super.setDisplay(ledValues); }

    /**
     * Clear the 5x5 LED panel immediately, turning off all 25 lights. If any
     * message was printing, it is cancelled.
     *
     * <p><b>Example:</b>
     * <pre>
     *   Finch bot = new Finch("A");
     *   int[] smile = {
     *     0,0,0,0,0,           // top row: all off
     *     0,1,0,1,0,           // 2nd row: two eyes
     *     0,0,0,0,0,           // 3rd row: nothing here, we have no "nose"
     *     1,0,0,0,1,           // 4th row: top of smile
     *     0,1,1,1,0            // 4th row: bottom of smile
     *   };
     *   while (true) {           // This loop repeatedly
     *     bot.setDisplay(smile); // displays a smile
     *     bot.allowTime(1.0);    // for one second
     *     bot.clearDisplay();    // then blanks the screen
     *     bot.allowTime(0.5);    // for half a second.
     *   }
     * </pre>
     */
    public void clearDisplay() { super.setDisplay(new int[25]); }

    /**
     * Turn a single dot (or "pixel") of the 5x5 LED panel on or off.
     *
     * <p><b>Example:</b>
     * <pre>
     *   Finch bot = new Finch("A");
     *   bot.setPixel(3, 3, 1);   // Turn on the middle pixel (row 3, col 3).
     *   bot.delay(1.0);          // Delay for 1 second.
     *   bot.setPixel(3, 3, 0);   // Turn that dot back off.
     * </pre>
     *
     * @param row     row number (1–5)
     * @param column  column number (1–5)
     * @param value   Use 1 for on, 0 for off.
     */
    @Override
    public void setPixel(int row, int column, int value) { super.setPixel(row, column, value); }

    /**
     * Play a musical note on the finch's buzzer for a certain duration (in
     * beats), and wait for the note to finish playing. One beat equals one
     * second. The notes are defined by MIDI note numbers, on a scale from 32 to
     * 135, corresponding to the keys on a piano. Note 60 is "middle C",
     * approximately the middle key on a piano keyboard. Lower numbers are lower
     * notes. This also pauses your program for the given amount of time, so you
     * can play a song by calling this function several times in a row. If
     * instead you want your program to continue executing, so the finch can do
     * other things while the note is playing, see
     * {@link birdbrain.Finch#playNoteInBackground(int note, double beats) playNoteInBackground(...)}.
     *
     * <p><b>Example:</b>
     * <pre>
     *   Finch bot = new Finch("A");
     *   bot.playNote(60, 1.0);      // Play note 60 ("middle C") for 1 second.
     *   bot.playNote(64, 0.5);      // Play E for 0.5 seconds.
     *   bot.playNote(67, 1.5);      // Play G for 1.5 seconds.
     *   bot.spin("R", 180, 50);     // After, turn right, 50% speed.
     * </pre>
     *
     * @param note    MIDI note number (range: 32–135).
     * @param beats   duration in beats (seconds) (range: 0–16).
     */
    @Override
    public void playNote(int note, double beats) { super.playNote(note, beats); }

    /**
     * Play musical notes in sequence, and wait for the song to finish. This
     * function works the same as calling 
     * {@link birdbrain.Finch#playNote(int note, double beats) playNote(...)}
     * several times in a row, once for each pair of numbers. The function must
     * be given a list of numbers, notes and beats. One beat equals one second.
     * The notes are defined by MIDI note numbers, on a scale from 32 to 135,
     * corresponding to the keys on a piano. Note 60 is "middle C",
     * approximately the middle key on a piano keyboard. Lower numbers are lower
     * notes. This also pauses your program for the total amount of time, so the
     * next part of the program doesn't execute until the song completes.
     *
     * <p><b>Example:</b>
     * <pre>
     *   Finch bot = new Finch("A");
     *   bot.playSong(60, 1.0, 64, 0.5, 67, 0.5);
     * </pre>
     *
     * @param notes_and_beats  A list of alternating integers (note numbers) and
     * decimals (beat durations) defining the song to be played.
     */
    @Override
    public void playSong(Object... notes_and_beats) { super.playSong(notes_and_beats); }

    /**
     * Play a musical note on the finch's buzzer, in the background, for a certain duration (in
     * beats). One beat equals one second. The notes are defined by MIDI note
     * numbers, on a scale from 32 to 135, corresponding to the keys on a piano.
     * Note 60 is "middle C", approximately the middle key on a piano keyboard.
     * Lower numbers are lower notes. This does <b>not</b> pause your program --
     * instead, your program will continue executing, allowing you to command
     * the finch to do other things while the note is playing. Calling this
     * function several times in a row will not work -- each note will begin
     * before the previous has finished, effectively cancelling the one before
     * it. To play a song, either insert delays or other actions between notes,
     * or see 
     * {@link birdbrain.Finch#playNote(int note, double beats) playNote(...)}
     * instead.
     *
     * <p><b>Example:</b>
     * <pre>
     *   Finch bot = new Finch("A");
     *   bot.playNoteInBackground(60, 1.0);      // Play note 60 ("middle C") for 1 second.
     *   bot.spin("L", 360, 30);                 // Spin left, 30% speed, while the first note plays.
     *   bot.playNoteInBackground(64, 0.5);      // Play E for 0.5 seconds.
     *   bot.spin("R", 180, 50);                 // Turn right, 50% speed, while the second note plays.
     *   bot.playNoteInBackground(67, 1.5);      // Play G for 1.5 seconds.
     * </pre>
     *
     * @param note    MIDI note number (range: 32–135).
     * @param beats   duration in beats (seconds) (range: 0–16).
     */
    @Override
    public void playNoteInBackground(int note, double beats) { super.playNoteInBackground(note, beats); }

    /**
     * Get the finch's acceleration along X, Y, and Z axes (in m/(s^2)). The
     * finch contains a 3-axis MEMS accelerometer, which helps it determine
     * whether it is accelerating along X, Y, or Z axes. When the finch is at
     * rest, these values can be used to determine orientation, by measuring
     * which direction gravity is pulling on the finch. These values can also be
     * used to determine if the finch is shaking, falling, bumping into objects,
     * etc.
     *
     * <p><b>Example:</b>
     * <pre>
     *   Finch bot = new Finch("A");
     *   double[] a = bot.getAcceleration();
     *   System.out.println("ax=" + a[0] + " ay=" + a[1] + " az=" + a[2]);
     * </pre>
     *
     * @return array {ax, ay, az} in m/(s^2).
     */
    @Override
    public double[] getAcceleration() { return super.getAcceleration(); }

    // TODO: what basis is used here?
    /**
     * Get the finch's magnetometer vector (compass field) in micro-tesla for X,
     * Y, and Z. The finch contains a small 3-axis digital magnetic compass,
     * pointing towards the north pole, so it can determine which way it is
     * facing at all times. <b>Note:</b> This sensor is not terribly precise, so
     * the results are only approximate values. The direction is represented by
     * a 3-dimensional vector (X, Y, Z).
     *
     * <p><b>Example:</b>
     * <pre>
     *   Finch bot = new Finch("A");
     *   int[] m = bot.getMagnetometer();
     *   System.out.println("mx=" + m[0] + " my=" + m[1] + " mz=" + m[2]);
     * </pre>
     *
     * @return array {mx, my, mz} in micro-tesla
     */
    @Override
    public int[] getMagnetometer() { return super.getMagnetometer(); }

    /**
     * Get the finch’s compass heading in degrees, where 0 means North, 90 East,
     * 180 South, 270 West. The finch contains a small digital magnetic compass
     * pointing towards the north pole, so it can determine which way it is
     * facing. <b>Note:</b> This sensor is not terribly precise, so the results
     * are only approximate values.
     *
     * <p><b>Example:</b>
     * <pre>
     *   Finch bot = new Finch("A");
     *   int deg = bot.getCompass();
     *   System.out.println("Currently pointing: " + deg + " degrees");
     * </pre>
     *
     * @return heading in degrees (0–360)
     */
    @Override
    public int getCompass() { return super.getCompass(); }

    /**
     * Check whether a button on the finch is currently being pressed. The finch
     * has three buttons located on the tail micro:bit circuit board. "A" and
     * "B" are small round black buttons, labeled with small colored triangles.
     * The "Logo" button is a the touch-sensitive gold logo near the back center
     * of the circuit board (pill-shaped, with two small dots). <b>Note:</b>
     * This function determins whether the button is <b>currently</b> being
     * pressed at the exact moment your program calls this function -- it does
     * not wait for the user to press a button, nor does it check if the user
     * pressed the button recently. If you want to wait for a button press, you
     * need a loop to repeatedly check the status of the button.
     *
     * <p><b>Example:</b>
     * <pre>
     *   Finch bot = new Finch("A");
     *   while (true) {
     *     if (bot.getButton("Logo")) {
     *       bot.setBeak(0, 100, 0);   // Green beak when Logo is being touched
     *     } else {
     *       bot.setBeak(0, 0, 0);     // Beak is off otherwise
     *     }
     *     bot.delay(1.0);             // A small delay... we check the button once per second
     *   }
     * </pre>
     *
     * If you want to <b>wait for a button</b> to be pressed, use a loop. It's good practice to give
     * the robot time to think within the loop, so it doesn't get tired. For
     * example:
     *
     * <pre>
     *   Finch bot = new Finch("A");
     *   bot.print("Press button A please");
     *   while (!bot.getButton("A")) {  // Check the button in a loop,
     *     bot.allowTime(0.1);          // about ten times a second.
     *   }
     *   bot.print("Thanks!");
     * </pre>
     *
     *
     * @param button  "A", "B", or "Logo"
     * @return true if pressed; false otherwise
     */
    @Override
    public boolean getButton(String button) { return super.getButton(button); }

    // /**
    //  * Wait for one of the buttons to be pressed, and return a string indicating
    //  * which one was. This will wait indefinitely for the user to press one of
    //  * the three buttons: "A", "B" or the touch-sensitive "Logo" button.
    //  *
    //  * <p><b>Example:</b>
    //  * <pre>
    //  *   Finch bot = new Finch("A");
    //  *   String s = bot.waitForButton();
    //  *   if (s.equals("A"))
    //  *     bot.playNote(60, 0.5);
    //  *   else if (s.equals("B")
    //  *     bot.playNote(48, 0.5);
    //  *   else // must be "Logo"
    //  *     bot.payNote(72, 0.5);
    //  * </pre>
    //  *
    //  * @return Either "A", "B", or "Logo", indicating which button was pressed.
    //  */
    // @Override
    // public String waitForButton() { return super.waitForButton(); }

    /**
     * Get the builtin microphone sound level, as a rough percentage.
     * Higher numbers mean louder sound nearby.
     *
     * <p><b>Example:</b>
     * <pre>
     *   Finch bot = new Finch("A");
     *   while (true) {
     *     int s = bot.getSound();
     *     if (s &gt; 60) bot.setBeak(100, 0, 0);  // Red beak when loud
     *     else bot.setBeak(0, 0, 100);         // Blue beak otherwise
     *   }
     * </pre>
     *
     * @return sound level (approximate percent)
     */
    @Override
    public int getSound() { return super.getSound(); }

    /**
     * Get the temperature near the finch (in degrees Celsius).
     *
     * <p><b>Example:</b>
     * <pre>
     *   Finch bot = new Finch("A");
     *   int temp = bot.getTemperature();
     *   System.out.println("Temperature: " + temp + " C");
     * </pre>
     *
     * @return temperature, in degrees C.
     */
    @Override
    public int getTemperature() { return super.getTemperature(); }

    /**
     * Detect whether the finch is currently being shaken. This uses the
     * gyroscopes to sense whether the robot has been picked up and is being
     * shaken.
     *
     * <p><b>Example:</b>
     * <pre>
     *   Finch bot = new Finch("A");
     *   while (true) {
     *     if (bot.isShaking()) {
     *       bot.setBeak(100, 100, 0);   // Yellow beak when shaking
     *     } else {
     *       bot.setBeak(0, 100, 0);     // Green beak otherwise
     *     }
     *   }
     * </pre>
     *
     * @return true if shaking; false otherwise
     */
    @Override
    public boolean isShaking() { return super.isShaking(); }

    /**
     * Allow the robot to carry on whatever it is doing, delaying the execution
     * of the program for the given number of seconds. This causes the program
     * to simply wait, and the robot keeps doing whatever it was doing. Useful
     * if the robot needs some time to complete an action and you want to just
     * have the program delay for a moment. This is the exact same as
     * {@link birdbrain.Finch#delay(double numSeconds) delay(...)} and
     * {@link birdbrain.Finch#carryOn(double numSeconds) carryOn(...)}.
     *
     * <p><b>Example:</b>
     * <pre>
     *   Finch bot = new Finch("A");
     *   bot.setMotors(30, 30);       // Start the wheels turning
     *   bot.allowTime(1.5);          // delay for 1.5 seconds (and the wheels are still turning)
     *   bot.stop();                  // then stop the wheels
     * </pre>
     *
     * @param numSeconds  time to wait, in seconds
     */
    public static void allowTime(double numSeconds) { Robot.allowTime(numSeconds); }

    /**
     * Allow the robot to carry on whatever it is doing, delaying the execution
     * of the program for the given number of seconds. This causes the program
     * to simply wait, and the robot keeps doing whatever it was doing. Useful
     * if the robot needs some time to complete an action and you want to just
     * have the program delay for a moment. This is the exact same as
     * {@link birdbrain.Finch#allowTime(double numSeconds) allowTime(...)} and
     * {@link birdbrain.Finch#delay(double numSeconds) delay(...)}.
     *
     * <p><b>Example:</b>
     * <pre>
     *   Finch bot = new Finch("A");
     *   bot.setMotors(30, 30);       // Start the wheels turning
     *   bot.carryOn(1.5);            // delay for 1.5 seconds (and the wheels are still turning)
     *   bot.stop();                  // then stop the wheels
     * </pre>
     *
     * @param numSeconds  time to wait, in seconds
     */
    public static void carryOn(double numSeconds) { Robot.delay(numSeconds); }

    /**
     * Allow the robot to carry on whatever it is doing, delaying the execution
     * of the program for the given number of seconds. This causes the program
     * to simply wait, and the robot keeps doing whatever it was doing. Useful
     * if the robot needs some time to complete an action and you want to just
     * have the program delay for a moment. This is the exact same as
     * {@link birdbrain.Finch#allowTime(double numSeconds) delay(...)} and
     * {@link birdbrain.Finch#carryOn(double numSeconds) carryOn(...)}.
     *
     * <p><b>Example:</b>
     * <pre>
     *   Finch bot = new Finch("A");
     *   bot.setMotors(30, 30);       // Start the wheels turning
     *   bot.delay(1.5);              // delay for 1.5 seconds (and the wheels are still turning)
     *   bot.stop();                  // then stop the wheels
     * </pre>
     *
     * @param numSeconds  time to wait, in seconds
     */
    public static void delay(double numSeconds) { Robot.delay(numSeconds); }

    /**
     * Turn off all outputs: stop the wheels, turn off LED panel, beak, and tail lights.
     * Helpful at the end of a program to leave the finch in a quiet state.
     *
     * <p><b>Example:</b>
     * <pre>
     *   Finch bot = new Finch("A");
     *   // ... do things ...
     *   bot.stopAll();   // leave everything off
     * </pre>
     */
    @Override
    public void stopAll() { super.stopAll(); }

}
