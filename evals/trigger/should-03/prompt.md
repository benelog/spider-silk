---
description: 'Should load the spider-silk skill.'
tags: [trigger, trigger-positive]
max_turns: 3
timeout_seconds: 180
allowed_tools: [Read, Glob, Grep, Skill]
---

in this repo (see pom.xml, groupId net.benelog.spidersilk for the framework dep) the POST /import endpoint holds the whole upload in memory, make it stream the multipart file to /var/uploads instead
