package za.co.fnb.dcre.mrv;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test fixture standing in for the fleet application that carries TWO seam tokens, {@code mrv} and
 * {@code mrv-account-reference}. Its package leaf is one value, {@code mrv}, which is exactly why
 * the leaf and not the seam token names the service: a service with two {@code service.name} values
 * publishes two {@code job} labels.
 */
@SpringBootApplication
public class MrvApplication {
}
