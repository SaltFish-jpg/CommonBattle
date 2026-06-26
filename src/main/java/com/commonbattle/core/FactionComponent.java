package com.commonbattle.core;

import java.util.Objects;

public record FactionComponent(String faction) implements Component {
    public FactionComponent {
        Objects.requireNonNull(faction, "faction");
    }
}
