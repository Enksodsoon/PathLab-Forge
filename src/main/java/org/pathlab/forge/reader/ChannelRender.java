package org.pathlab.forge.reader;

import java.util.Locale;
import java.util.Objects;

public record ChannelRender(int channel, boolean enabled, String color, double minimum, double maximum) {
    public ChannelRender {
        color = Objects.requireNonNull(color, "color").toLowerCase(Locale.ROOT);
        if (channel < 0 || !color.matches("#[0-9a-f]{6}")
                || !Double.isFinite(minimum) || !Double.isFinite(maximum) || maximum <= minimum) {
            throw new IllegalArgumentException("Channel rendering is invalid");
        }
    }
}
