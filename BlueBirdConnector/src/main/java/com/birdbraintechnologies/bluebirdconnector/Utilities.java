package com.birdbraintechnologies.bluebirdconnector;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;

public final class Utilities {
    protected static String stackTraceToString(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString(); // stack trace as a string
    }
    public static String bytesToString(byte[] bytes) {
        StringBuffer result = new StringBuffer();
        result.append("[ ");
        for(byte b : bytes) result.append( Integer.toHexString(b & 0xFF) + " ");
        result.append("]");
        return result.toString();
    }

    // Generic function to get sub-array of a non-primitive array
    // between specified indices
    public static char[] subArray(char [] array, int beg, int end) {
        return Arrays.copyOfRange(array, beg, end+1);
    }
}
