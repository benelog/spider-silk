# Plan

What is still open in the work that falls out of [docs/positioning.md](docs/positioning.md), each item with the condition that would make it worth doing.

Every item that was once on this list has shipped, apart from one that was rejected as a decision.
The reasoning behind each of them is the [design decision log](docs/decisions.md), which is where a question that starts with "why does it work this way" belongs.
What is left here is what was deliberately deferred, each with the condition that would make it worth doing.

## Deferred

Nothing below is a gap yet.
Each one was left out with a reason, and each names what would have to happen first.

- **Pre-compressed static variants (`.gz`/`.br`) and an external-directory location** — [decision 9](docs/decisions.md#9-static-file-caching).
  Both are real; neither is needed until someone deploys behind something that is not already doing it.
- **Route description overloads on the registration methods** — [decision 13](docs/decisions.md#13-route-introspection-the-differentiator).
  Purely additive if the need ever proves real, and a description attached at the registration site is an annotation with the reflection taken out — so the bar is a concrete need, not a nicer OpenAPI document.
- **A public record for "which guard covers this path"** — [decision 13](docs/decisions.md#13-route-introspection-the-differentiator).
  `routes()` lists routes only, not filters or error handlers.
  Nice-to-have, and additive later.
- **The OpenAPI export as a fourth module** — [decision 13](docs/decisions.md#13-route-introspection-the-differentiator).
  It lives in the example for now, because a spec format's version drift is not core's to own.
  If it earns its keep, it becomes a module the way `spider-silk-test` did.
- **A `spider-silk-ws` module** — [decision 15b](docs/decisions.md#15b-websocket-in-core--rejected).
  WebSocket stays out of core for good.
  If the `customizeContext` recipe turns out to be worth wrapping, it is wrapped in a module whose name states that it is Jetty-only.
  `spider-silk-tomcat` is the precedent for that shape — [decision 22](docs/decisions.md#22-spider-silk-tomcat-with-jetty-still-the-default).
