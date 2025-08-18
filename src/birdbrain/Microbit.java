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
	
    private boolean isMicrobit() {
        return httpRequestInBoolean("in/isMicrobit/static/%s", deviceInstance);
    }

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
        if (device != null && !device.equals("A") && !device.equals("B") && !device.equals("C")) {
            System.out.printf("Error: Could not connect to Microbit \"%s\", that name is not legal.\n", device);
            System.out.printf("When calling `new Microbit(...)`, instead use \"A\", \"B\", or \"C\" as the parameter to\n");
            System.out.printf("specify which robot to connect to. Make sure you are running the BlueBird Connector\n");
            System.out.printf("app and have connected via bluetooth to the Microbit. Within that app you can\n");
            System.out.printf("connect up to three robots, which will be listed as robot \"A\", \"B\", and \"C\".\n");
            throw new IllegalArgumentException(String.format("When calling `new Microbit(\"%s\")`, the argument \"%s\" is invalid. "
                        + "Make sure you are running the BlueBird Connector app and have connected a robot, then use "
                        + "\"A\", \"B\", or \"C\" to specify which robot to connect to.", device, device));
        }
        connect(device);
        if (!isMicrobit()) {
            System.out.printf("Error: Connected to robot \"%s\", but it is not a Microbit device.\n", deviceInstance);
            System.out.printf("Within the BlueBird Connector app, ensure you connect to a Microbit\n");
            System.out.printf("device. Within that app you can connect up to three robots, which\n");
            System.out.printf("will be listed as robot \"A\", \"B\", and \"C\".\n");
            System.exit(0);
        }
    }
}
    
    
