package com.commonbattle.example.card;

import com.commonbattle.core.Component;

public final class ZoneComponent implements Component {
    private Zone zone;

    public ZoneComponent(Zone zone) {
        this.zone = zone;
    }

    public Zone zone() {
        return zone;
    }

    public void moveTo(Zone zone) {
        this.zone = zone;
    }
}
