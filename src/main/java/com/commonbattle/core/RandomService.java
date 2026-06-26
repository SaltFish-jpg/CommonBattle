package com.commonbattle.core;

import java.util.Random;

public final class RandomService {
    private final Random random;

    public RandomService(long seed) {
        this.random = new Random(seed);
    }

    public int nextInt(int bound) {
        return random.nextInt(bound);
    }
}
