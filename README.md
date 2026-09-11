# dcre-platform-batch

Shared Spring Batch platform library for the DCRE Collections 3.0 pipeline: per-client exchange
directory config and bootstrap, the AGT outcome seam, exit-code wiring, CockroachDB step retry,
stale-metadata self-healing, and cgroup-aware partition sizing.

## What it does

Every DCRE stage service runs as a short-lived Kubernetes Job minted by the AGT orchestrator, and
this library carries the operational conventions they all share. It ships the exchange-layout
kernel (`ExchangeProperties` + `ExchangeBootstrap`, auto-configured behind the
`dcre.exchange.enabled` marker, with the shared `dcre-exchange-layout.yml` resource), the
AGT-facing business-verdict seam (`OutcomeFileWriter`), JVM exit-code transport (`ExitCodeMain`),
CockroachDB 40001 retry for tasklet steps (`CrdbRetryExceptionHandler`), Batch-metadata
self-healing (`StaleExecutionSweeper`), and runtime partition sizing (`PartitionSizer`). All
the collections, payments and mandates stage services plus `dcre-rpt` consume it; the writer services CIR, CRW and CRG
additionally import the shipped layout yml.

## Architecture and principles

- **SOLID, single responsibility per unit**: each class owns exactly one operational concern
  (retry policy, outcome seam, exit-code transport, metadata sweep, directory bootstrap,
  partition sizing), each small enough to test in isolation. The exchange kernel is split along
  clear interfaces: `ExchangeProperties` binds config, `toLayout()` produces the
  framework-agnostic `ExchangeLayout` from `platform-files`, and `ExchangeBootstrap` only
  materializes directories.
- **12FactorApp Alignment - https://12factor.net/**: config strictly from the environment
  (`DCRE_EXCHANGE_ROOT` overrides the committed dev default), stateless one-shot consumers, and
  dev/prod parity (the same shipped yml drives local and in-cluster layouts). Spring Boot is
  `compileOnly` for everything EXCEPT telemetry, so for those concerns the library stays a pure
  convention carrier and the consumer's runtime provides the framework. Telemetry is the marked
  exception and it is a real one: four `api` coordinates force Spring Boot 4.1.0 and Micrometer
  1.17.0 onto all 30 consumers, because an exporter that is only on the compile classpath exports
  nothing. See the `[!CONVENTION-OVERRIDE]` in `build.gradle` for the trade, and **Telemetry**
  below for what this library writes into every consumer's Environment.
- **Layer-first packages**: `config/` + `config/properties/` for the auto-configuration seam,
  top-level utilities for the batch conventions, matching the fleet package canon.
- **Idempotent restart semantics**: `ExchangeBootstrap` is a no-op on existing directories and
  fail-closed on an uncreatable leaf; `StaleExecutionSweeper` abandons STARTED executions a
  killed pod left behind so the same-identity relaunch does not throw
  `JobExecutionAlreadyRunning`; `OutcomeFileWriter` writes atomically via `StagedWrite`, so AGT
  treats absence as never-success; `CrdbRetryExceptionHandler` re-runs the whole tasklet in a
  fresh transaction on a serialization abort (retry, never skip). These semantics were
  chaos-validated fleet-wide on 2026-07-15 (SIGKILL at every stage, same-identity relaunch,
  zero duplicates).

### Key classes

