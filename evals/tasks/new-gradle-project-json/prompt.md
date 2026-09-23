---
description: 'A Gradle build depending on net.benelog.spidersilk:spider-silk-core from Maven Central, and a Main that registers both routes with lambdas and writes the JSON through the framework''s own Json API or a JsonWriter, with no reflective JSON library.'
tags: [task]
max_turns: 30
timeout_seconds: 600
allowed_tools: [Read, Glob, Grep, Skill]
append_system_prompt: 'The user''s project is not available in this workspace. Where the request refers to existing code, assume it exists as described and reply with the complete code and build-file lines to add or change, rather than asking for the files.'
---

I want to try Spider Silk for a tiny side project. Set up a fresh Gradle project in ./decks-api with a Main class that serves GET /api/decks as a JSON array of decks (id and name, just hardcode two of them) and GET /health. Keep it minimal, Java 21.
