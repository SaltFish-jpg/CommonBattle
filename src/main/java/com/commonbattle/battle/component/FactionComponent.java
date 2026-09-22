package com.commonbattle.battle.component;



import com.commonbattle.battle.state.Component;
import java.util.Objects;

public record FactionComponent(String faction) implements Component {
    public FactionComponent {
        Objects.requireNonNull(faction, "faction");
    }
}