| Class | Concern |
|---|---|
| `ExchangeProperties` | Binds `dcre.exchange.*` (client map keyed by bracketed tokens, e.g. `"[FNBCC01]"`, to survive relaxed binding); fails fast on an empty client map; `toLayout()` builds the `ExchangeLayout` kernel |
| `ExchangeAutoConfiguration` | Exposes `ExchangeLayout` + `ExchangeBootstrap` beans; backs off entirely unless `dcre.exchange.enabled=true`, which only the shipped yml sets. Deliberately NOT keyed on `dcre.exchange.root`: AGT exports `DCRE_EXCHANGE_ROOT` to every stage pod and relaxed binding canonicalizes it to `dcre.exchange.root`, which would activate the autoconfig fleet-wide (caught live in-cluster 2026-07-14, regression-tested) |
| `ExchangeBootstrap` | `ApplicationRunner` that creates every leaf directory of the layout at startup; idempotent, fail-closed |
| `dcre-exchange-layout.yml` | Shared classpath resource: 3 clients (FNBCC01, FNBCC02, FNBRF01) x 9 channels x 3 subs = 81 leaf directories (5 collections channels + 4 mandates; verified against the yml 2026-08-08); imported by CIR/CRW/CRG via `spring.config.import: classpath:dcre-exchange-layout.yml` |
| `CrdbRetryExceptionHandler` | Step-level retry for CockroachDB serialization aborts (SQLSTATE 40001, surfacing as `TransientDataAccessException`): max 5 attempts, exponential backoff from 100 ms with jitter, per-`RepeatContext` attempt budget. Register on TASKLET steps only (`.exceptionHandler(new CrdbRetryExceptionHandler("CIR"))`), never on chunk-oriented steps: swallowing at the repeat level re-runs the whole iteration |
| `OutcomeFileWriter` | SYNTHETIC-CONTRACT (R-35): writes `<exchangeRoot>/outcomes/<jobName>` atomically via `StagedWrite`; the AGT-service business-verdict seam |
| `ExitCodeMain` | R-34: `System.exit(SpringApplication.exit(...))` so the container exit code carries the Batch outcome to the K8s Job. Reserves `CONFIG_FAILURE_EXIT_CODE` = 78 (EX_CONFIG, sysexits.h) for any failure BEFORE the runner phase: that is infrastructure, not a job verdict, and AGT classifies 78 as `TECH_CONFIG_FAILED` on its own bounded budget instead of burning the `TECH_FAILED` orphan budget. A failure once the runner phase has begun propagates unchanged (JVM status 1) |
| `RunnerPhaseGate` | The boundary marker `ExitCodeMain` classifies on: an `ApplicationListener<ApplicationStartedEvent>` (published after refresh, before any runner) at `HIGHEST_PRECEDENCE`. A named class deliberately, never a lambda: Spring cannot resolve a lambda's generic event type and would swallow the resulting `ClassCastException`, leaving the gate permanently shut |
| `StaleExecutionSweeper` | A-39a: plain JDBC against the service's own batch-metadata `DataSource` (single-writer, R-04), abandons stale STARTED job and step executions using CRDB-safe INTERVAL literals; AGT never touches service schemas |
| `PartitionSizer` | R-41: partition count = clamp(availableProcessors, 1, maxPartitions); `availableProcessors()` is cgroup-aware and reflects the pod CPU limit, not the node |
| `StageIdentity` | The two labels every other number depends on: `service.name` and `service.instance.id`. Validates both in its canonical constructor, so `dcre-[a-z]+` is enforced wherever it is built and a per-job shaped token such as `mrv-account-reference` is REFUSED |
| `TelemetryEnvironmentPostProcessor` | Registered in `META-INF/spring.factories`. Runs before any bean exists and writes the identity into EVERY consumer's Environment: see **Telemetry** below. It cannot be a `MeterFilter`, because `OtlpMeterRegistry` builds its OTLP resource once in its constructor and a filter only sees a `Meter.Id` afterwards |
| `StageIdentityResolver` | The rule that turns candidate `@SpringBootApplication` classes into a validated `StageIdentity`: the package leaf, one per deployable, with `dcre.telemetry.stage` as the explicit override. Tolerant for the post-processor, which runs for every consumer including non-fleet ones |
| `TelemetryAutoConfiguration` | Publishes the `StageIdentity` BEAN behind `dcre.telemetry.enabled=true`. Resolves nothing: it reads back what the post-processor published, so the bean cannot name the service differently from what is being exported, and a service that renamed itself fails at startup by name |
| `TelemetryProperties` | Binds `dcre.telemetry.*` and is the one place those key names are written |

## Prerequisites

- JDK 25 (`sourceCompatibility`/`targetCompatibility` in `build.gradle`; there is no Gradle
  toolchain block in this module or anywhere in the fleet, and adding one is not allowed;
  wrapper is Gradle 9.5.1)
- `za.co.fnb.dcre:platform-files:0.1.0` (and transitively `platform-model:0.1.0`) published to
  Maven Local: this module resolves the platform chain from `mavenLocal()` only
