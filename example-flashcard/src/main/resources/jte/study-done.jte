@import flashcard.service.StudySession

@param StudySession study

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Study Complete - Flashcard</title>
    <link rel="stylesheet" href="/style.css">
</head>
<body>
<h1><a href="/">Flashcard</a></h1>

<div class="card-box">
    <p>You finished studying ${study.getTitle()}! 🎉</p>
    @if(study.isRetryRound())
    <p class="muted">Cleared everything in ${study.getRound()} rounds.</p>
    @endif
</div>

<form action="/study/finish" method="post" class="inline">
    <button type="submit" class="primary">Home</button>
</form>
<a class="button" href="/stats">View stats</a>
</body>
</html>
