package birdbrain;

/**
 * This class extends the Robot class to incorporate functions to control the inputs and outputs
 * of the Hummingbird Bit. It includes methods to set the values of motors and LEDs, as well
 * as methods to read the values of the sensors.
 * 
 * Mike Yuan and Bambi Brewer, BirdBrain Technologies LLC
 * November 2018
 */
public class Hummingbird extends Robot {

	/**
     * Default constructor for the library. Set the default device to be A.
     */
    public Hummingbird() {
        this(null);
    }

   
    /**
     * General constructor for the library. Set the device to be "A", "B", or "C".
     *
     * @param device The letter corresponding to the Hummingbird device, which much be "A", "B", or "C". 
     * The letter that identifies the Hummingbird device is assigned by the BlueBird Connector.
     *      */
    public Hummingbird(String device) {
        super("Hummingbird", device);
    }

    /* This function checks whether a port is within the given bounds. It returns a boolean value 
	   that is either true or false and prints an error if necessary. */
	protected boolean isPortValid(int port, int portMax) {
		if ((port < 1) || (port > portMax)) {
			System.out.println("Error: Please choose a port value between 1 and " + portMax);
			return false;
		}
		else
			return true;	
	}

    /**
     * setPositionServo sets the positionServo at a given port to a specific angle.
     * The function shows a warning dialog if the inputs are not in the specified range.
     *
     * @param port     The port that the position servo is attached to. (Range: 1-4)
     * @param position The angle of the position servo. (Range: 0-180)
     */
    public void setPositionServo(int port, int angle) {
    	if (!isPortValid(port,4))
        	return; 
        
    	angle = clampParameterToBounds(angle, 0, 180, "setPositionServo", "angle");
        int degrees = (int) (angle * 254.0 / 180.0);
        
        httpRequestOut("out/servo/%d/%d/%s", port, degrees, deviceInstance);
    }

    /**
     * setRotationServo sets the rotationServo at a given port to a specific speed.
     * The function shows a warning dialog if the inputs are not in the specified range.
     *
     * @param port  The port that the rotation servo is attached to. (Range: 1-4)
     * @param speed The speed of the rotation servo. (Range: -100-100)
     */
    public void setRotationServo(int port, int speed) {
    	if (!isPortValid(port,4))
        	return; 
    	
    	speed = clampParameterToBounds(speed, -100, 100, "setRotationServo", "speed");
    
        if ((speed > -10) && (speed < 10)) // dead zone to turn off the motor
            speed = 255;
        else // Scale the speed so that it is semi-linear
            speed = ((speed * 23) / 100) + 122;
            
        httpRequestOut("out/rotation/%d/%d/%s", port, speed, deviceInstance);
            
    }

    /**
     * setLED sets the LED at a given port to a specific light intensity.
     * The function shows a warning dialog if the inputs are not in the specified range.
     *
     * @param port      The port that the LED is attached to. (Range: 1-3)
     * @param intensity The intensity of the LED. (Range: 0-100)
     */
    public void setLED(int port, int intensity) {
    	if (!isPortValid(port,3))
        	return; 
    	
    	intensity = clampParameterToBounds(intensity, 0, 100, "setLED", "intensity");
    	
        intensity = (int) (intensity * 255.0 / 100.0);
        
        httpRequestOut("out/led/%d/%d/%s", port, intensity, deviceInstance);
    }

    /**
     * setTriLED sets the triLED at a given port to a specific color.
     * The function shows a warning dialog if the inputs are not in the specified range.
     *
     * @param port           The port that the LED is attached to. (Range: 1-2)
     * @param redIntensity   The intensity of red light of the triLED. (Range: 0-100)
     * @param greenIntensity The intensity of green light of the triLED. (Range: 0-100)
     * @param blueIntensity  The intensity of blue light of the triLED. (Range: 0-100)
     */
    public void setTriLED(int port, int redIntensity, int greenIntensity, int blueIntensity) {
    	if (!isPortValid(port,2))
        	return; 
    	
        redIntensity = clampParameterToBounds(redIntensity, 0, 100, "setTriLED", "redIntensity");
    	greenIntensity = clampParameterToBounds(greenIntensity, 0, 100, "setTriLED", "greenIntensity");
    	blueIntensity = clampParameterToBounds(blueIntensity, 0, 100, "setTriLED", "blueIntensity");
    	
    	// Scale
        redIntensity = (int) (redIntensity * 255.0 / 100.0);
        greenIntensity = (int) (greenIntensity * 255.0 / 100.0);
        blueIntensity = (int) (blueIntensity * 255.0 / 100.0);

        httpRequestOut("out/triled/%d/%d/%d/%d/%s", port, redIntensity, greenIntensity, blueIntensity, deviceInstance);
          
    }

    /**
     * getSensorValue returns the raw sensor value at a given port
     *
     * @param port The port that the sensor is attached to. (Range: 1-3)
     */
    private int getSensorValue(int port) {
    	if (!isPortValid(port,3))
        	return -1; 
        return (int) httpRequestInDouble("in/sensor/%d/%s", port, deviceInstance); 	
    }

    /**
     * getLight returns light sensor value at a given port after processing the raw sensor value retrieved.
     * The function shows a warning dialog if the inputs are not in the specified range.
     *
     * @param port The port that the light sensor is attached to. (Range: 1-3)
     */
    public int getLight(int port) {   	
        int sensorResponse = getSensorValue(port);
        return (int) (sensorResponse * 100.0 / 255.0);
    }

    /**
     * getSound returns sound sensor value at a given port after processing the raw sensor value retrieved.
     * The function shows a warning dialog if the inputs are not in the specified range.
     *
     * @param port The port that the sound sensor is attached to. (Range: 1-3)
     */
    public int getSound(int port) {
    	int sensorResponse = getSensorValue(port);
        return (int) (sensorResponse * 200.0 / 255.0);
    }

    /**
     * getDistance returns distance sensor value at a given port after processing the raw sensor value retrieved.
     * The function shows a warning dialog if the inputs are not in the specified range.
     *
     * @param port The port that the distance sensor is attached to. (Range: 1-3)
     */
    public int getDistance(int port) {
    	int sensorResponse = getSensorValue(port);
        return (int) (sensorResponse * 117.0 / 100.0);
    }

    /**
     * getDial returns dial value at a given port after processing the raw sensor value retrieved.
     * The function shows a warning dialog if the inputs are not in the specified range.
     *
     * @param port The port that the dial is attached to. (Range: 1-3)
     */
    public int getDial(int port) {
    	int sensorResponse = getSensorValue(port);
        int processedResponse = (int) (sensorResponse * 100.0 / 230.0);
        return processedResponse >= 100 ? 100 : processedResponse;
    }
    
    /**
     * getVoltage returns voltage value at a given port after processing the raw sensor value retrieved.
     * The function shows a warning dialog if the inputs are not in the specified range.
     *
     * @param port The port that the dial is attached to. (Range: 1-3)
     */
    public double getVoltage(int port) {
    	int sensorResponse = getSensorValue(port);
        return (sensorResponse * 3.3 / 255);
    }

}
