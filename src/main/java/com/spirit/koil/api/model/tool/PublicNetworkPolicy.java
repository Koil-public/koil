package com.spirit.koil.api.model.tool;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;

/**
 * Shared SSRF boundary for model-facing public-network providers.
 *
 * <p>Validation happens for the initial URI and for every redirect destination;
 * DNS answers are checked on each call so a provider cannot use a stale safe
 * resolution to reach a local address later.</p>
 */
public final class PublicNetworkPolicy {
    private PublicNetworkPolicy() {}

    public static URI validate(URI candidate) {
        if (candidate == null || !"https".equalsIgnoreCase(candidate.getScheme())
                || candidate.getHost() == null || candidate.getHost().isBlank()
                || candidate.getUserInfo() != null) {
            throw new IllegalArgumentException("Only public HTTPS URLs are supported.");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(candidate.getHost())) {
                if (nonPublic(address)) {
                    throw new IllegalArgumentException("Local and private network addresses are not permitted.");
                }
            }
        } catch (java.net.UnknownHostException failure) {
            throw new IllegalArgumentException("Public URL host could not be resolved.");
        }
        return candidate.normalize();
    }

    private static boolean nonPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return privateIpv4(bytes);
        if (address instanceof Inet6Address) {
            if (isIpv4Mapped(bytes)) return privateIpv4(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return (first & 0xfe) == 0xfc // unique-local fc00::/7
                    || first == 0xfe && (second & 0xc0) == 0x80; // link-local fe80::/10
        }
        return true;
    }

    private static boolean privateIpv4(byte[] bytes) {
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        return first == 0 || first == 10 || first == 127
                || first == 100 && second >= 64 && second <= 127
                || first == 169 && second == 254
                || first == 172 && second >= 16 && second <= 31
                || first == 192 && second == 168
                || first == 198 && (second == 18 || second == 19)
                || first >= 224;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) if (bytes[index] != 0) return false;
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }
}
