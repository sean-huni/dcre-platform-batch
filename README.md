# dcre-platform-batch

Spring Batch operational conventions shared by the DCRE Collections 3.0 pipeline
services (C-family and M-family, see the `dcre` umbrella repo): the AGT-facing job
outcome seam, JVM exit-code wiring, and self-healing of stranded Batch metadata.
Depends on `dcre-platform-files` (see Local module dependencies below); Spring Boot
itself is `compileOnly` and must come from the consumer.

## Key classes

- `OutcomeFileWriter` (SYNTHETIC-CONTRACT, R-35): the AGT-service business-verdict
  seam. Writes `<exchangeRoot>/outcomes/<jobName>` atomically via `StagedWrite`;
  AGT treats absence as never-success (R-33 arbiter clause).
- `ExitCodeMain` (R-34): exit-code wiring; `System.exit(SpringApplication.exit(...))`
  so the JVM exit code carries the Batch outcome to the K8s Job.
- `StaleExecutionSweeper` (A-39a): a killed pod strands `BATCH_JOB_EXECUTION` in
  STARTED and the relaunch throws `JobExecutionAlreadyRunning`. Each service
  self-heals its OWN metadata (single-writer, R-04) by abandoning stale STARTED
  job and step executions at startup, before the job launches; AGT never touches
  service schemas. Plain JDBC against the batch-metadata `DataSource`, using
  INTERVAL literals (CRDB-safe, see `dcre-platform-persistence`).

## Local module dependencies

| Module | Version | Scope | Used for |
|---|---|---|---|
| `dcre-platform-files` | 0.1.0 | `api` | `StagedWrite` backs `OutcomeFileWriter`'s atomic seam write; `api` scope re-exports files (and, through its own `api` on `dcre-platform-model`, the shared model types) to every consumer |

Resolves from Maven Local only (no remote repository): publish the chain in order,
`dcre-platform-model` -> `dcre-platform-files` -> this module, running
`./gradlew publishToMavenLocal` in each repo. Details in each module repo's README
under "Publishing".

## Publishing (how this module is made available for reuse)

This module is published as a Maven artifact via the Gradle `maven-publish` plugin
(see `build.gradle`) so other DCRE services can import it as a normal dependency.

Coordinates:

```
za.co.fnb.dcre:dcre-platform-batch:0.1.0
```

### How it was published

1. `build.gradle` applies `java-library` + `maven-publish`, sets
   `group = 'za.co.fnb.dcre'` and `version = '0.1.0'`, and declares a single
   `MavenPublication` from `components.java`. `withSourcesJar()` publishes a
   sources jar alongside the binary jar.
2. Publish to the local Maven repository (`~/.m2/repository`):

   ```bash
   ./gradlew publishToMavenLocal
   ```

3. This produces, under `~/.m2/repository/za/co/fnb/dcre/dcre-platform-batch/0.1.0/`:
   - `dcre-platform-batch-0.1.0.jar` (classes)
   - `dcre-platform-batch-0.1.0-sources.jar`
   - `dcre-platform-batch-0.1.0.pom` (Maven metadata)
   - `dcre-platform-batch-0.1.0.module` (Gradle module metadata)

There is currently no remote repository configured; distribution is Maven Local only.
Every consuming project is built on the same machine, so `publishToMavenLocal` is the
whole release step. When a shared artifact repository (e.g. Nexus/Artifactory) becomes
available, add it under `publishing.repositories` and publish with `./gradlew publish`.

### How to consume it from another project

1. Make sure the version you need exists locally (clone this repo at the matching
   commit and run `./gradlew publishToMavenLocal` if it does not). The `api`
   dependency chain (`dcre-platform-files:0.1.0`, `dcre-platform-model:0.1.0`) must
   be in Maven Local too.
2. In the consuming project's `build.gradle`, include `mavenLocal()` in the
   repositories and add the dependency:

   ```groovy
   repositories {
       mavenCentral()
       mavenLocal()
   }

   dependencies {
       implementation 'za.co.fnb.dcre:dcre-platform-batch:0.1.0'
   }
   ```

3. Note: `org.springframework.boot:spring-boot:4.1.0` is declared `compileOnly`
   here (used by `ExitCodeMain`), so the consuming service must provide Spring Boot
   itself; any Spring Boot 4 service already does.

### Releasing a new version

1. Bump `version` in `build.gradle` (SemVer; released versions are immutable, so any
   change after a release means a new version, never a re-publish of the same one).
2. Run the build: `./gradlew build` (this module currently has no tests of its own;
   its seam behavior is exercised through `dcre-platform-files`' `StagedWrite` tests
   and the consuming services).
3. Publish: `./gradlew publishToMavenLocal`.
4. Commit with the JIRA ticket in the title, then bump the dependency version in the
   consuming projects.

The sibling platform modules (`dcre-platform-model`, `dcre-platform-files`,
`dcre-platform-persistence`) follow the same publish/consume flow under the same
`za.co.fnb.dcre` group.
