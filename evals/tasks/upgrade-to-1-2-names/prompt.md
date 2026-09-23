---
description: 'rawJson(String), Json.object(), statusPage(...), writeFailure(), and Hook/hooks(), each with the reason it was renamed or a pointer to it.'
tags: [task]
max_turns: 30
timeout_seconds: 600
allowed_tools: [Read, Glob, Grep, Skill]
append_system_prompt: 'The user''s project is not available in this workspace. Where the request refers to existing code, assume it exists as described and reply with the complete code and build-file lines to add or change, rather than asking for the files.'
---

After upgrading our Spider Silk app to 1.2.0 these lines stopped compiling: `WebResponse.json("{\"status\":\"up\"}")`, `Json.obj().put("n", 1)`, `app.error(HttpStatus.NOT_FOUND, h)`, `completion.failure()`, and `for (Guard g : app.guards())`. What are the new names?
