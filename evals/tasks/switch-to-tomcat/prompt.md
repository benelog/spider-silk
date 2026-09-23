---
description: 'Add spider-silk-tomcat, optionally exclude the Jetty group, and set app.server((app, port) -> new TomcatServer(app).port(port)).'
tags: [task]
max_turns: 30
timeout_seconds: 600
allowed_tools: [Read, Glob, Grep, Skill]
append_system_prompt: 'The user''s project is not available in this workspace. Where the request refers to existing code, assume it exists as described and reply with the complete code and build-file lines to add or change, rather than asking for the files.'
---

Ops says our servers must run Tomcat, not Jetty. We use Spider Silk 1.1.0 with Gradle. What do I change so the same App runs on embedded Tomcat? Don't pull in anything we don't need.
