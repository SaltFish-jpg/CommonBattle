package com.findpath.findPathPainter.utils;

public class TimeUtility {

    public static String nanoToCN(long time) {
        long result = time;
        if (result < 1000) {
            return result + "纳秒";
        }

        result /= 1000;
        if (result < 1000) {
            return result + "微秒";
        }

        result /= 1000;
        if (result < 1000) {
            return result + "毫秒";
        }

        result /= 1000;
        return result + "秒";
    }
}
