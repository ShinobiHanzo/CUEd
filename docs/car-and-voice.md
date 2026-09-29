# Car mode and voice control

## Car mode (in-app)

Open the sidebar (☰) and flip **Car mode**. The whole app becomes one screen:
oversized play/pause, previous/next, a big **Voice** button, the current track in
large type, and quick-play tiles for Favourites, Trending, Recommended, New and your
playlists. The screen stays on. The ✕ in the corner leaves car mode for this session.

**Enter car mode automatically** (also in the sidebar, on by default) switches in when
Android reports the car UI mode: a car dock, or a head unit that flips the phone into
car mode. That signal is not universal, which is why the manual toggle exists.

## Android Auto

`PlaybackService` is a Media3 `MediaLibraryService`, so an Android Auto head unit
shows CUEd as a media app with a browse tree:

```
CUEd
├── For you      Trending, Newly downloaded, Unplayed, Forgotten, Favourites, Recommended
├── Playlists
├── Genres
└── All tracks
```

Tapping a folder plays it; voice on the head unit ("play house on CUEd") goes through
the same search as the phone. Because CUEd is not on the Play Store, Android Auto only
lists it if you enable **Developer settings → Unknown sources** in the Android Auto app
on the phone. That is Google's rule for sideloaded media apps, not something we can
change from here.

## Voice

Three routes, one brain:

1. **In-app mic** (top bar, sidebar, or the big button in car mode). Uses the system
   speech recogniser through `RecognizerIntent` with offline preferred. If you have an
   offline language pack installed, nothing leaves the phone.
2. **Google Assistant**: "play *forgotten tracks* on CUEd". Arrives through the media
   session as a search request and is resolved the same way.
3. **Android Auto voice**: same path as Assistant.

Understanding is `core/.../voice/VoiceCommands.kt`: a few dozen phrasings mapped to
commands by string matching, unit-tested. No model, no cloud. The resolver in
`app/.../playback/VoiceResolver.kt` then matches against your library with substring
and token matching over titles, artists, genres and playlist names.

Examples that work:

| Say | Does |
|---|---|
| "play", "resume", "pause", "next", "skip", "back" | transport |
| "shuffle on/off", "repeat one/all/off" | modes |
| "play something", "play some music" | Recommended → Trending → shuffle all |
| "play my favourites", "play what's trending", "play forgotten tracks" | smart lists |
| "play playlist late night", "play my gym playlist" | playlists (fuzzy) |
| "play some house music", "play genre drum and bass" | genres |
| "play songs by daft punk" | artist |
| "play around the world by daft punk", "put on blue monday" | title search |
| "car mode", "exit car mode", "louder", "quieter", "what's playing" | misc |

In car mode replies are also spoken through the system TTS.
