package dev.cued.core.voice

/**
 * Turns a transcribed utterance into a command. Plain pattern matching, no
 * model: the phone's speech recogniser does the listening (offline language
 * packs work), this file decides what the words mean.
 *
 * Understood shapes (case-insensitive, leading "hey cued"/"cued" optional):
 *   play | resume | pause | stop | next | skip | previous | back
 *   shuffle [on|off] | repeat [off|all|one]
 *   play <something>           -> search titles/artists
 *   play playlist <name>       -> a playlist
 *   play genre <name> / play some <genre>
 *   play artist <name>
 *   play favourites | trending | recommended | new | unplayed | forgotten
 *   play something / anything / some music
 *   car mode on|off, volume up|down, what's playing
 */
object VoiceCommands {

    sealed class Command {
        data object Resume : Command()
        data object Pause : Command()
        data object Next : Command()
        data object Previous : Command()
        data class Shuffle(val on: Boolean?) : Command()
        data class Repeat(val mode: String) : Command() // off | all | one
        data class CarMode(val on: Boolean) : Command()
        data class Volume(val up: Boolean) : Command()
        data object WhatsPlaying : Command()
        data class Play(val target: Target) : Command()
        data class Unknown(val text: String) : Command()
    }

    sealed class Target {
        data object Anything : Target()
        data class SmartList(val name: String) : Target()   // TRENDING, NEW, UNPLAYED, FORGOTTEN, FAVOURITES, RECOMMENDED
        data class Playlist(val name: String) : Target()
        data class Genre(val name: String) : Target()
        data class Artist(val name: String) : Target()
        data class Search(val query: String) : Target()
    }

    private val smartWords = mapOf(
        "favourites" to "FAVOURITES", "favorites" to "FAVOURITES", "my favourites" to "FAVOURITES", "my favorites" to "FAVOURITES", "liked songs" to "FAVOURITES",
        "trending" to "TRENDING", "what's trending" to "TRENDING", "popular" to "TRENDING",
        "recommended" to "RECOMMENDED", "recommendations" to "RECOMMENDED", "suggestions" to "RECOMMENDED",
        "new" to "NEW", "new music" to "NEW", "new downloads" to "NEW", "newly downloaded" to "NEW", "latest" to "NEW",
        "unplayed" to "UNPLAYED", "something new" to "UNPLAYED", "something i haven't heard" to "UNPLAYED",
        "forgotten" to "FORGOTTEN", "forgotten tracks" to "FORGOTTEN", "old favourites" to "FORGOTTEN",
    )
    private val anythingWords = setOf("", "something", "anything", "music", "some music", "a song", "songs", "whatever", "random")

    fun parse(input: String): Command {
        var t = input.trim().lowercase().replace(Regex("[.,!?]"), " ").replace(Regex("\\s+"), " ").trim()
        t = t.removePrefix("hey cued").removePrefix("ok cued").removePrefix("cued").removePrefix("please").trim()
        if (t.isEmpty()) return Command.Unknown(input)

        when (t) {
            "pause", "pause music", "pause the music", "stop", "stop music", "stop playing", "hold on" -> return Command.Pause
            "play", "resume", "continue", "unpause", "keep playing", "go" -> return Command.Resume
            "next", "skip", "next song", "next track", "skip this", "skip song", "skip track" -> return Command.Next
            "previous", "back", "go back", "previous song", "previous track", "last song", "last track" -> return Command.Previous
            "shuffle", "shuffle on", "turn on shuffle", "enable shuffle" -> return Command.Shuffle(true)
            "shuffle off", "turn off shuffle", "disable shuffle", "stop shuffling" -> return Command.Shuffle(false)
            "repeat", "repeat all", "repeat on", "loop" -> return Command.Repeat("all")
            "repeat one", "repeat this", "repeat this song", "loop this" -> return Command.Repeat("one")
            "repeat off", "no repeat", "stop repeating", "loop off" -> return Command.Repeat("off")
            "car mode", "car mode on", "enter car mode", "driving mode", "driving mode on" -> return Command.CarMode(true)
            "car mode off", "exit car mode", "leave car mode", "driving mode off" -> return Command.CarMode(false)
            "volume up", "louder", "turn it up" -> return Command.Volume(true)
            "volume down", "quieter", "turn it down" -> return Command.Volume(false)
            "what's playing", "what is playing", "what's this", "what is this", "what song is this", "who is this" -> return Command.WhatsPlaying
        }

        val playMatch = Regex("^(?:play|put on|start|listen to|i want to hear|i wanna hear)\\s*(.*)$").find(t)
        if (playMatch != null) return Command.Play(target(playMatch.groupValues[1].trim()))
        if (t.startsWith("shuffle ")) return Command.Play(target(t.removePrefix("shuffle ").trim()))
        return Command.Unknown(input)
    }

    private fun target(raw: String): Target {
        var s = raw.removePrefix("me ").removePrefix("some ").trim()
        s = s.removePrefix("the ").trim()
        if (s in anythingWords || raw in anythingWords) return Target.Anything
        smartWords[s]?.let { return Target.SmartList(it) }
        smartWords[raw]?.let { return Target.SmartList(it) }
        Regex("^(?:my )?playlist (.+)$").find(s)?.let { return Target.Playlist(it.groupValues[1].trim()) }
        Regex("^(.+) playlist$").find(s)?.let { return Target.Playlist(it.groupValues[1].removePrefix("my ").trim()) }
        Regex("^(?:genre|some genre|the genre) (.+)$").find(s)?.let { return Target.Genre(it.groupValues[1].trim()) }
        Regex("^(.+) (?:music|tracks|songs|tunes)$").find(s)?.let { return Target.Genre(it.groupValues[1].trim()) }
        Regex("^(?:artist|songs by|tracks by|music by|something by|anything by) (.+)$").find(s)?.let { return Target.Artist(it.groupValues[1].trim()) }
        Regex("^(.+) by (.+)$").find(s)?.let { return Target.Search("${it.groupValues[1].trim()} ${it.groupValues[2].trim()}") }
        return Target.Search(s)
    }
}
