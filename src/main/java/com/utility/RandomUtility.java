package com.utility;

import java.util.Random;

public class RandomUtility {

    private static final Random random = new Random();

    public static int nextInt(int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException(min + ">" + max);
        }

        if (min == max) {
            return min;
        }

        int temp = max - min;
        temp = random.nextInt(temp + 1);
        temp = temp + min;
        return temp;
    }
}
