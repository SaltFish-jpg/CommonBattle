package com.commonbattle.battle.example.card;




import com.commonbattle.battle.state.Component;
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
