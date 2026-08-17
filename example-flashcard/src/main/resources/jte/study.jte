@import flashcard.service.StudySession

@param StudySession study
@param String question
@param String answer
@param boolean flipped = false

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Study: ${study.getTitle()}</title>
    <link rel="stylesheet" href="/style.css">
</head>
<body>
<h1><a href="/">Flashcard</a></h1>

<p class="muted">
    <span>${study.getTitle()} · ${study.getPosition()} / ${study.getTotal()}</span>
    @if(study.isRetryRound())
    <span>· round ${study.getRound()} (retry)</span>
    @endif
    <span>· ${study.getDirection().getLabel()}</span>
</p>

<div class="card-box">
    @if(!flipped)
    <span>${question}</span>
    @else
    <span>${answer}</span>
    @endif
</div>

@if(!flipped)
<p>
    <a class="button primary" href="/study?flipped=true">Flip</a>
</p>
@endif

<!-- Grade yourself only after flipping -->
@if(flipped)
<form action="/study/answer" method="post">
    <button type="submit" name="correct" value="true" class="correct">Got it</button>
    <button type="submit" name="correct" value="false" class="wrong">Missed it</button>
</form>
@endif

<form action="/study/finish" method="post">
    <button type="submit">Stop</button>
</form>
</body>
</html>
