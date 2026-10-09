-- The json-post case: every request POSTs items.json, the 100 records the
-- json-list case answers with, and the server answers how many it read.
-- The report at the end is report.lua's.
dofile("report.lua")

local file = assert(io.open("items.json", "rb"))
local body = file:read("*a")
file:close()

wrk.method = "POST"
wrk.body = body
wrk.headers["Content-Type"] = "application/json"
