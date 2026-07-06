package firefly520.fireflymc.client.relay.ipv6;

/** IPv6 出站能力检测的终态分类。 */
public enum Ipv6ProbeStatus {
    AVAILABLE,
    DNS_FAILED,
    CONNECT_FAILED,
    CONNECT_TIMEOUT,
    TLS_FAILED,
    HTTP_FAILED,
    UNKNOWN
}
