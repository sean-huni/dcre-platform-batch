package za.co.fnb.dcre.platform.batch.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import za.co.fnb.dcre.platform.batch.ExchangeBootstrap;
import za.co.fnb.dcre.platform.files.ExchangeLayout;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExchangeAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ExchangeAutoConfiguration.class));

    @Test
    void backsOffWhenNoExchangeConfigPresent() {
        // The 8 non-writer services import no layout yml: the autoconfig must not activate,
        // must not bind ExchangeProperties (which would throw on empty clients), must not fail.
        runner.run(context -> {
            assertNull(context.getStartupFailure(), "context must start without exchange config");
            assertEquals(0, context.getBeanNamesForType(ExchangeLayout.class).length);
            assertEquals(0, context.getBeanNamesForType(ExchangeBootstrap.class).length);
        });
    }

    @Test
    void backsOffWhenOnlyLegacyFlatExchangeRootPresent() {
        // The pre-existing flat property is dcre.exchange-root (element "exchange-root"),
        // distinct from dcre.exchange.root; it must NOT trigger the new autoconfig.
        runner.withPropertyValues("dcre.exchange-root=build/test-exchange").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(0, context.getBeanNamesForType(ExchangeLayout.class).length);
            assertEquals(0, context.getBeanNamesForType(ExchangeBootstrap.class).length);
        });
    }

    @Test
    void activatesAndBootstrapsWhenLayoutYamlImported(@TempDir final Path root) {
        // CIR/PRG/CRW import the shared yml (which sets dcre.exchange.root): full activation.
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.config.import=classpath:dcre-exchange-layout.yml",
                        "DCRE_EXCHANGE_ROOT=" + root)
                .run(context -> {
                    assertNull(context.getStartupFailure(), "context must start with exchange config");
                    assertEquals(1, context.getBeanNamesForType(ExchangeLayout.class).length);
                    assertEquals(1, context.getBeanNamesForType(ExchangeBootstrap.class).length);

                    final ExchangeBootstrap bootstrap = context.getBean(ExchangeBootstrap.class);
                    bootstrap.run(null);

                    final ExchangeLayout layout = context.getBean(ExchangeLayout.class);
                    assertEquals(45, layout.allLeafDirs().size());
                    for (final Path leaf : layout.allLeafDirs()) {
                        assertTrue(Files.isDirectory(leaf), () -> "leaf dir not created: " + leaf);
                    }
                });
    }
}
