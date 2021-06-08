package com.birdbraintechnologies.bluebirdconnector;

public class RobotInfo {
    public String deviceName;
    public String deviceFancyName;
    public int deviceRSSI;
    public char devLetter = 'X';

    public RobotInfo(String name, int rssi){
        deviceName = name;
        deviceRSSI = rssi;
        deviceFancyName = FancyNames.getDeviceFancyName(name);
    }
}
