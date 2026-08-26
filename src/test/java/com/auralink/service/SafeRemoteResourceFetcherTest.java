package com.auralink.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.auralink.config.properties.RemoteFetchProperties;
import com.auralink.exception.RemoteResourceRejectedException;

class SafeRemoteResourceFetcherTest {

    private static final byte[] PUBLIC_IPV4 = {(byte) 93, (byte) 184, (byte) 216, (byte) 34};

    private RemoteFetchProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RemoteFetchProperties();
        properties.setMaxBytes(8);
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofSeconds(1));
        properties.setTotalTimeout(Duration.ofSeconds(5));
        properties.setMaxRedirects(2);
    }

    @Test
    void acceptsHttpAndHttpsWhenAllResolvedAddressesArePublic() throws Exception {
        SafeRemoteResourceFetcher fetcher = fetcherResolvingTo(PUBLIC_IPV4);

        assertEquals("https", fetcher.parseAndValidate("https://public.example/art.png").getScheme());
        assertEquals("http", fetcher.parseAndValidate("http://public.example/art.png").getScheme());
    }

    @Test
    void rejectsUnsupportedSchemesAndEmbeddedCredentials() {
        SafeRemoteResourceFetcher fetcher = fetcherResolvingTo(PUBLIC_IPV4);

        assertThrows(RemoteResourceRejectedException.class,
                () -> fetcher.parseAndValidate("file:///etc/passwd"));
        assertThrows(RemoteResourceRejectedException.class,
                () -> fetcher.parseAndValidate("ftp://public.example/file"));
        assertThrows(RemoteResourceRejectedException.class,
                () -> fetcher.parseAndValidate("data:text/plain,secret"));
        assertThrows(RemoteResourceRejectedException.class,
                () -> fetcher.parseAndValidate("https://user:password@public.example/file"));
    }

    @Test
    void rejectsLocalHostNamesWithoutDns() {
        SafeRemoteResourceFetcher fetcher = fetcherResolvingTo(PUBLIC_IPV4);

        assertThrows(RemoteResourceRejectedException.class,
                () -> fetcher.parseAndValidate("http://localhost/file"));
        assertThrows(RemoteResourceRejectedException.class,
                () -> fetcher.parseAndValidate("http://service.local/file"));
        assertThrows(RemoteResourceRejectedException.class,
                () -> fetcher.parseAndValidate("http://metadata.internal/file"));
        assertThrows(RemoteResourceRejectedException.class,
                () -> fetcher.parseAndValidate("http://singlelabel/file"));
    }

    @Test
    void rejectsLoopbackPrivateLinkLocalAndMulticastIpv4() {
        assertRejectedAddress(new byte[] {127, 0, 0, 1});
        assertRejectedAddress(new byte[] {10, 0, 0, 1});
        assertRejectedAddress(new byte[] {(byte) 172, 16, 0, 1});
        assertRejectedAddress(new byte[] {(byte) 192, (byte) 168, 1, 1});
        assertRejectedAddress(new byte[] {(byte) 169, (byte) 254, 1, 1});
        assertRejectedAddress(new byte[] {(byte) 224, 0, 0, 1});
    }

    @Test
    void rejectsIpv6LoopbackLinkLocalAndUniqueLocal() throws Exception {
        assertRejectedAddress(InetAddress.getByAddress(new byte[] {
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
        }));
        assertRejectedAddress(InetAddress.getByAddress(new byte[] {
                (byte) 0xfe, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
        }));
        assertRejectedAddress(InetAddress.getByAddress(new byte[] {
                (byte) 0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
        }));
    }

    @Test
    void rejectsIpv4MappedPrivateAddress() throws Exception {
        byte[] mapped = new byte[] {
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff,
                127, 0, 0, 1
        };

        assertRejectedAddress(InetAddress.getByAddress(mapped));
    }

    @Test
    void rejectsHostWhenAnyResolvedAddressIsPrivate() throws Exception {
        InetAddress publicAddress = InetAddress.getByAddress(PUBLIC_IPV4);
        InetAddress privateAddress = InetAddress.getByAddress(new byte[] {10, 0, 0, 5});
        SafeRemoteResourceFetcher fetcher = new SafeRemoteResourceFetcher(
                properties,
                host -> new InetAddress[] {publicAddress, privateAddress});

        assertThrows(RemoteResourceRejectedException.class,
                () -> fetcher.parseAndValidate("https://mixed.example/file"));
    }

    @Test
    void redirectTargetsAreResolvedAndRevalidated() throws Exception {
        InetAddress publicAddress = InetAddress.getByAddress(PUBLIC_IPV4);
        SafeRemoteResourceFetcher fetcher = new SafeRemoteResourceFetcher(
                properties,
                host -> host.equals("private.example")
                        ? new InetAddress[] {InetAddress.getByAddress(new byte[] {10, 0, 0, 1})}
                        : new InetAddress[] {publicAddress});
        URI origin = fetcher.parseAndValidate("https://public.example/start");

        assertEquals(
                URI.create("https://public.example/next"),
                fetcher.resolveAndValidateRedirect(origin, "/next"));
        assertThrows(RemoteResourceRejectedException.class,
                () -> fetcher.resolveAndValidateRedirect(origin, "http://private.example/secret"));
        assertThrows(RemoteResourceRejectedException.class,
                () -> fetcher.resolveAndValidateRedirect(origin, "file:///etc/passwd"));
    }

    @Test
    void streamCopyAcceptsExactLimitAndRejectsLimitPlusOne() throws IOException {
        byte[] exact = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        ByteArrayOutputStream accepted = new ByteArrayOutputStream();

        assertEquals(8, SafeRemoteResourceFetcher.copyWithLimit(
                new ByteArrayInputStream(exact), accepted, 8));
        assertArrayEquals(exact, accepted.toByteArray());

        assertThrows(RemoteResourceRejectedException.class,
                () -> SafeRemoteResourceFetcher.copyWithLimit(
                        new ByteArrayInputStream(new byte[9]),
                        new ByteArrayOutputStream(),
                        8));
    }

    @Test
    void pinnedResolverReturnsOnlyValidatedAddressesAndRejectsOtherHosts() throws Exception {
        InetAddress publicAddress = InetAddress.getByAddress(PUBLIC_IPV4);
        SafeRemoteResourceFetcher.PinnedDnsResolver resolver =
                new SafeRemoteResourceFetcher.PinnedDnsResolver(
                        "public.example", new InetAddress[] {publicAddress});

        assertArrayEquals(
                new InetAddress[] {publicAddress},
                resolver.resolve("PUBLIC.EXAMPLE."));
        assertEquals("public.example", resolver.resolveCanonicalHostname("public.example"));
        assertThrows(UnknownHostException.class, () -> resolver.resolve("different.example"));
    }

    @Test
    void streamCopyEnforcesTotalDeadlineIndependentlyOfByteLimit() {
        AtomicLong nanoTime = new AtomicLong(0);
        InputStreamWithClock input = new InputStreamWithClock(new byte[] {1, 2}, nanoTime, 6);

        assertThrows(RemoteResourceRejectedException.class,
                () -> SafeRemoteResourceFetcher.copyWithLimit(
                        input,
                        new ByteArrayOutputStream(),
                        8,
                        5,
                        nanoTime::get));
    }

    private SafeRemoteResourceFetcher fetcherResolvingTo(byte[] address) {
        return new SafeRemoteResourceFetcher(
                properties,
                host -> new InetAddress[] {InetAddress.getByAddress(address)});
    }

    private void assertRejectedAddress(byte[] address) {
        try {
            assertRejectedAddress(InetAddress.getByAddress(address));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private void assertRejectedAddress(InetAddress address) {
        SafeRemoteResourceFetcher fetcher = new SafeRemoteResourceFetcher(
                properties,
                host -> new InetAddress[] {address});

        assertThrows(RemoteResourceRejectedException.class,
                () -> fetcher.parseAndValidate("https://resolved.example/file"));
    }

    private static final class InputStreamWithClock extends ByteArrayInputStream {

        private final AtomicLong nanoTime;
        private final long timeAfterRead;

        private InputStreamWithClock(byte[] bytes, AtomicLong nanoTime, long timeAfterRead) {
            super(bytes);
            this.nanoTime = nanoTime;
            this.timeAfterRead = timeAfterRead;
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            int read = super.read(bytes, offset, length);
            nanoTime.set(timeAfterRead);
            return read;
        }
    }
}
