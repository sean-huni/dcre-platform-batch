package za.co.fnb.dcre.platform.batch.fixture.crg;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test fixture standing in for a fleet application class. Only the PACKAGE LEAF is load-bearing:
 * every Boot application in the fleet sits at {@code za.co.fnb.dcre.<svc>}, this one's leaf is
 * {@code crg}, and the identity derived from it is {@code dcre-crg}.
 *
 * <p>It deliberately does NOT use the real class's fully-qualified name. A shared library that ever
 * publishes a test-fixtures jar would put two classes with one FQCN on a consumer's test classpath
 * and let load order decide which wins.
 */
@SpringBootApplication
public class SampleApplication {
}
