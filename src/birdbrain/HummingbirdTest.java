package birdbrain;

public class HummingbirdTest {
    public static void main(String[] args) {
        Hummingbird myBit = new Hummingbird("A");
        
        for (int i = 0; i < 10; i++) {
        	myBit.setLED(1, 100);
        	myBit.delay(1);
        	myBit.setLED(1, 0);
        	myBit.delay(1);
        }
        
        myBit.stopAll();
    }
}
