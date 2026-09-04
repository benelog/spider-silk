# Spider Silk

## Design Principles

- **spider-silk-core covers the web tier only.**
  Routing, parameter extraction, JSON, and templates are the extent of core's territory.
  Transaction/DB helpers (`Transactions` and the like) belong in example-flashcard (`flashcard.service`), not in core.
  Do not add data-layer dependencies such as spring-jdbc to core's build.gradle.
  (When adding a new feature to core, if it falls outside the web tier — persistence, transactions, scheduling, and so on — propose the example module or a separate module instead.)
- **A module names what it is tied to.**
  `spider-silk-tomcat` and `spider-silk-undertow` are `WebServer` implementations for those servers; they depend on core, never the other way round.
  Core's default stays Jetty, and anything server-specific belongs in the matching module rather than behind a flag in core.
  Each server module carries its own copy of the acceptance tests, deliberately: they assert what core promises against that container.
- **Test-only code lives in spider-silk-test.**
  The `WebTest` harness is its own module, so core's jar carries no test code.
  Core's tests depend on it (`testImplementation project(':spider-silk-test')`); do not move it back into core.
- **No reflection.**
  There is no annotation scanning, no proxies, and no automatic binding in core.
  Do not make changes that break this principle.
- There is no DI container: `FlashcardContext` assembles the object graph by calling constructors directly.

## Documentation

- **Markdown is one sentence per line.**
  Do not wrap prose to a column width: a line ends where a sentence ends, however long it runs.
  A diff then shows the sentence that changed instead of every line a rewrap touched.
  Headings, tables, and code blocks are unaffected.
  The same rule applies to the AsciiDoc pages under `manual/`.
- **The manual lives in `manual/`, as an Antora component.**
  Pages are AsciiDoc under `manual/modules/ROOT/pages/`, one chapter per file, listed in `manual/modules/ROOT/nav.adoc`.
  A new chapter is a new page plus a `nav.adoc` entry, nested under one of the existing groups (`**`, not `*`); only Introduction and Installation sit at the top level.
  `README.md` stays a Quick Start: installation, hello world, and links into the site.
  Do not grow the README back into the manual.
- `notes/positioning.md` and `notes/decisions.md` stay Markdown; they are background, not the manual.
- **Diagrams are draw.io sources in `manual/diagrams/`, exported to SVG in `manual/modules/ROOT/images/`.**
  Edit the `.drawio` file and run `npm run diagrams` to regenerate the SVG; never edit an exported SVG by hand, and commit both files.
  The export runs the draw.io desktop app (`drawio` on the path, under `xvfb-run` when there is no display), and the snap build of it cannot read `/tmp`.
  A page embeds a diagram with a captioned `image::name.svg[alt,link=self]`, after the sentence that states what the diagram shows.
- **The prose style is a skill**: `.claude/skills/doc-tone/SKILL.md`.
  Lead with the conclusion, one idea per sentence, the register of a technical reference manual.
  It covers `manual/`, `README.md`, and `notes/` alike, and carries the before/after examples and the verification steps.

## Build / Verification

```bash
./gradlew build              # compiles every module + runs all tests
./gradlew publishToMavenLocal # verifies the GitHub Packages publication config
npm install && npm run docs   # builds the Antora site into build/site
```

- H2 DB file location for the example app: `~/db/spider-silk/flashcard`

## Issues and Commits

- **A commit message never references an issue.**
  No `#12`, no `Fixes #34`, no tracker URL: a commit message says what the change does and stops there.
  The link runs the other way — once the commit is pushed, comment its SHA on the related issue.
  The history then stays readable on its own if the issue tracker is ever replaced, and the mapping lives in the tracker, which is the thing a migration would carry across.
