package com.birdbraintechnologies.bluebirdconnector;

public class Microbit extends Robot {

    public Microbit(String name){
        super(name);
    }

    @Override
    protected int getCalibrationIndex() { return 7; }
}
