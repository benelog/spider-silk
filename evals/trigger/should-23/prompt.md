---
description: 'Should load the spider-silk skill.'
tags: [trigger, trigger-positive]
max_turns: 3
timeout_seconds: 180
allowed_tools: [Read, Glob, Grep, Skill]
---

spider-silk 앱에서 모든 /api/* 응답에 CORS 허용 헤더를 붙이고 싶어. 허용할 origin은 https://admin.example.com 하나야.
