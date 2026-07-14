package za.co.fnb.dcre.platform.batch.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import za.co.fnb.dcre.platform.batch.ExchangeBootstrap;
import za.co.fnb.dcre.platform.batch.config.properties.ExchangeProperties;
import za.co.fnb.dcre.platform.files.ExchangeLayout;

/**
 * SCRUM-42: exposes the {@link ExchangeLayout} kernel from {@link ExchangeProperties}
 * and wires the startup {@link ExchangeBootstrap} for every writer service that
 * imports {@code classpath:dcre-exchange-layout.yml}.
 *
 * <p>Backs off entirely unless the marker {@code dcre.exchange.enabled=true} is set,
 * which ONLY the shipped {@code dcre-exchange-layout.yml} provides (imported by the
 * writer services CIR/PRG/CRW). The other services that depend on platform-batch for
 * the seam listener carry no {@code dcre.exchange} config, so they must not bind
 * {@link ExchangeProperties} nor fail startup.
 *
 * <p>The condition deliberately does NOT key on {@code dcre.exchange.root}: the
 * orchestrator exports {@code DCRE_EXCHANGE_ROOT} to EVERY stage pod, and relaxed
 * binding canonicalizes that env var to {@code dcre.exchange.root}, which activated
 * the autoconfig fleet-wide and crashed non-writer services at startup (caught live
 * on the first in-cluster smoke, 2026-07-14). No env var canonicalizes to
 * {@code dcre.exchange.enabled}.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "dcre.exchange", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ExchangeProperties.class)
public class ExchangeAutoConfiguration {

    @Bean
    ExchangeLayout exchangeLayout(final ExchangeProperties properties) {
        return properties.toLayout();
    }

    @Bean
    ExchangeBootstrap exchangeBootstrap(final ExchangeLayout exchangeLayout) {
        return new ExchangeBootstrap(exchangeLayout);
    }
}
