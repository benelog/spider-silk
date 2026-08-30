---
name: doc-tone
description: >-
  The prose style for this repository's documentation: lead with the conclusion, one idea per sentence,
  the register of a technical reference manual.
  Use this skill when writing or editing any prose under `manual/` (the Antora pages), `README.md`,
  or `notes/` (positioning.md, decisions.md) — a new chapter, a new section, a rewrite, or a paragraph
  added to an existing page. It governs how a sentence is built, not what the documentation says.
---

# Documentation tone

The target register is a technical reference manual: the
[JUnit](https://docs.junit.org/current/user-guide/) and
[Spring Framework](https://docs.spring.io/spring-framework/reference/overview.html) references are the model.
Declarative, present tense, the defined term first, no literary flourish.

## The rules

1. **Lead with the conclusion.**
   Every page opens with a one-sentence definition of its subject.
   Every section opens with the statement it exists to make.
   Reasons, caveats, and history follow that sentence; they never build up to it.
2. **One idea per sentence.**
   A sentence carrying a second, different point behind a dash or a semicolon becomes two sentences.
   A short parenthetical or a colon introducing a list is not a second point and may stay.
3. **Declarative present tense.**
   "A route maps an HTTP method and a path pattern to a handler."
   Not "What a route is, is …", not "you might think of a route as …".
4. **No literary inversion and no rhetorical setup.**
   Cut "as the last section explains", "does the latter", "what is left is".
   State it plainly, or drop it.
5. **"You" for actions, the subject for behaviour.**
   "You register a route with `get`." / "The router compares patterns segment by segment."
   This mostly does not fire in `notes/`, which is a rationale document rather than instructions — do not force it there.
6. **Keep every fact.**
   Each existing sentence carries one precise semantic.
   Rewrite sentence by sentence; never summarize a paragraph away and regenerate it.
7. **One sentence per line.**
   A line ends where a sentence ends, however long it runs (this is also a `CLAUDE.md` rule).
   The diff then shows the sentence that changed instead of every line a rewrap touched.
8. **English stays English.** The documentation is in English whatever language the request arrives in.

### The one exception to rule 2

A short list of parallel clauses in one sentence is fine when the clauses *are* the list:

> What runs is what the code says, stack traces stay short, and startup stays fast.

Rule 2 targets a sentence that buries a second, different point behind a dash or semicolon — not this.

## Before and after

Buried point, dash aside, forward reference:

> A route is a method, a path pattern, and a handler, registered as one statement: `get`, `post`, `put`, `patch`, `delete`, and — rarely, as the last section explains — `head` and `options`.

> A route maps an HTTP method and a path pattern to a handler, and registers as one statement.
> `get`, `post`, `put`, `patch`, and `delete` each register one.
> `head` and `options` are answered automatically, so they are rarely registered.

Conclusion last:

> An application that containerizes with Jib, precompiles its templates, and perhaps compiles to a native binary ends up restating the same packaging block in every build file.
> The `net.benelog.spidersilk` plugin carries that block, so the build states only what is the application's own.

> The `net.benelog.spidersilk` plugin carries the packaging conventions, so a build states only what is the application's own.
> An application that containerizes with Jib, precompiles its templates, and perhaps compiles to a native binary would otherwise restate the same block in every build file.

Two points on one semicolon:

> `app.routes()` reads back the same list the dispatcher walks, as data, with no reflection at all; Javalin needs a plugin for the equivalent.

> `app.routes()` reads back the same list the dispatcher walks, as data, with no reflection at all.
> Javalin needs a plugin for the equivalent.

Description-then-link, in a list of further reading:

> Where Spider Silk sits next to Javalin, Spark, Helidon SE, and Spring Boot, and what it trades away to get there: [notes/positioning.md](notes/positioning.md).

> [notes/positioning.md](notes/positioning.md) places Spider Silk next to Javalin, Spark, Helidon SE, and Spring Boot, and states what it trades away to get there.

## Never touched

- Code blocks, tables, headings, `:navtitle:` and other AsciiDoc attributes.
- `xref:` anchors and link targets. A heading is an anchor target: `## Rejected — decisions, with the reason`
  is linked as `#rejected--decisions-with-the-reason`, so its em-dash stays.
- In `notes/decisions.md`, the decision numbers and the cross-references between them ("decision 27's rule",
  "item 16's argument", "15b"). They are load-bearing, and splitting a compound sentence is the easiest way
  to orphan one.
- A deliberate tagline, such as `Thin by design, strong by types.`

## How to apply it

Edit with exact-match replacements that fail loudly on a miss, not by retyping a file:

```python
def apply(path, reps):
    s = open(path).read()
    for a, b in reps:
        if a not in s:
            raise SystemExit("MISS in %s: %r" % (path, a[:70]))
        s = s.replace(a, b, 1)
    open(path, 'w').write(s)
```

A full-file rewrite can silently corrupt a code block it retypes; a replacement that raises cannot.

**Read the whole file first.** `cat` and `sed` output is truncated by this machine's rtk hook without saying so,
so a page's tail can be missed entirely. Use the Read tool, or dump the prose to a scratch file and read that.

**Verify**, in this order:

```bash
npm run docs                                   # manual/ only: failure_level is warn, so a broken xref fails the build
grep -rn "—\|; " <files>                       # leftover old-style prose; hits inside tables are expected
diff <(git show HEAD:<file> | grep '^#') <(grep '^#' <file>)   # headings, and therefore anchors, unchanged
```

Commit per group of pages rather than in one sweep, so a broken anchor is easy to place.
