package za.co.fnb.dcre.platform.batch.config.properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
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
    void allLeafDirsCoversThreeClientsFiveChannelsThreeSubs() throws IOException {
        // 3 clients * 5 channels * 3 subs (each channel has exactly in/out + error + archive) = 45
        assertEquals(45, bindShared().toLayout().allLeafDirs().size());
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

    private static void assertResolves(final ExchangeLayout layout, final String client,
                                       final ExchangeChannel channel, final ExchangeSub sub,
                                       final String base, final String channelDir, final String subDir) {
        final Path resolved = layout.resolve(client, channel, sub);
        assertTrue(resolved.endsWith(Path.of(base, channelDir, subDir)),
                () -> "%s did not end with %s/%s/%s".formatted(resolved, base, channelDir, subDir));
    }
}
