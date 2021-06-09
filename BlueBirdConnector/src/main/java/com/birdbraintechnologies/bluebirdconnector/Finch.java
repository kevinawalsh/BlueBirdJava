package com.birdbraintechnologies.bluebirdconnector;

public class Finch extends Robot {

    public Finch(String name){
        super(name);
    }

    @Override
    protected int getCalibrationIndex() { return 16; }
}
