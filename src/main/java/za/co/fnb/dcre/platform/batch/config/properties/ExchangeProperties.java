package za.co.fnb.dcre.platform.batch.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import za.co.fnb.dcre.platform.files.ExchangeChannel;
import za.co.fnb.dcre.platform.files.ExchangeLayout;
import za.co.fnb.dcre.platform.files.ExchangeSub;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SCRUM-42 exchange directory config (yml, {@code dcre.exchange}). Client map
 * keys are the bracketed client tokens (e.g. {@code "[FNBCC01]"}) to survive
 * Spring relaxed binding of uppercase keys; channel/sub keys bind to the typed
 * fields below. {@link #toLayout()} builds the framework-agnostic kernel.
 */
@ConfigurationProperties(prefix = "dcre.exchange")
public record ExchangeProperties(String root, Map<String, ClientDirs> clients) {

    public ExchangeProperties {
        if (clients == null || clients.isEmpty()) {
            throw new IllegalStateException("dcre.exchange.clients must not be empty (mandatory config)");
        }
    }

    public ExchangeLayout toLayout() {
        final Map<String, Map<ExchangeChannel, Map<ExchangeSub, String>>> dirs = new LinkedHashMap<>();
        clients.forEach((client, clientDirs) -> dirs.put(client, clientDirs.toChannelMap()));
        return new ExchangeLayout(Path.of(root), dirs);
    }

    /**
     * Collections five + mandates four (SCRUM-73). Every field is optional:
     * an absent yml block stays absent in the channel map, so
     * {@link ExchangeLayout#resolve} keeps failing closed for it.
     */
    public record ClientDirs(ChannelDirs onhostReq, ChannelDirs onhostReqEndo, ChannelDirs onhostResp,
                             ChannelDirs fintReq, ChannelDirs fintResp,
                             ChannelDirs onhostReqMan, ChannelDirs onhostRespMan,
                             ChannelDirs fintReqMan, ChannelDirs fintRespMan) {

        Map<ExchangeChannel, Map<ExchangeSub, String>> toChannelMap() {
            final Map<ExchangeChannel, Map<ExchangeSub, String>> map = new LinkedHashMap<>();
            put(map, ExchangeChannel.ONHOST_REQ, onhostReq);
            put(map, ExchangeChannel.ONHOST_REQ_ENDO, onhostReqEndo);
            put(map, ExchangeChannel.ONHOST_RESP, onhostResp);
            put(map, ExchangeChannel.FINT_REQ, fintReq);
            put(map, ExchangeChannel.FINT_RESP, fintResp);
            put(map, ExchangeChannel.ONHOST_REQ_MAN, onhostReqMan);
            put(map, ExchangeChannel.ONHOST_RESP_MAN, onhostRespMan);
            put(map, ExchangeChannel.FINT_REQ_MAN, fintReqMan);
            put(map, ExchangeChannel.FINT_RESP_MAN, fintRespMan);
            return map;
        }

        private static void put(final Map<ExchangeChannel, Map<ExchangeSub, String>> map,
                                final ExchangeChannel channel, final ChannelDirs dirs) {
            if (dirs == null) {
                return;
            }
            final Map<ExchangeSub, String> subs = dirs.toSubMap();
            if (!subs.isEmpty()) {
                map.put(channel, subs);
            }
        }
    }

    public record ChannelDirs(String in, String out, String error, String archive) {

        Map<ExchangeSub, String> toSubMap() {
            final Map<ExchangeSub, String> map = new LinkedHashMap<>();
            putIfPresent(map, ExchangeSub.IN, in);
            putIfPresent(map, ExchangeSub.OUT, out);
            putIfPresent(map, ExchangeSub.ERROR, error);
            putIfPresent(map, ExchangeSub.ARCHIVE, archive);
            return map;
        }

        private static void putIfPresent(final Map<ExchangeSub, String> map,
                                         final ExchangeSub sub, final String relative) {
            if (relative != null) {
                map.put(sub, relative);
            }
        }
    }
}
