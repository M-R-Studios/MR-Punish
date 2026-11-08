package com.mrstudios.punish.ip;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Turns an address into a keyed hash (DESIGN.md 8.1). The only class that ever sees an
 * {@link InetAddress}: it returns {@code hex(HMAC-SHA256(secret, normalised address))} and nothing
 * else, and its exceptions never contain the address. IPv4-mapped IPv6 becomes IPv4; IPv6 is
 * masked to the configured prefix first, so one home connection (usually a /64) is one IP.
 */
public final class IpHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String FINGERPRINT_INPUT = "mrpunish-fingerprint";

    private final SecretKeySpec key;
    private final int ipv6Prefix;

    /**
     * @param secret     the 32 bytes from {@code ip-secret.key}
     * @param ipv6Prefix how many leading bits of an IPv6 address are kept (32-128)
     */
    public IpHasher(byte[] secret, int ipv6Prefix) {
        if (secret.length < 16) {
            throw new IllegalArgumentException("The IP secret is too short");
        }
        if (ipv6Prefix < 32 || ipv6Prefix > 128) {
            throw new IllegalArgumentException("ipv6-prefix must be 32-128");
        }
        this.key = new SecretKeySpec(secret.clone(), ALGORITHM);
        this.ipv6Prefix = ipv6Prefix;
    }

    /** The address's hash: 64 lower-case hex characters. */
    public String hash(InetAddress address) {
        return HexFormat.of().formatHex(mac(normalise(address, ipv6Prefix)));
    }

    /**
     * The first 16 hex characters of {@code HMAC(secret, "mrpunish-fingerprint")}: identifies the
     * secret without revealing it, so servers sharing a database can tell they use the same key.
     */
    public String fingerprint() {
        return HexFormat.of().formatHex(mac(FINGERPRINT_INPUT)).substring(0, 16);
    }

    private byte[] mac(String input) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            // The message is the JDK's, about the algorithm or key: never the address.
            throw new IllegalStateException("HMAC-SHA256 is not available: " + e.getClass().getSimpleName());
        }
    }

    /**
     * The text that is hashed: the dotted quad for IPv4 (also for IPv4-mapped IPv6), and the
     * canonical form of the masked address for IPv6. Package-private: only tests look at it.
     */
    static String normalise(InetAddress address, int ipv6Prefix) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address && isIpv4Mapped(bytes)) {
            bytes = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
        }
        if (bytes.length == 4) {
            return (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + "." + (bytes[2] & 0xff) + "." + (bytes[3] & 0xff);
        }
        byte[] masked = bytes.clone();
        for (int bit = ipv6Prefix; bit < 128; bit++) {
            masked[bit / 8] &= (byte) ~(1 << (7 - bit % 8));
        }
        try {
            // Rebuilt from bytes, so no scope id or host name survives, then written in canonical form.
            return canonical((Inet6Address) InetAddress.getByAddress(masked)) + "/" + ipv6Prefix;
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Not an IP address");
        }
    }

    private static boolean isIpv4Mapped(byte[] b) {
        if (b.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }

    /** RFC 5952 form: lower case, no leading zeros, the longest run of zero groups as "::". */
    private static String canonical(Inet6Address address) {
        byte[] b = address.getAddress();
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = ((b[2 * i] & 0xff) << 8) | (b[2 * i + 1] & 0xff);
        }
        int bestStart = -1;
        int bestLength = 0;
        for (int i = 0; i < 8; ) {
            if (groups[i] != 0) {
                i++;
                continue;
            }
            int start = i;
            while (i < 8 && groups[i] == 0) {
                i++;
            }
            if (i - start > bestLength && i - start > 1) {
                bestStart = start;
                bestLength = i - start;
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                out.append("::");
                i += bestLength - 1;
                continue;
            }
            if (out.length() > 0 && out.charAt(out.length() - 1) != ':') {
                out.append(':');
            }
            out.append(Integer.toHexString(groups[i]));
        }
        return out.toString();
    }

    /** Whether {@code address} is IPv4 (or IPv4-mapped IPv6). For tests and logs that must not print it. */
    static boolean isIpv4(InetAddress address) {
        return address instanceof Inet4Address || isIpv4Mapped(address.getAddress());
    }
}
