---
description: 'The header moves to app.responseFilter(...), with the reason that an after-filter only sees routes that returned normally.'
tags: [task]
max_turns: 30
timeout_seconds: 600
allowed_tools: [Read, Glob, Grep, Skill]
append_system_prompt: 'The user''s project is not available in this workspace. Where the request refers to existing code, assume it exists as described and reply with the complete code and build-file lines to add or change, rather than asking for the files.'
---

Every response from our Spider Silk service needs an X-Request-Id header, including 404s, error pages, and static files. I tried app.afterRoute((req, res) -> res.header("X-Request-Id", id())) but the 404s don't have it. Fix it.
