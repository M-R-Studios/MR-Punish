package com.mrstudios.punish.ip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** DESIGN.md 8.1: keyed hashing, normalisation, the fingerprint and the key file. */
class IpHasherTest {

    private static final byte[] KEY_A = filled(1);
    private static final byte[] KEY_B = filled(2);

    private static byte[] filled(int value) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) value);
        return key;
    }

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal); // literals only: no lookup happens
    }

    @Test
    void sameInputSameHashAndItsHexOnly() throws Exception {
        IpHasher hasher = new IpHasher(KEY_A, 64);
        String hash = hasher.hash(ip("203.0.113.7"));
        assertEquals(hash, hasher.hash(ip("203.0.113.7")));
        assertTrue(hash.matches("[0-9a-f]{64}"), hash);
        assertFalse(hash.contains("203"), "not the address in any form");
        assertNotEquals(hash, hasher.hash(ip("203.0.113.8")));
    }

    @Test
    void aDifferentSecretGivesADifferentHash() throws Exception {
        assertNotEquals(new IpHasher(KEY_A, 64).hash(ip("203.0.113.7")), new IpHasher(KEY_B, 64).hash(ip("203.0.113.7")),
                "a leaked database can't be reversed by hashing every IPv4 address without the key");
    }

    @Test
    void ipv4MappedIpv6IsTheSameAsIpv4() throws Exception {
        IpHasher hasher = new IpHasher(KEY_A, 64);
        InetAddress mapped = InetAddress.getByAddress(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff,
                (byte) 203, 0, 113, 7});
        assertEquals(hasher.hash(ip("203.0.113.7")), hasher.hash(mapped));
        assertEquals("203.0.113.7", IpHasher.normalise(mapped, 64));
        assertTrue(IpHasher.isIpv4(mapped));
    }

    @Test
    void ipv6IsGroupedByThePrefix() throws Exception {
        IpHasher by64 = new IpHasher(KEY_A, 64);
        assertEquals(by64.hash(ip("2001:db8:1:2:aaaa::1")), by64.hash(ip("2001:db8:1:2:bbbb:cccc:dddd:eeee")),
                "one /64 is one IP");
        assertNotEquals(by64.hash(ip("2001:db8:1:2::1")), by64.hash(ip("2001:db8:1:3::1")));
        assertEquals("2001:db8:1:2::/64", IpHasher.normalise(ip("2001:0DB8:0001:0002:aaaa::1"), 64));
        IpHasher by128 = new IpHasher(KEY_A, 128);
        assertNotEquals(by128.hash(ip("2001:db8:1:2::1")), by128.hash(ip("2001:db8:1:2::2")));
        assertEquals("2001:db8::1/128", IpHasher.normalise(ip("2001:db8:0:0:0:0:0:1"), 128));
        assertEquals("2001:db8::/48", IpHasher.normalise(ip("2001:db8:0:1:ffff::"), 48), "masked bits are dropped");
    }

    @Test
    void canonicalIpv6Form() throws Exception {
        assertEquals("::/64", IpHasher.normalise(ip("::1"), 64));
        assertEquals("fe80::/64", IpHasher.normalise(ip("fe80::1%1"), 64), "scope ids never reach the hash");
        assertEquals("2001:0:0:1::/64", IpHasher.normalise(ip("2001:0:0:1::5"), 64),
                "only the longest zero run is compressed");
    }

    @Test
    void fingerprintIdentifiesTheKeyWithoutRevealingIt() {
        String a = new IpHasher(KEY_A, 64).fingerprint();
        assertEquals(a, new IpHasher(KEY_A, 128).fingerprint());
        assertTrue(a.matches("[0-9a-f]{16}"), a);
        assertNotEquals(a, new IpHasher(KEY_B, 64).fingerprint());
    }

    @Test
    void badSettingsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new IpHasher(new byte[4], 64));
        assertThrows(IllegalArgumentException.class, () -> new IpHasher(KEY_A, 20));
    }

    @Test
    void theKeyFileIsCreatedOnceAndReadBack(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(IpSecret.FILE);
        IpSecret.Loaded first = IpSecret.loadOrCreate(file);
        assertTrue(first.created());
        assertEquals(32, first.secret().length);
        IpSecret.Loaded again = IpSecret.loadOrCreate(file);
        assertFalse(again.created());
        assertTrue(Arrays.equals(first.secret(), again.secret()));
        assertNotEquals(new IpHasher(first.secret(), 64).fingerprint(),
                new IpHasher(IpSecret.loadOrCreate(dir.resolve("other.key")).secret(), 64).fingerprint(),
                "every new key is random");
    }

    @Test
    void aBrokenKeyFileIsRefusedWithoutEchoingIt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(IpSecret.FILE);
        Files.writeString(file, "this is not base64 !!!", StandardCharsets.US_ASCII);
        Exception notBase64 = assertThrows(java.io.IOException.class, () -> IpSecret.loadOrCreate(file));
        assertFalse(notBase64.getMessage().contains("this is not"), notBase64.getMessage());
        Files.writeString(file, java.util.Base64.getEncoder().encodeToString(new byte[8]), StandardCharsets.US_ASCII);
        assertTrue(assertThrows(java.io.IOException.class, () -> IpSecret.loadOrCreate(file)).getMessage()
                .contains("8 bytes"));
    }
}
