package dev.cued.core

import dev.cued.core.voice.VoiceCommands
import dev.cued.core.voice.VoiceCommands.Command
import dev.cued.core.voice.VoiceCommands.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceTest {
    @Test
    fun `transport commands`() {
        assertEquals(Command.Pause, VoiceCommands.parse("Pause."))
        assertEquals(Command.Next, VoiceCommands.parse("hey cued skip this"))
        assertEquals(Command.Previous, VoiceCommands.parse("go back"))
        assertEquals(Command.Resume, VoiceCommands.parse("play"))
        assertEquals(Command.Shuffle(false), VoiceCommands.parse("turn off shuffle"))
        assertEquals(Command.Repeat("one"), VoiceCommands.parse("repeat this song"))
        assertEquals(Command.CarMode(true), VoiceCommands.parse("driving mode"))
        assertEquals(Command.WhatsPlaying, VoiceCommands.parse("what song is this?"))
    }

    @Test
    fun `play targets`() {
        assertEquals(Command.Play(Target.Anything), VoiceCommands.parse("play something"))
        assertEquals(Command.Play(Target.Anything), VoiceCommands.parse("play some music"))
        assertEquals(Command.Play(Target.SmartList("FAVOURITES")), VoiceCommands.parse("play my favourites"))
        assertEquals(Command.Play(Target.SmartList("TRENDING")), VoiceCommands.parse("Play what's trending"))
        assertEquals(Command.Play(Target.Playlist("late night drive")), VoiceCommands.parse("play playlist late night drive"))
        assertEquals(Command.Play(Target.Playlist("gym")), VoiceCommands.parse("play my gym playlist"))
        assertEquals(Command.Play(Target.Genre("house")), VoiceCommands.parse("play some house music"))
        assertEquals(Command.Play(Target.Genre("drum and bass")), VoiceCommands.parse("play genre drum and bass"))
        assertEquals(Command.Play(Target.Artist("daft punk")), VoiceCommands.parse("play songs by daft punk"))
        assertEquals(Command.Play(Target.Search("around the world daft punk")), VoiceCommands.parse("play around the world by daft punk"))
        assertEquals(Command.Play(Target.Search("blue monday")), VoiceCommands.parse("put on blue monday"))
    }

    @Test
    fun `unknown stays unknown`() {
        assertTrue(VoiceCommands.parse("order a pizza") is Command.Unknown)
    }
}
