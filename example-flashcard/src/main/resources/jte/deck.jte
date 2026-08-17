@import java.util.List
@import flashcard.domain.CardWithTags
@import flashcard.domain.Deck
@import flashcard.service.StudyDirection

@param Deck deck
@param List<CardWithTags> cards
@param StudyDirection[] directions
@param String message = null
@param String error = null

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>${deck.name()} - Flashcard</title>
    <link rel="stylesheet" href="/style.css">
</head>
<body>
<h1><a href="/">Flashcard</a></h1>
<nav><a href="/">Home</a> <a href="/stats">Stats</a></nav>

@if(message != null)
<p class="notice">${message}</p>
@endif
@if(error != null)
<p class="error">${error}</p>
@endif

<h2>${deck.name()}</h2>

<form action="/study/deck/${deck.id()}" method="post">
    <select name="direction">
        @for(var dir : directions)
        <option value="${dir.name()}">${dir.getLabel()}</option>
        @endfor
    </select>
    <button type="submit" class="primary">Start studying</button>
</form>

<h3>Cards (${cards.size()})</h3>
<ul class="plain">
    @for(var cardWithTags : cards)
    <li>
        <span>
            <strong>${cardWithTags.card().text()}</strong>
            <span class="muted">${cardWithTags.card().meaning()}</span>
            @for(var tag : cardWithTags.tags())
            <span class="tag">${tag}</span>
            @endfor
        </span>
        <span>
            <a class="button"
               href="/decks/${deck.id()}/cards/${cardWithTags.card().id()}/edit">Edit</a>
            <form class="inline" method="post"
                  action="/decks/${deck.id()}/cards/${cardWithTags.card().id()}/delete">
                <button type="submit">Delete</button>
            </form>
        </span>
    </li>
    @endfor
</ul>

<h3>Add a card</h3>
<form action="/decks/${deck.id()}/cards" method="post">
    <input type="text" name="text" placeholder="Text" required>
    <input type="text" name="meaning" placeholder="Meaning" required>
    <input type="text" name="tags" placeholder="Tags (comma-separated)">
    <button type="submit" class="primary">Add</button>
</form>

<h3>CSV Import / Export</h3>
<form action="/decks/${deck.id()}/import" method="post" enctype="multipart/form-data">
    <input type="file" name="file" accept=".csv,text/csv" required>
    <button type="submit">Import CSV</button>
</form>
<p>
    <a class="button" href="/decks/${deck.id()}/export.csv">Export CSV</a>
</p>

<h3>Manage deck</h3>
<form action="/decks/${deck.id()}/rename" method="post">
    <input type="text" name="name" value="${deck.name()}" required>
    <button type="submit">Rename</button>
</form>
<form action="/decks/${deck.id()}/delete" method="post">
    <button type="submit">Delete deck</button>
</form>
</body>
</html>