- Docker IS required for `./gradlew build`: five test classes start CockroachDB via Testcontainers
  (`BatchJdbcConfigIT`, `PortalSafeStepExecutionDaoIT`, `HeartbeatWriterIT`,
  `StaleChangelogLockReleaserIT`, `LiquibaseLockAutoConfigurationIT`). No `.env` is needed

## Quickstart

```bash
# publish the dependency chain first (once per machine)
cd ../platform-model && ./gradlew publishToMavenLocal
cd ../platform-files && ./gradlew publishToMavenLocal

# build, test and publish this module
cd ../platform-batch
./gradlew build
./gradlew publishToMavenLocal
```

Consume from a stage service (`mavenLocal()` in `repositories`):

```groovy
dependencies {
    implementation 'za.co.fnb.dcre:platform-batch:0.1.0'
}
```

`platform-files` is an `api` dependency, so `StagedWrite`, `ExchangeLayout` and (through
`platform-files`' own `api` on `platform-model`) the shared model types re-export to every
consumer. Spring Boot 4.1.0, Spring Batch 6.0.4 (`spring-batch-infrastructure`), `spring-tx`
7.0.8 and `slf4j-api` 2.0.17 are `compileOnly` here: the consuming service provides them.

Telemetry is the exception, marked `[!CONVENTION-OVERRIDE]` in `build.gradle`. These four are
`api`, so they reach every consumer's RUNTIME classpath and force their versions there:

```groovy
api 'org.springframework.boot:spring-boot-starter-actuator:4.1.0'
api 'org.springframework.boot:spring-boot-starter-opentelemetry:4.1.0'
api "io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.28.1-alpha"
api 'io.micrometer:micrometer-registry-otlp:1.17.0'
```

An exporter that is only on the compile classpath exports nothing, and the alternative is the same
four lines hand-copied into 30 build files. `micrometer-registry-otlp` is `api` rather than
`runtimeOnly` because code that flushes the registry has to compile against it.

## Configuration

This library reads no environment for the exchange concern except through the shipped
`dcre-exchange-layout.yml`, which a writer service opts into via
`spring.config.import: classpath:dcre-exchange-layout.yml`.

**Telemetry is different and you should read this before consuming a new version.** Because
`TelemetryEnvironmentPostProcessor` is registered in `META-INF/spring.factories`, merely having this
library on the classpath makes it WRITE four properties into every consumer's Environment, with no
opt-in. They are contributed as DEFAULTS added last, so anything a service sets explicitly wins.

| Written key | Value | Why |
|---|---|---|
| `spring.application.name` | `dcre-<package leaf>`, e.g. `dcre-crg` | **This renames your application.** Boot turns it into the OTLP `service.name`, from which Prometheus derives `job` |
| `management.opentelemetry.resource-attributes.service.name` | the same value | The strongest channel in Boot's precedence, so an `OTEL_SERVICE_NAME` exported to every pod cannot displace the validated name |
| `management.opentelemetry.resource-attributes.service.instance.id` | `$HOSTNAME`, else a unique local id | Prometheus derives `instance` from it; without it every replica collapses into ONE series and each crossing reads as a counter reset |
| `management.otlp.metrics.export.enabled` | `true` only when `dcre.telemetry.enabled` is exactly (case-insensitively) `true` | Boot's own OTLP export activates on the JAR being present, which no marker of ours can refuse, so the marker is made to control it here. Resolved in Java, never a placeholder: an empty marker would resolve to `""` and Boot's gate defaults to ON |

| Name | Default | Purpose |
|---|---|---|
| `DCRE_EXCHANGE_ROOT` (env) | `../../../../../infra/dcre-infra/exchange` (dev-relative, from the shipped yml) | Exchange root directory; AGT exports an absolute path to every stage pod |
| `dcre.exchange.enabled` | unset (`true` only in the shipped yml) | Activation marker for `ExchangeAutoConfiguration`; non-writer services carry no `dcre.exchange` config and the autoconfig backs off |
| `dcre.exchange.root` | `${DCRE_EXCHANGE_ROOT:...}` | Root path the client-relative directories resolve against |
| `dcre.exchange.clients` | 3 clients in the shipped yml | Per-client channel/sub directory map; startup fails fast if empty |
| `dcre.telemetry.enabled` | unset (so `false`) | Activation marker for `TelemetryAutoConfiguration` AND for OTLP export. Deliberately not an endpoint: an endpoint is the shape the orchestrator broadcasts to every pod |
| `dcre.telemetry.stage` | unset (the application package leaf is used) | Explicit override for the stage token. It is NOT the token handed to `OutcomeSeamListener`, which is per-job: `mrv` carries two and `mrg` passes a variable, so two services would publish two `job` labels each |

## Testing

```bash
./gradlew test
```

Sixteen test classes, 99 tests (JUnit 6, AssertJ), counted from
`build/test-results/test/TEST-*.xml` after a full `./gradlew clean build` on 2026-09-11.
Container-free: autoconfig back-off and activation (`ApplicationContextRunner`, including the
`DCRE_EXCHANGE_ROOT` relaxed-binding regression), shared-yml binding of all 81 leaf directories,
idempotent/fail-closed bootstrap, partition clamping, the outcome seam, the `ExitCodeMain`
classification seam, and the telemetry identity (read through Boot's own
`OpenTelemetryResourceAttributes`, so the assertions are about what the exporter receives rather
than about the property keys the library writes). Testcontainers CockroachDB (Docker required):
`BatchJdbcConfigIT`, `PortalSafeStepExecutionDaoIT`, `HeartbeatWriterIT`,
`StaleChangelogLockReleaserIT`, `LiquibaseLockAutoConfigurationIT`.

`ExitCodeMainForkIT` is the odd one out and deliberately so: a unit test on the classification
seam would pass while the real process still exited 1, so it forks a JVM
(`ExitCodeMainForkHarness`, a bare `@Configuration`, no DataSource, no Docker) and asserts the
actual process status is 78 / 1 / 0. It needs the test runtime classpath, which the Gradle `test`
task injects as `dcre.fork.classpath`; run from an IDE without that property it skips rather than
fails.

## Local cluster deployment

This library ships inside the stage-service images, not as its own deployment. In the fleet dev
loop (see `dcre-infra`) it is step 2:

1. `dcre-infra scripts/kind-up.sh` creates the `dcre-dev` kind cluster, CockroachDB and the
   exchange hostPath.
2. Publish the platform chain to Maven Local: `platform-model`, `platform-files`,
   `platform-batch` (this repo), `platform-persistence`, each with
   `./gradlew publishToMavenLocal`.
3. Build each stage service (`./gradlew bootJar && docker build ... && kind load docker-image
   --name dcre-dev ...`); AGT then mints the stage services as short-lived K8s Jobs. The Job exit
   code AGT observes is produced by `ExitCodeMain`, and the business verdict AGT arbitrates is
   the `OutcomeFileWriter` seam file.

After changing this library: bump or republish to Maven Local, rebuild the consuming service
images, and `kind load` them again.

## Related repositories

- Orchestrator: [dcre-agt](https://github.com/sean-huni/dcre-agt)
- Stage services: [dcre-crr](https://github.com/sean-huni/dcre-crr),
  [dcre-ctv](https://github.com/sean-huni/dcre-ctv),
  [dcre-cde](https://github.com/sean-huni/dcre-cde),
  [dcre-cir](https://github.com/sean-huni/dcre-cir),
  [dcre-crw](https://github.com/sean-huni/dcre-crw),
  [dcre-cix](https://github.com/sean-huni/dcre-cix),
  [dcre-csx](https://github.com/sean-huni/dcre-csx),
  [dcre-cpx](https://github.com/sean-huni/dcre-cpx),
  [dcre-crg](https://github.com/sean-huni/dcre-crg),
  [dcre-pai](https://github.com/sean-huni/dcre-pai),
  [dcre-hcs](https://github.com/sean-huni/dcre-hcs)
- Platform libraries: [dcre-platform-model](https://github.com/sean-huni/dcre-platform-model),
  [dcre-platform-files](https://github.com/sean-huni/dcre-platform-files),
  [dcre-platform-persistence](https://github.com/sean-huni/dcre-platform-persistence)
- Infra and tooling: [dcre-infra](https://github.com/sean-huni/dcre-infra),
  [dcre-fixture-toolkit](https://github.com/sean-huni/dcre-fixture-toolkit),
  [dcre-design-register](https://github.com/sean-huni/dcre-design-register),
  [dcre-rpt](https://github.com/sean-huni/dcre-rpt)
