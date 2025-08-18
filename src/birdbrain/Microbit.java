package birdbrain;

/**
 * This class controls a micro:bit via bluetooth. It inherits almost 
 * all its functionality from the abstract class Robot. The only methods it needs here are constructors, 
 * which call a function that checks whether or not it is a micro:bit.
 * 
 * Mike Yuan and Bambi Breewer, BirdBrain Technologies LLC
 * November 2018
 */
public class Microbit extends Robot {
	
    /**
     * default constructor for the library. Construct the baseUrl and set the default device to be A
     */
    public Microbit() {
    	this(null);
    }

    /**
     * constructor for the library. Construct the baseUrl and set the default device to be input.
     *
     * @param device the input device that will be specified by the user.
     */
    public Microbit(String device) {
        super("Microbit", device);
    }
}
    
    
