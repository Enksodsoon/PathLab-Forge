package org.pathlab.forge.reader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record ViewDefinition(
        int series,
        AxisSelection z,
        AxisSelection t,
        List<ChannelRender> channels,
        RenderProfile profile) {
    public ViewDefinition {
        z = Objects.requireNonNull(z, "z");
        t = Objects.requireNonNull(t, "t");
        channels = List.copyOf(Objects.requireNonNull(channels, "channels"));
        profile = Objects.requireNonNull(profile, "profile");
        if (series < 0 || channels.isEmpty() || (z.projected() && t.projected())) {
            throw new IllegalArgumentException("View definition is invalid");
        }
    }

    public String revision() {
        var canonical = new StringBuilder()
                .append(series).append('|')
                .append(z.mode()).append('|').append(z.start()).append('|').append(z.end()).append('|')
                .append(t.mode()).append('|').append(t.start()).append('|').append(t.end()).append('|')
                .append(profile);
        channels.forEach(channel -> canonical.append('|')
                .append(channel.channel()).append(':').append(channel.enabled()).append(':')
                .append(channel.color()).append(':').append(Double.toString(channel.minimum()))
                .append(':').append(Double.toString(channel.maximum())));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
