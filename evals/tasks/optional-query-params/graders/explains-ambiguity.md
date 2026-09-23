---
type: 'llm'
---

PASS if the reply explains that req.param("q", null) does not compile because null matches both the (name, String default) overload and the (name, parser Function) overload, so the call is ambiguous. FAIL if it gives another reason, such as type inference or Optional, or gives none.
