# Changelog

The version shows in Reel's ⚙ settings title. Newest first.

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
