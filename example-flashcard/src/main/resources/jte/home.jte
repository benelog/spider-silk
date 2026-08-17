@import java.util.List
@import flashcard.domain.DeckSummary
@import flashcard.domain.SmartDeck
@import flashcard.service.StudyDirection

@param long todayCount
@param long oftenWrongCount
@param long staleCount
@param List<DeckSummary> decks
@param List<SmartDeck> smartDecks
@param StudyDirection[] directions
@param String message = null
@param String error = null

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Flashcard</title>
    <link rel="stylesheet" href="/style.css">
</head>
<body>
<h1><a href="/">Flashcard</a></h1>
<nav><a href="/">Home</a> <a href="/stats">Stats</a> <a href="/_routes">Routes</a></nav>

@if(message != null)
<p class="notice">${message}</p>
@endif
@if(error != null)
<p class="error">${error}</p>
@endif

<div class="tile-row">
    <div class="tile">
        <div>Today's review</div>
        <div class="count">${todayCount} cards</div>
        @if(todayCount > 0)
        <form action="/study/today" method="post">
            <select name="direction">
                @for(var dir : directions)
                <option value="${dir.name()}">${dir.getLabel()}</option>
                @endfor
            </select>
            <button type="submit" class="primary">Start review</button>
        </form>
        @else
        <p class="muted">No cards are due today.</p>
        @endif
    </div>
</div>

<h2>Recommended Review</h2>
<div class="tile-row">
    <div class="tile">
        <div>Often missed cards</div>
        <div class="count">${oftenWrongCount} cards</div>
        @if(oftenWrongCount > 0)
        <form action="/study/preset/OFTEN_WRONG" method="post">
            <select name="direction">
                @for(var dir : directions)
                <option value="${dir.name()}">${dir.getLabel()}</option>
                @endfor
            </select>
            <button type="submit">Study</button>
        </form>
        @endif
    </div>
    <div class="tile">
        <div>Not reviewed lately</div>
        <div class="count">${staleCount} cards</div>
        @if(staleCount > 0)
        <form action="/study/preset/STALE" method="post">
            <select name="direction">
                @for(var dir : directions)
                <option value="${dir.name()}">${dir.getLabel()}</option>
                @endfor
            </select>
            <button type="submit">Study</button>
        </form>
        @endif
    </div>
</div>

<h2>My Decks</h2>
@if(decks.isEmpty())
<p class="muted">No decks yet. Create your first one.</p>
@endif
<ul class="plain">
    @for(var deck : decks)
    <li>
        <a href="/decks/${deck.id()}">${deck.name()}</a>
        <span class="muted">${deck.cardCount()} cards · ${deck.dueCount()} due today</span>
    </li>
    @endfor
</ul>

<form action="/decks" method="post">
    <input type="text" name="name" placeholder="New deck name" required>
    <button type="submit" class="primary">Create deck</button>
</form>

<h2>Smart Decks</h2>
<p class="muted">Instead of storing cards, a smart deck picks them by condition on the fly.</p>
<ul class="plain">
    @for(var smartDeck : smartDecks)
    <li>
        <span>
            <strong>${smartDeck.name()}</strong>
            <span class="muted">${smartDeck.conditionType().getLabel()}</span>
            @if(smartDeck.param() != null && !smartDeck.param().isEmpty())
            <span class="muted">(${smartDeck.param()})</span>
            @endif
        </span>
        <span>
            <form class="inline" action="/study/smart/${smartDeck.id()}" method="post">
                <select name="direction">
                    @for(var dir : directions)
                    <option value="${dir.name()}">${dir.getLabel()}</option>
                    @endfor
                </select>
                <button type="submit">Study</button>
            </form>
            <form class="inline" action="/smart-decks/${smartDeck.id()}/delete" method="post">
                <button type="submit">Delete</button>
            </form>
        </span>
    </li>
    @endfor
</ul>

<form action="/smart-decks" method="post">
    <input type="text" name="name" placeholder="Smart deck name" required>
    <select name="condition">
        <option value="OFTEN_WRONG">Often missed cards</option>
        <option value="STALE">Not reviewed lately</option>
        <option value="TAGGED">Cards with a specific tag</option>
        <option value="RECENT">Recently added cards</option>
    </select>
    <input type="text" name="param" placeholder="Tag name or days (optional)">
    <button type="submit">Create smart deck</button>
</form>
</body>
</html>
