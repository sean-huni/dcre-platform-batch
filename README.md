# dcre-platform-batch

> Part of the DCRE fleet. For the fleet map, the rulings and the diagrams that specify every stage, start at the [DCRE design register](https://github.com/sean-huni/dcre-design-register); the complete list of live repositories is its [Repositories](https://github.com/sean-huni/dcre-design-register/blob/dev/README.md#repositories) table.

Shared Spring Batch platform library for the DCRE pipeline: the persistent JDBC job repository,
the AGT outcome seam and liveness heartbeat, exit-code wiring, CockroachDB step retry, stale
Batch-metadata and Liquibase-lock self-healing, per-client exchange directories, and cgroup-aware
partition sizing.

## What it does

A library, not a service: it has no `main`, no image and no runtime of its own. It ships inside
every DCRE stage-service jar, and each stage runs as a short-lived Kubernetes Job minted by the AGT
orchestrator, so this library carries the operational conventions those Jobs share.

Public API, package `za.co.fnb.dcre.platform.batch` (and `.config`, `.config.properties`):

| Class | Concern |
|---|---|
| `BatchJdbcConfig` | SCRUM-84: a persistent JDBC `JobRepository` replacing the Boot 4.1 / Batch 6 in-memory `ResourcelessJobRepository`. `@EnableBatchProcessing` makes Boot's batch autoconfig back off; the repository is built explicitly with the prefix from `BatchProperties`, on the primary `DataSource` and transaction manager (one datasource is a correctness requirement: step metadata commits with the business chunk). Launch and restart run at `READ_COMMITTED`. `@Import` only, never component-scanned, deliberately not an auto-configuration |
| `PortalSafeJobRepositoryFactoryBean` / `PortalSafeStepExecutionDao` | SCRUM-101: package-private. Batch 6.0.4's `getLastStepExecution` nests a second query inside an open `ResultSet`, which CockroachDB rejects, so every restart of an existing job instance died. The substituted DAO reads sequentially. Pinned to the exact `springBatchVersion` in `gradle.properties` by `PortalSafeOverrideGuardTest` |
| `BatchProperties` | Binds `dcre.batch.table-prefix`. Fail-closed: null, blank, the bare `BATCH_` and any value not ending in `_` are rejected at context start |
| `HeartbeatDatasourceConfig` + `HeartbeatWriter` | SCRUM-85 (R-47): while a job of this pod runs, a `@Scheduled` UPDATE stamps `heartbeat_at` / `owner_pod` on `agt_ops.launch_intent` for the row keyed by `JOB_NAME`, so AGT can see a wedged-but-alive pod. A last-writer stamp, not a fenced lease. Never fails a job: missing intent row or DB errors WARN once and are swallowed. Uses its own non-pooling agt_ops connection, not a `DataSource` bean. `@Import` only; the service also registers `heartbeatWriter` as a listener on its Job |
| `OutcomeSeamListener` | SCRUM-58 (R-33/R-35): the one shared business-verdict listener. On `COMPLETED` only, runs an optional owner-local persistence hook (write-ahead), then writes the verdict via `OutcomeFileWriter`. Technical death writes nothing: the exit code and the K8s Failed condition are the witnesses |
| `OutcomeFileWriter` | Writes `<exchangeRoot>/outcomes/<jobName>` atomically via `StagedWrite`; `jobNameOrLocal` resolves `JOB_NAME` or falls back to `local-<svc>-<executionId>` |
| `ExitCodeMain` + `RunnerPhaseGate` | R-34: `System.exit(SpringApplication.exit(...))` so the container exit code carries the Batch outcome. A failure before the runner phase exits `78` (`CONFIG_FAILURE_EXIT_CODE`, EX_CONFIG), which AGT classifies as `TECH_CONFIG_FAILED`; a failure after it propagates unchanged (status 1). `RunnerPhaseGate` is the `ApplicationStartedEvent` listener that marks the boundary, a named class because Spring cannot resolve a lambda's generic event type |
| `CrdbRetryExceptionHandler` | Step-level retry for CockroachDB serialization aborts (`TransientDataAccessException`): 5 attempts, exponential backoff from 100 ms with jitter, per-`RepeatContext` budget. TASKLET steps only, never chunk-oriented steps |
| `StaleExecutionSweeper` | A-39a: `abandonStale(dataSource, tablePrefix, olderThanSeconds)` abandons STARTED job and step executions a killed pod left behind, so the same-identity relaunch does not throw `JobExecutionAlreadyRunning` |
| `LiquibaseLockAutoConfiguration` + `StaleChangelogLockReleaser` | A-80: an auto-configuration (ordered before Boot's Liquibase autoconfig, matched by class NAME so this library carries no Liquibase dependency) that, before the bean named `liquibase` initialises, breaks a changelog lock older than a TTL, aged by the database clock. A missing lock table or a failed probe never blocks startup |
| `ExchangeAutoConfiguration` + `ExchangeProperties` + `ExchangeBootstrap` | SCRUM-42: binds `dcre.exchange.*`, exposes the `ExchangeLayout` kernel from `platform-files`, and creates every leaf directory at startup (idempotent, fail-closed). Backs off unless `dcre.exchange.enabled=true`, which only the shipped `dcre-exchange-layout.yml` sets; deliberately not keyed on `dcre.exchange.root`, because AGT exports `DCRE_EXCHANGE_ROOT` to every stage pod and relaxed binding would activate it fleet-wide (caught live 2026-07-14) |
| `dcre-exchange-layout.yml` | Shared classpath resource: 3 clients (FNBCC01, FNBCC02, FNBRF01) x 9 channels (5 collections + 4 mandates) x 3 subs = 81 leaf directories |
| `PartitionSizer` | R-41: `partitions(max)` = clamp(`availableProcessors()`, 1, max); cgroup-aware, so it reflects the pod CPU limit |

The two auto-configurations registered in `META-INF/spring/...AutoConfiguration.imports` are
`ExchangeAutoConfiguration` and `LiquibaseLockAutoConfiguration`. Everything else is opted into
explicitly by the consumer.

## Consumers

Counted from each fleet repo's `build.gradle` on `origin/dev` (local clones, not fetched), plus
AGT (checked 2026-09-28):

| Consumer | Version |
|---|---|
| Every collections stage: `cde` `cir` `cix` `cpx` `crg` `crr` `crw` `csx` `ctv` | `0.1.0` |
| Every payments stage: `pai` `pir` `pix` `ppx` `prg` `prr` `prw` `psx` `ptv` | `0.1.0` |
| Every mandates stage: `mas` `mir` `mit` `mix` `mpx` `mrg` `mrr` `mrv` `mrw` `msx` | `0.1.0` |
| Shared: `hcs`, `rpt` | `0.1.0` |
| AGT (Quarkus) | not a consumer |

`dcre-exchange-layout.yml` is imported (`spring.config.import`) by `cir`, `crg`, `crw`, `mir`,
`mrg`, `pir`, `prg` and `prw`. Every consumer except `rpt` references `BatchJdbcConfig` or
`HeartbeatDatasourceConfig` in `src/main`.

## Architecture and principles

- **SOLID, single responsibility per unit**: each class owns one operational concern (repository
  wiring, restart-safe DAO, heartbeat, outcome seam, exit-code transport, retry, metadata sweep,
  lock release, directory bootstrap, partition sizing), each small enough to test in isolation.
- **Opt-in over auto-activation**: `BatchJdbcConfig` and `HeartbeatDatasourceConfig` are
  `@Import`-only and must not fall under a consumer's component scan, because a scanned
  `@EnableBatchProcessing` or a second `DataSource` bean would silently reshape the consumer's
  context. Only the two conditional auto-configurations above activate on their own.
- **12FactorApp Alignment - https://12factor.net/**: config strictly from the environment, with
  committed dev defaults so a clean clone boots; stateless one-shot consumers; dev/prod parity
  (the same shipped yml drives local and in-cluster layouts). Spring Boot, Spring Batch, `spring-tx`,
  `spring-jdbc`, `spring-boot-jdbc` and `slf4j-api` are `compileOnly`: the consumer's runtime
  provides them.
- **Idempotent restart semantics**: `ExchangeBootstrap` is a no-op on existing directories;
  `StaleExecutionSweeper` and `StaleChangelogLockReleaser` clear what a SIGKILLed pod leaves
  behind; `OutcomeFileWriter` writes atomically, so AGT treats absence as never-success;
  `CrdbRetryExceptionHandler` re-runs the whole tasklet in a fresh transaction (retry, never skip).
- **Fail closed on configuration**: an empty exchange client map and an unset or bare batch table
  prefix abort startup rather than binding a default that would cross-write a shared table (R-04).

## Prerequisites

- Java 25 (`.sdkmanrc`: `java=25-tem`; `build.gradle` sets `sourceCompatibility` /
  `targetCompatibility` 25)
- Gradle wrapper 9.5.1 (committed)
- `za.co.fnb.dcre:platform-files:0.1.0` in Maven Local (and transitively `platform-model:0.1.0`)
- Docker for the Testcontainers ITs (see Testing); the rest of the suite needs none

## Build and publish

Coordinates: `za.co.fnb.dcre:platform-batch:0.1.0` (binary and sources jar). Distribution is Maven
Local only; no remote repository is configured.

```bash
# the dependency chain first (once per machine)
(cd ../platform-model && ./gradlew publishToMavenLocal)
(cd ../platform-files && ./gradlew publishToMavenLocal)

./gradlew build publishToMavenLocal
```

Consume from a stage service with `mavenLocal()` in `repositories`:

```groovy
dependencies {
    implementation 'za.co.fnb.dcre:platform-batch:0.1.0'
}
```

```java
@SpringBootApplication
@Import({JdbcConfig.class, BatchJdbcConfig.class, HeartbeatDatasourceConfig.class})
public class XxxApplication {
    public static void main(String[] args) {
        ExitCodeMain.run(XxxApplication.class, args);
    }
}
```

`platform-files` is an `api` dependency, so `StagedWrite`, `ExchangeLayout` and (through its own
`api`) the `platform-model` types re-export to every consumer. After changing this library,
republish (or bump the version) and rebuild the consuming service images.

Bumping `springBatchVersion` in `gradle.properties` is not a routine bump: the note in that file
lists what must be re-read and re-run before `PortalSafeOverrideGuardTest` is moved.

## Configuration

This library reads configuration only through the classes above; the consumer supplies values in
its own `application.yml` or environment. Spring relaxed binding means any property below can also
be set by its environment-variable form, so this table documents what the library reads, not a
closed set of names.

| Property / env | Default | Read by | Purpose |
|---|---|---|---|
| `dcre.batch.table-prefix` | none (mandatory with `BatchJdbcConfig`) | `BatchProperties` | Per-service `<SVC>_BATCH_` metadata prefix. Replaces `spring.batch.jdbc.table-prefix`, which Boot 4.1 no longer binds |
| `dcre.batch.heartbeat-seconds` | `10` | `HeartbeatWriter` | Heartbeat interval |
| `dcre.agtops-db-url` | `jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable` | `HeartbeatDatasourceConfig` | Heartbeat target database |
| `dcre.agtops-db-user` / `dcre.agtops-db-password` | `root` / empty | `HeartbeatDatasourceConfig` | Dedicated agt_ops credentials, not the primary datasource's |
| `JOB_NAME` (env) | unset: heartbeat disabled, outcome file `local-<svc>-<id>` | `HeartbeatDatasourceConfig`, `OutcomeFileWriter` | K8s Job name, set by AGT |
| `HOSTNAME` (env) | local host name | `HeartbeatDatasourceConfig` | `owner_pod` stamp |
| `dcre.liquibase.release-stale-locks` | `true` | `LiquibaseLockAutoConfiguration` | Set `false` to disable lock release |
| `dcre.liquibase.lock-ttl` | `60s` | `LiquibaseLockAutoConfiguration` | Minimum lock age before it is broken |
| `spring.liquibase.database-change-log-lock-table` | `DATABASECHANGELOGLOCK` | `LiquibaseLockAutoConfiguration` | Which lock table to inspect |
| `dcre.exchange.enabled` | unset (`true` only in the shipped yml) | `ExchangeAutoConfiguration` | Activation marker |
| `dcre.exchange.root` / `DCRE_EXCHANGE_ROOT` | `../../../../../../infra/dcre-infra/exchange` (shipped yml, dev-relative) | `ExchangeProperties` | Exchange root; AGT exports `/exchange` to stage pods |
| `dcre.exchange.clients` | 3 clients in the shipped yml | `ExchangeProperties` | Per-client channel/sub map, bracketed keys (`"[FNBCC01]"`) to survive relaxed binding; fails fast if empty |

## Testing

```bash
./gradlew test
```

13 test classes, 59 `@Test` methods (JUnit 6, AssertJ; counted from `src/test` at HEAD).

- Container-free: `ExchangePropertiesTest` (shared yml binds all 81 leaves), `ExchangeAutoConfigurationTest`
  (back-off, activation, the `DCRE_EXCHANGE_ROOT` relaxed-binding regression), `ExchangeBootstrapTest`,
  `OutcomeSeamListenerTest`, `ExitCodeMainTest`, `PartitionSizerTest`, `PortalSafeOverrideGuardTest`.
- Testcontainers CockroachDB `cockroachdb/cockroach:v26.2.3` (Docker required): `BatchJdbcConfigIT`,
  `PortalSafeStepExecutionDaoIT`, `HeartbeatWriterIT`, `StaleChangelogLockReleaserIT`,
  `LiquibaseLockAutoConfigurationIT` (boots real Liquibase to prove the bean-name assumption).
- `ExitCodeMainForkIT` forks a real JVM (`ExitCodeMainForkHarness`) and asserts the process status
  is 78 / 1 / 0, because a seam unit test would pass while production still exited 1. It needs the
  test runtime classpath, which the Gradle `test` task injects as `dcre.fork.classpath`; run from an
  IDE without it, it skips.

## Related repositories

The complete, current list of live DCRE repositories (stage services, orchestrator, platform libraries, infra and tooling) lives in one place: the [DCRE design register README](https://github.com/sean-huni/dcre-design-register/blob/dev/README.md#repositories). Deprecated and archived repositories are deliberately absent from it. This README does not copy that list, so it cannot drift.

- Design register: https://github.com/sean-huni/dcre-design-register (start at `docs/specs/DESIGN-REGISTER.md`; the diagrams in `docs/diagrams/` are the specification)
