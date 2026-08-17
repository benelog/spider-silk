@import flashcard.domain.Card
@import flashcard.domain.Deck

@param Deck deck
@param Card card
@param String tags

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Edit Card - Flashcard</title>
    <link rel="stylesheet" href="/style.css">
</head>
<body>
<h1><a href="/">Flashcard</a></h1>

<h2>Edit Card (${deck.name()})</h2>

<form action="/decks/${deck.id()}/cards/${card.id()}/edit" method="post">
    <p><input type="text" name="text" value="${card.text()}" required></p>
    <p><input type="text" name="meaning" value="${card.meaning()}" required></p>
    <p><input type="text" name="tags" value="${tags}" placeholder="Tags (comma-separated)"></p>
    <button type="submit" class="primary">Save</button>
    <a class="button" href="/decks/${deck.id()}">Cancel</a>
</form>
</body>
</html>
