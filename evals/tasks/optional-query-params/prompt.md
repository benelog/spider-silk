---
description: 'Defaults through paramLong(name, default) or param(name, Integer::parseInt, default), and the optional string through paramOrNull("q"), with the reason the null default does not compile.'
tags: [task]
max_turns: 30
timeout_seconds: 600
allowed_tools: [Read, Glob, Grep, Skill]
append_system_prompt: 'The user''s project is not available in this workspace. Where the request refers to existing code, assume it exists as described and reply with the complete code and build-file lines to add or change, rather than asking for the files.'
---

GET /api/cards in my spider silk app should accept ?page= (default 1), ?size= (default 20), and an optional ?q= search term. If q is missing just list everything. I wrote req.param("q", null) and it doesn't compile??
