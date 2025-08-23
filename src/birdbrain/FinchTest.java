package birdbrain;
public class FinchTest {
    public static void main(String[] args) {
        Finch myFinch = new Finch("A");

        for (int i = 0; i < 10; i++) {
            myFinch.setBeak(100, 100, 100);
            myFinch.delay(1);
            myFinch.setBeak(0, 0, 0);
            myFinch.delay(1);
        }

        myFinch.stopAll();
    }
}
