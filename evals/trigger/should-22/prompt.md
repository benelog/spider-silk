---
description: 'Should load the spider-silk skill.'
tags: [trigger, trigger-positive]
max_turns: 3
timeout_seconds: 180
allowed_tools: [Read, Glob, Grep, Skill]
---

Our example-flashcard app needs a POST /cards/{cardId}/delete that removes the card and redirects back to the deck page with a flash message saying it was deleted. How do I write that handler?
