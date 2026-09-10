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
  dev/prod parity (the same shipped yml drives local and in-cluster layouts). Spring Boot itself
  is `compileOnly`: the consumer's runtime provides it, keeping this library a pure convention
  carrier.
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

## Prerequisites

- JDK 25 (Gradle toolchain `languageVersion = 25`; wrapper is Gradle 9.5.1)
- `za.co.fnb.dcre:platform-files:0.1.0` (and transitively `platform-model:0.1.0`) published to
  Maven Local: this module resolves the platform chain from `mavenLocal()` only
- No Docker and no `.env` needed: the test suite is plain JUnit against temp directories

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

## Configuration

This is a library: it reads no environment itself except through the shipped
`dcre-exchange-layout.yml`, which a writer service opts into via
`spring.config.import: classpath:dcre-exchange-layout.yml`.

| Name | Default | Purpose |
|---|---|---|
| `DCRE_EXCHANGE_ROOT` (env) | `../../../../../infra/dcre-infra/exchange` (dev-relative, from the shipped yml) | Exchange root directory; AGT exports an absolute path to every stage pod |
| `dcre.exchange.enabled` | unset (`true` only in the shipped yml) | Activation marker for `ExchangeAutoConfiguration`; non-writer services carry no `dcre.exchange` config and the autoconfig backs off |
| `dcre.exchange.root` | `${DCRE_EXCHANGE_ROOT:...}` | Root path the client-relative directories resolve against |
| `dcre.exchange.clients` | 3 clients in the shipped yml | Per-client channel/sub directory map; startup fails fast if empty |

## Testing

```bash
./gradlew test
```

Eleven test classes, 51 tests (JUnit 6, AssertJ). Container-free: autoconfig back-off and
activation (`ApplicationContextRunner`, including the `DCRE_EXCHANGE_ROOT` relaxed-binding
regression), shared-yml binding of all 45 leaf directories, idempotent/fail-closed bootstrap,
partition clamping, the outcome seam, and the `ExitCodeMain` classification seam. Testcontainers
CockroachDB (Docker required): `BatchJdbcConfigIT`, `PortalSafeStepExecutionDaoIT`,
`HeartbeatWriterIT`.

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
