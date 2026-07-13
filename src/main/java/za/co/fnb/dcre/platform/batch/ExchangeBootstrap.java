package za.co.fnb.dcre.platform.batch;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import za.co.fnb.dcre.platform.files.ExchangeLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SCRUM-42: materializes the per-client exchange directory tree at startup.
 * Idempotent ({@code createDirectories} is a no-op on an existing dir) and
 * fail-closed: a leaf that cannot be created rethrows and aborts startup.
 */
public final class ExchangeBootstrap implements ApplicationRunner {

    private final ExchangeLayout layout;

    public ExchangeBootstrap(final ExchangeLayout layout) {
        this.layout = layout;
    }

    @Override
    public void run(final ApplicationArguments args) throws IOException {
        for (final Path leaf : layout.allLeafDirs()) {
            Files.createDirectories(leaf);
        }
    }
}
