/**
 * Copyright (C) 2012 SINTEF <franck.fleurey@sintef.no>
 *
 * Licensed under the GNU LESSER GENERAL PUBLIC LICENSE, Version 3, 29 June 2007;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.gnu.org/licenses/lgpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.thingml.bglib.gui;

import java.util.Hashtable;
import org.thingml.bglib.BGAPI;

/**
 *
 * @author franck
 */
public class BLEDevice {
    
    protected String address;
    protected String name;
    protected int rssi;
    protected int microbitVersion;
    protected int rxHandle; //notifications characteristic
    protected int txHandle; //write characteristic
    
    protected Hashtable<String, BLEService> services = new Hashtable<String, BLEService>();

    public Hashtable<String, BLEService> getServices() {
        return services;
    }
    
    public String getGATTDescription() {
        String result = toString();
        for (BLEService s : services.values()) {
            result += "\n" + s.getDescription();
        }
        return result;
    }
    
    public BLEDevice(String address) {
        this.address = address;
        name = "";
        microbitVersion = 0;
    }

    public String getAddress() {
        return address;
    }

    public String getName() {
        return name;
    }

    public int getRssi() {
        return rssi;
    }

    public int getMicrobitVersion() { return microbitVersion; }

    public int getRxHandle() { return rxHandle; }

    public int getTxHandle() { return txHandle; }

    public void setName(String name) {
        this.name = name;
    }

    public void setRssi(int rssi) {
        this.rssi = rssi;
    }

    public void setMicrobitVersion(int version) { this.microbitVersion = version; }
    
    public void setRxHandle(int handle) { this.rxHandle = handle; }

    public void setTxHandle(int handle) { this.txHandle = handle; }

    public String toString() {
        return name + " [" + address + "] (" + rssi + " dBm)";
    }
    
    public String bytesToString(byte[] bytes) {
        StringBuffer result = new StringBuffer();
        result.append("[ ");
        for(byte b : bytes) result.append( Integer.toHexString(b & 0xFF) + " ");
        result.append("]");
        return result.toString();        
    }
    
}
