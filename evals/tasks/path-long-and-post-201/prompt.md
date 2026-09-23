---
description: 'Handlers that read the id with pathParamLong, parse the body with req.bodyJson(reader) through a hand-written JsonReader, and answer the POST with .status(HttpStatus.CREATED).'
tags: [task]
max_turns: 30
timeout_seconds: 600
allowed_tools: [Read, Glob, Grep, Skill]
append_system_prompt: 'The user''s project is not available in this workspace. Where the request refers to existing code, assume it exists as described and reply with the complete code and build-file lines to add or change, rather than asking for the files.'
---

In our spider-silk app (DeckRoutes.java registers everything on `app`), add GET /api/decks/{deckId} and POST /api/decks. The id is a number and a non-numeric id should come back as 400, not 500. POST takes {"name": "..."} and should answer 201 with the created deck. DeckService already has find(long) and create(String).
