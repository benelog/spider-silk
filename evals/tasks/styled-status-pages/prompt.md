---
description: 'Two statusPage registrations rendering templates by name without the .jte extension.'
tags: [task]
max_turns: 30
timeout_seconds: 600
allowed_tools: [Read, Glob, Grep, Skill]
append_system_prompt: 'The user''s project is not available in this workspace. Where the request refers to existing code, assume it exists as described and reply with the complete code and build-file lines to add or change, rather than asking for the files.'
---

Our spider-silk app shows the default plain-text 404. I want a styled not-found page using our jte template at src/main/resources/jte/not-found.jte that gets the requested path. Also make any uncaught 500 show jte/oops.jte.
