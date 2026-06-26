package com.commonbattle.core;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AttributeComponent implements Component {
    private final Map<String, Integer> values = new LinkedHashMap<>();

    public AttributeComponent set(String name, int value) {
        values.put(name, value);
        return this;
    }

    public int get(String name) {
        return values.getOrDefault(name, 0);
    }

    public int base(String name) {
        return get(name);
    }
}
