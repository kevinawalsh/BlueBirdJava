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
    public static String indexToDevLetter(int index) {
        return Character.toString((char)(index + 65));
    }
    public static String bytesToString(byte[] bytes) {
        StringBuffer result = new StringBuffer();
        result.append("[ ");
        for(byte b : bytes) result.append( Integer.toHexString(b & 0xFF) + " ");
        result.append("]");
        return result.toString();
    }
    public static byte[] concatBytes(byte[] a, byte[] b) {
        int aLen = a.length;
        int bLen = b.length;
        byte[] c= new byte[aLen+bLen];
        System.arraycopy(a, 0, c, 0, aLen);
        System.arraycopy(b, 0, c, aLen, bLen);
        return c;
    }
    // Generic function to get sub-array of a non-primitive array
    // between specified indices
    public static char[] subArray(char [] array, int beg, int end) {
        return Arrays.copyOfRange(array, beg, end+1);
    }
}
