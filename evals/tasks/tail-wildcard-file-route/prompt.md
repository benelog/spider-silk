---
description: 'Either a /files/{path*} route reading req.pathParam("path"), checking the path stays under the root and throwing HttpException(NOT_FOUND) before WebResponse.file(path), or StaticFiles.directory(Path.of("/srv/shared")).hostedPath("/files"), which does all three.'
tags: [task]
max_turns: 30
timeout_seconds: 600
allowed_tools: [Read, Glob, Grep, Skill]
append_system_prompt: 'The user''s project is not available in this workspace. Where the request refers to existing code, assume it exists as described and reply with the complete code and build-file lines to add or change, rather than asking for the files.'
---

I need a /files/{something} route in my spider silk app that serves anything under /srv/shared, including nested folders like /files/2024/reports/q1.pdf. Missing files should be a 404 not a 500.
