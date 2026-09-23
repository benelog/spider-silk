---
description: 'Iterate app.hooks() with a switch over the sealed Hook records, and app.routes() for the routes, printed after registration.'
tags: [task]
max_turns: 30
timeout_seconds: 600
allowed_tools: [Read, Glob, Grep, Skill]
append_system_prompt: 'The user''s project is not available in this workspace. Where the request refers to existing code, assume it exists as described and reply with the complete code and build-file lines to add or change, rather than asking for the files.'
---

Security review question for our spider-silk service: which filters cover /admin paths, and do any status pages exist? I don't want to read all of Routes.java. Can the app print that at startup?
