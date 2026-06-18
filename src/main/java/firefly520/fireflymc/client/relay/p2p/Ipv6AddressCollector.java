package firefly520.fireflymc.client.relay.p2p;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 收集本机可用于 P2P 直连的全局 IPv6 地址（GUA）。
 *
 * <p>IPv6 通常没有 NAT，公网 GUA 地址即为网卡地址，可直接端到端直连。
 * 过滤掉链路本地（fe80::/10）、loopback（::1）、IPv4-mapped（::ffff:0:0/96）、
 * 站点本地、组播等不可全球路由的地址。
 */
public final class Ipv6AddressCollector {
    private static final Logger LOGGER = LoggerFactory.getLogger(Ipv6AddressCollector.class);

    private Ipv6AddressCollector() {
    }

    /**
     * 收集本机所有全球单播 IPv6 地址（不含 zone id）。
     *
     * @return 地址字面量列表，可能为空（本机无公网 IPv6）
     */
    public static List<String> collectGlobalIpv6() {
        List<String> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return addresses;
            }
            for (NetworkInterface nic : Collections.list(interfaces)) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nic.getInetAddresses())) {
                    if (!(addr instanceof Inet6Address inet6)) {
                        continue;
                    }
                    if (inet6.isLinkLocalAddress() || inet6.isLoopbackAddress()
                            || inet6.isSiteLocalAddress() || inet6.isMulticastAddress()) {
                        continue;
                    }
                    byte[] bytes = inet6.getAddress();
                    if (isIpv4Mapped(bytes)) {
                        continue;
                    }
                    // 去掉 zone id（GUA 一般没有，保险处理）
                    String host = inet6.getHostAddress();
                    int percent = host.indexOf('%');
                    if (percent > 0) {
                        host = host.substring(0, percent);
                    }
                    addresses.add(host);
                }
            }
        } catch (SocketException e) {
            LOGGER.warn("[FireflyMC] 收集本机 IPv6 地址失败: {}", e.getMessage());
        }
        return addresses;
    }

    /** IPv4-mapped IPv6 地址：::ffff:0:0/96，前 80 位为 0，随后 16 位为 1。 */
    private static boolean isIpv4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return b[10] == (byte) 0xff && b[11] == (byte) 0xff;
    }
}
