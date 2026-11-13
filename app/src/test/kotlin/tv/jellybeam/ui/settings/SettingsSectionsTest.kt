package tv.jellybeam.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins [settingsSectionOrder]'s section list and order against silent drift. */
class SettingsSectionsTest {

    @Test
    fun `rail order is Home, Appearance, Library, Playback, Audio, OSD, Subtitles, Discover, Troubleshooting, About`() {
        // docs/09: Appearance sits directly under Home.
        // docs/14-seerr-discover.md: Discover sits before Troubleshooting/About; its config lives
        // outside the Settings record. docs/21-user-reporting.md §6: Troubleshooting sits between
        // Discover and About. Audio (profiles) sits right under Playback.
        assertEquals(
            listOf(
                SettingsSection.HOME,
                SettingsSection.APPEARANCE,
                SettingsSection.LIBRARY,
                SettingsSection.PLAYBACK,
                SettingsSection.AUDIO,
                SettingsSection.OSD,
                SettingsSection.SUBTITLES,
                SettingsSection.DISCOVER,
                SettingsSection.TROUBLESHOOTING,
                SettingsSection.UPDATES,
                SettingsSection.ABOUT,
            ),
            settingsSectionOrder(),
        )
    }

    @Test
    fun `Home is the seed section SettingsScreen focuses on first mount`() {
        assertEquals(SettingsSection.HOME, settingsSectionOrder().first())
    }
}
