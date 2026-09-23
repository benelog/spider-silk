---
description: 'Session writes and reads through req.session() (set, get with User.class, invalidate), and a beforeRoute or beforeRequest filter on /admin/* that returns a redirect or null.'
tags: [task]
max_turns: 30
timeout_seconds: 600
allowed_tools: [Read, Glob, Grep, Skill]
append_system_prompt: 'The user''s project is not available in this workspace. Where the request refers to existing code, assume it exists as described and reply with the complete code and build-file lines to add or change, rather than asking for the files.'
---

add login to my spider silk app: POST /login checks the password with UserService.authenticate(name, pw) and remembers the user, everything under /admin must redirect to /login when nobody is logged in, and POST /logout ends the session. users are a User record.
