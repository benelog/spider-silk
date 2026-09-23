---
description: 'An end-to-end test with WebTest.test(app, client -> ...) and a unit test built from TestRequest, both from spider-silk-test, with no mock library.'
tags: [task]
max_turns: 30
timeout_seconds: 600
allowed_tools: [Read, Glob, Grep, Skill]
append_system_prompt: 'The user''s project is not available in this workspace. Where the request refers to existing code, assume it exists as described and reply with the complete code and build-file lines to add or change, rather than asking for the files.'
---

Write tests for the POST /api/decks endpoint in our spider-silk project: one end-to-end test through HTTP and one that calls DeckController.createDeck directly. We use JUnit 5 and AssertJ. No Spring in this project.
