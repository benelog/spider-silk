@import flashcard.service.StatsService.StatsView

@param StatsView stats

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Stats - Flashcard</title>
    <link rel="stylesheet" href="/style.css">
</head>
<body>
<h1><a href="/">Flashcard</a></h1>
<nav><a href="/">Home</a> <a href="/stats">Stats</a> <a href="/_routes">Routes</a></nav>

<div class="tile-row">
    <div class="tile">
        <div>Study streak</div>
        <div class="count">${stats.streakDays()} days</div>
    </div>
    <div class="tile">
        <div>Total reviews</div>
        <div class="count">${stats.totalReviews()}</div>
    </div>
    <div class="tile">
        <div>Accuracy</div>
        <div class="count">${stats.accuracyPercent()}%</div>
    </div>
</div>

<h2>Last 30 Days</h2>
<p class="muted">Blue is correct, red is wrong.</p>
<div class="chart">
    @for(var day : stats.dailyStats())
    <div class="day"
         title="${day.studyDate().toString()}: correct ${day.correctCount()} · wrong ${day.wrongCount()}">
        @if(stats.maxDailyTotal() > 0)
        <div class="bar-wrong" style="height: ${day.wrongCount() * 120 / stats.maxDailyTotal()}px"></div>
        <div class="bar-correct" style="height: ${day.correctCount() * 120 / stats.maxDailyTotal()}px"></div>
        @endif
    </div>
    @endfor
</div>

<h2>Per-Deck Performance</h2>
<table class="stats">
    <thead>
    <tr>
        <th>Deck</th>
        <th>Reviews</th>
        <th>Accuracy</th>
    </tr>
    </thead>
    <tbody>
    @for(var deckStat : stats.deckStats())
    <tr>
        <td>${deckStat.deckName()}</td>
        <td>${deckStat.totalCount()}</td>
        <td>${deckStat.accuracyPercent()}%</td>
    </tr>
    @endfor
    </tbody>
</table>
</body>
</html>
