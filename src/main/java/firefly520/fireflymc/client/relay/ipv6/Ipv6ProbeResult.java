package firefly520.fireflymc.client.relay.ipv6;

import javax.annotation.Nullable;
import java.time.Instant;

/** 一次已完成的 IPv6 出站检测的不可变结果。 */
public record Ipv6ProbeResult(
        Ipv6ProbeStatus status,
        Instant checkedAt,
        long durationMs,
        @Nullable Integer httpStatus
) {}
