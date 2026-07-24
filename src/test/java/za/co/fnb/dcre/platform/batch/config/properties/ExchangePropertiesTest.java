package za.co.fnb.dcre.platform.batch.config.properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import za.co.fnb.dcre.platform.files.ExchangeChannel;
import za.co.fnb.dcre.platform.files.ExchangeLayout;
import za.co.fnb.dcre.platform.files.ExchangeSub;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExchangePropertiesTest {

    private static final List<String> CLIENTS = List.of("FNBCC01", "FNBCC02", "FNBRF01");

    private static ExchangeProperties bindShared() throws IOException {
        final StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("layout", new ClassPathResource("dcre-exchange-layout.yml"))
                .forEach(source -> env.getPropertySources().addLast(source));
        return Binder.get(env).bind("dcre.exchange", ExchangeProperties.class).get();
    }

    @Test
    void bindsAllThreeClientsFromSharedYaml() throws IOException {
        assertEquals(Set.of("FNBCC01", "FNBCC02", "FNBRF01"), bindShared().clients().keySet());
    }

    @Test
    void everyClientResolvesAllFiveChannels() throws IOException {
        final ExchangeLayout layout = bindShared().toLayout();
        for (final String client : CLIENTS) {
            final String base = client.toLowerCase();
            assertResolves(layout, client, ExchangeChannel.ONHOST_REQ, ExchangeSub.IN, base, "onhost-req", "in");
            assertResolves(layout, client, ExchangeChannel.ONHOST_REQ_ENDO, ExchangeSub.IN, base, "onhost-req-endo", "in");
            assertResolves(layout, client, ExchangeChannel.ONHOST_RESP, ExchangeSub.OUT, base, "onhost-resp", "out");
            assertResolves(layout, client, ExchangeChannel.FINT_REQ, ExchangeSub.OUT, base, "fint-req", "out");
            assertResolves(layout, client, ExchangeChannel.FINT_RESP, ExchangeSub.IN, base, "fint-resp", "in");
        }
    }

    @Test
    void allLeafDirsCoversThreeClientsNineChannelsThreeSubs() throws IOException {
        // 3 clients * 9 channels (5 collections + 4 mandate, SCRUM-79) * 3 subs
        // (each channel has exactly in/out + error + archive) = 81
        assertEquals(81, bindShared().toLayout().allLeafDirs().size());
    }

    @Test
    void toLayoutFailsClosedForUnconfiguredClient() throws IOException {
        final ExchangeLayout layout = bindShared().toLayout();
        assertThrows(IllegalArgumentException.class,
                () -> layout.resolve("FNBXX99", ExchangeChannel.ONHOST_RESP, ExchangeSub.OUT));
    }

    @Test
    void emptyOrNullClientsFailsStartup() {
        assertThrows(IllegalStateException.class, () -> new ExchangeProperties("root", Map.of()));
        assertThrows(IllegalStateException.class, () -> new ExchangeProperties("root", null));
    }

    @Test
    void manChannelBlocksBindAndResolve() throws IOException {
        final String yml = """
                dcre:
                  exchange:
                    root: /exchange
                    clients:
                      "[FNBCC01]":
                        onhost-req:      { in: fnbcc01/onhost-req/in }
                        onhost-req-man:  { in: fnbcc01/onhost-req-man/in, error: fnbcc01/onhost-req-man/error, archive: fnbcc01/onhost-req-man/archive }
                        onhost-resp-man: { out: fnbcc01/onhost-resp-man/out }
                        fint-req-man:    { out: fnbcc01/fint-req-man/out }
                        fint-resp-man:   { in: fnbcc01/fint-resp-man/in }
                """;
        final ExchangeLayout layout = bindYaml(yml).toLayout();
        assertResolves(layout, "FNBCC01", ExchangeChannel.ONHOST_REQ_MAN, ExchangeSub.IN, "fnbcc01", "onhost-req-man", "in");
        assertResolves(layout, "FNBCC01", ExchangeChannel.ONHOST_RESP_MAN, ExchangeSub.OUT, "fnbcc01", "onhost-resp-man", "out");
        assertResolves(layout, "FNBCC01", ExchangeChannel.FINT_REQ_MAN, ExchangeSub.OUT, "fnbcc01", "fint-req-man", "out");
        assertResolves(layout, "FNBCC01", ExchangeChannel.FINT_RESP_MAN, ExchangeSub.IN, "fnbcc01", "fint-resp-man", "in");
        // 1 (onhost-req) + 3 + 1 + 1 + 1 man leaves = 7, no phantom leaves
        assertEquals(7, layout.allLeafDirs().size());
    }

    @Test
    void shippedYmlResolvesManChannels() throws IOException {
        // SCRUM-79: the shared yml now wires the 4 mandate channels per client; they resolve, not fail closed.
        final ExchangeLayout layout = bindShared().toLayout();
        for (final String client : CLIENTS) {
            for (final ExchangeChannel channel : List.of(
                    ExchangeChannel.ONHOST_REQ_MAN, ExchangeChannel.ONHOST_RESP_MAN,
                    ExchangeChannel.FINT_REQ_MAN, ExchangeChannel.FINT_RESP_MAN)) {
                final ExchangeSub sub = (channel == ExchangeChannel.ONHOST_RESP_MAN
                        || channel == ExchangeChannel.FINT_REQ_MAN) ? ExchangeSub.OUT : ExchangeSub.IN;
                layout.resolve(client, channel, sub); // throws IllegalArgumentException if unwired -> test fails
            }
        }
        // 5 collections + 4 mandate channels, 3 subs, 3 clients = 81
        assertEquals(81, layout.allLeafDirs().size());
    }

    private static ExchangeProperties bindYaml(final String yml) throws IOException {
        final StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("inline", new ByteArrayResource(yml.getBytes()))
                .forEach(source -> env.getPropertySources().addLast(source));
        return Binder.get(env).bind("dcre.exchange", ExchangeProperties.class).get();
    }

    private static void assertResolves(final ExchangeLayout layout, final String client,
                                       final ExchangeChannel channel, final ExchangeSub sub,
                                       final String base, final String channelDir, final String subDir) {
        final Path resolved = layout.resolve(client, channel, sub);
        assertTrue(resolved.endsWith(Path.of(base, channelDir, subDir)),
                () -> "%s did not end with %s/%s/%s".formatted(resolved, base, channelDir, subDir));
    }
}
