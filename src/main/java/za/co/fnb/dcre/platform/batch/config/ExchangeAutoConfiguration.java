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
 * <p>Backs off entirely unless {@code dcre.exchange.root} is set (only the writer
 * services CIR/PRG/CRW import the layout yml). The other services that depend on
 * platform-batch for the seam listener carry no {@code dcre.exchange} config, so
 * they must not bind {@link ExchangeProperties} nor fail startup. The condition
 * targets the nested {@code dcre.exchange.root}, which is distinct from the legacy
 * flat {@code dcre.exchange-root} property.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "dcre.exchange", name = "root")
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
