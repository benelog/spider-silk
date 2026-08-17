@import java.util.List
@import spidersilk.Route

@param List<Route> routes

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Routes - Flashcard</title>
    <link rel="stylesheet" href="/style.css">
</head>
<body>
<h1><a href="/">Flashcard</a></h1>
<nav><a href="/">Home</a> <a href="/stats">Stats</a> <a href="/_routes">Routes</a></nav>

<h2>Routes</h2>
<p class="muted">${routes.size()} registered, read back from <code>app.routes()</code> with no
    annotation scanning. The order is registration order, which is also the order the router
    breaks ties in. The automatic HEAD and OPTIONS answers are missing because nobody
    registered them.</p>

<table class="stats">
    <thead>
    <tr>
        <th>Method</th>
        <th>Path</th>
    </tr>
    </thead>
    <tbody>
    @for(var route : routes)
    <tr>
        <td><span class="tag">${route.method()}</span></td>
        <td>${route.path()}</td>
    </tr>
    @endfor
    </tbody>
</table>

<p class="muted">The same list as an OpenAPI 3.1 document:
    <a href="/openapi.json">openapi.json</a>.</p>
</body>
</html>
