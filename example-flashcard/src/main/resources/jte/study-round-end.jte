@import flashcard.service.StudySession

@param StudySession study

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Round Finished - Flashcard</title>
    <link rel="stylesheet" href="/style.css">
</head>
<body>
<h1><a href="/">Flashcard</a></h1>

<div class="card-box">
    <p>Round ${study.getRound()} finished!</p>
    <p class="muted">${study.getWrongCount()} missed cards remain.</p>
</div>

<form action="/study/retry" method="post" class="inline">
    <button type="submit" class="primary">Retry missed cards</button>
</form>
<form action="/study/finish" method="post" class="inline">
    <button type="submit">Done for today</button>
</form>
</body>
</html>
