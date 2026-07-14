package za.co.fnb.dcre.platform.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import za.co.fnb.dcre.platform.files.ExchangeChannel;
import za.co.fnb.dcre.platform.files.ExchangeLayout;
import za.co.fnb.dcre.platform.files.ExchangeSub;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExchangeBootstrapTest {

    private static ExchangeLayout layout(final Path root) {
        return new ExchangeLayout(root, Map.of("FNBCC01", Map.of(
                ExchangeChannel.ONHOST_RESP, Map.of(
                        ExchangeSub.OUT, "fnbcc01/onhost-resp/out",
                        ExchangeSub.ARCHIVE, "fnbcc01/onhost-resp/archive"),
                ExchangeChannel.FINT_REQ, Map.of(
                        ExchangeSub.OUT, "fnbcc01/fint-req/out"))));
    }

    @Test
    void createsEveryLeafDirIdempotently(@TempDir final Path root) throws Exception {
        final ExchangeLayout layout = layout(root);
        new ExchangeBootstrap(layout).run(null);
        for (final Path leaf : layout.allLeafDirs()) {
            assertTrue(Files.isDirectory(leaf), () -> "leaf dir not created: " + leaf);
        }
        // second run is a no-op, not a failure
        assertDoesNotThrow(() -> new ExchangeBootstrap(layout).run(null));
    }

    @Test
    void failsClosedWhenLeafCannotBeCreated(@TempDir final Path root) throws Exception {
        // occupy the client base segment with a regular file so createDirectories cannot descend
        Files.createFile(root.resolve("fnbcc01"));
        assertThrows(IOException.class, () -> new ExchangeBootstrap(layout(root)).run(null));
    }
}
