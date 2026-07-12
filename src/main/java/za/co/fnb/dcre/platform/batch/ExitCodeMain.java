package za.co.fnb.dcre.platform.batch;

import org.springframework.boot.SpringApplication;

/** R-34 exit-code wiring: the JVM exit code carries the Batch outcome. */
public final class ExitCodeMain {

    private ExitCodeMain() {
    }

    public static void run(Class<?> app, String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(app, args)));
    }
}
