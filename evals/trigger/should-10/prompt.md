---
description: 'Should load the spider-silk skill.'
tags: [trigger, trigger-positive]
max_turns: 3
timeout_seconds: 180
allowed_tools: [Read, Glob, Grep, Skill]
---

req.session().get("user", User.class) returns null right after login in my spider silk handler even though I call set in /login — why?
