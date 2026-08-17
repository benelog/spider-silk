# Spider Silk

## Design Principles

- **spider-silk-core covers the web tier only.** Routing, parameter extraction, JSON, and
  templates are the extent of core's territory. Transaction/DB helpers (`Transactions` and
  the like) belong in example-flashcard (`flashcard.service`), not in core.
  Do not add data-layer dependencies such as spring-jdbc to core's build.gradle.
  (When adding a new feature to core, if it falls outside the web tier — persistence,
  transactions, scheduling, and so on — propose the example module or a separate module
  instead.)
- **Test-only code lives in spider-silk-test.** The `WebTest` harness is its own module, so
  core's jar carries no test code. Core's tests depend on it
  (`testImplementation project(':spider-silk-test')`); do not move it back into core.
- **No reflection.** There is no annotation scanning, no proxies, and no automatic binding
  in core. Do not make changes that break this principle.
- There is no DI container: `FlashcardContext` assembles the object graph by calling
  constructors directly.

## Build / Verification

```bash
./gradlew build   # compiles all three modules + runs all tests
```

- H2 DB file location for the example app: `~/db/spider-silk/flashcard`
