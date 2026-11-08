# Changelog

The version shows in Reel's ⚙ settings title. Newest first.

## 1.2 — 2026-11-08

### Added

- **Send what you watch to Trakt**: what Netflix, Prime Video, Pluto TV, Tubi and the other streaming apps play goes into your Trakt history (80% or more) or Continue watching (less). Uses the same notification access as YouTube watch tracking. ⚙ → Trakt turns it on or off and shows the last thing sent.
  - Prime Video titles are looked up by their catalog id on primevideo.com; episodes are matched on Trakt by name, since Prime and Trakt number some differently.
  - When an app names only the episode ("Pilot", "S24:E01") of a show you opened from Reel, it's read as that show's episode.
  - Netflix reports no title, so it only works for titles opened from Reel. For a show, Reel asks which episode you're about to watch (Trakt's next one first, or pick by season), then follows on episode by episode. ⚙ → Trakt → *Episode of … in Netflix* corrects it mid-episode.
  - Previews, "up next" cards on Tubi's home screen, plot descriptions and anything under 3 minutes of play don't count.

### Changed

- ⚙ → Trakt opens a small menu (Netflix episode, sending to Trakt, unlink) once linked, instead of unlinking straight away.
- Opening a show in Netflix asks which episode first, when sending to Trakt is on.

## 1.1 — 2026-10-31

### Added

- **YouTube row** under Apps: for each channel you subscribe to, the newest video you haven't watched from the last two weeks, Shorts left out. Needs a Google sign-in (⚙ → Link YouTube; README section 3c).
- **Watch tracking**: Reel sees what the YouTube app plays (title, channel, play time) and drops a video from the row once you've played most of it. Needs notification access, granted once from adb. Without it, opening a video from the row counts as watching it.
- Long-press a card for **Mark watched**, **More from [channel]** and **Watch [channel] in order…**.
- **Watch in order**: pick the first video you haven't seen and that channel's card shows the oldest unwatched one from there, however old, with "+N more" for the rest. Long-press to start somewhere else or stop.
- **Channel page** (More from…): the channel's last 50 uploads, watched ones dimmed with ✓. Long-press for Mark watched / unwatched, Mark watched up to here, and Watch in order from here.
- **Row ⚙**, shown when you press Left on the first card: hide the row, sort, thumbnail size (Small, Medium, Large), refresh, watch tracking, link or unlink YouTube.
- **Sort**: Most watched channels first (your TV watch time, then YouTube's own "most relevant" order), Newest uploads first, or Watch in order channels first (in-order channels, then the rest, each newest first).
- Reel settings: Link / Unlink YouTube, and YouTube row shown / hidden.

### Changed

- The top of the home screen scrolls so the focused row is always on screen, since bigger thumbnails can push Genres below the fold. Down from Genres collapses the rows above before stepping into the posters.
- The app version is shown in the ⚙ settings title.

## 1.0 — 2026-10-25

First version.

- Home screen with an **Apps row** (long-press to Move or Hide) and a **genre browser** that only shows titles streaming on the apps you have installed, from TMDB and JustWatch.
- Rows for **Trending**, **All**, TMDB's genres, 70+ niche genres or any genre you type, and **Recent** (what you opened from Reel).
- **Trakt**: Watchlist, For you, and **Continue watching** with where to pick up.
- **Search** across movies and shows, a **Movies / Shows** toggle, and **sort** (Popular, Top rated, Newest).
- **Title panel** before launching: details, cast, which of your apps include it, rent or buy, trailer. Opens the title's own page in the app where JustWatch knows it.
- **Rent, buy & unavailable titles: shown / hidden** setting.
- Greeting with rotating movie quotes, weather, and disk caching for a fast start.
