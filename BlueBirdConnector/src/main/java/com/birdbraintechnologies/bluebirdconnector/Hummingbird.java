package com.birdbraintechnologies.bluebirdconnector;

public class Hummingbird extends Robot {

    public Hummingbird(String name){
        super(name);
    }

    @Override
    protected int getCalibrationIndex() { return 7; }
}
