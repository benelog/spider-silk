---
description: 'WebResponse.jsonArray(sink -> ...) or WebResponse.ndjson(sink -> ...) writing one card at a time from eachCard, with the caveat that a failure after commitment cannot change the status.'
tags: [task]
max_turns: 30
timeout_seconds: 600
allowed_tools: [Read, Glob, Grep, Skill]
append_system_prompt: 'The user''s project is not available in this workspace. Where the request refers to existing code, assume it exists as described and reply with the complete code and build-file lines to add or change, rather than asking for the files.'
---

Our export endpoint GET /api/cards/export builds a List of 500k cards and returns WebResponse.json(cards, CARDS) and the heap blows up. How do I stream it with spider silk? CardService has eachCard(Consumer<Card>). Clients can take either a JSON array or NDJSON.
