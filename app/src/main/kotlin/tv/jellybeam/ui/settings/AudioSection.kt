package tv.jellybeam.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.audio.AudioDsp
import tv.jellybeam.audio.AudioProfile
import tv.jellybeam.audio.AudioProfilesState
import tv.jellybeam.audio.CrossfeedParam
import tv.jellybeam.audio.DspStage
import tv.jellybeam.audio.HeadphoneVolume
import tv.jellybeam.audio.PROFILE_OUTPUTS
import tv.jellybeam.audio.addProfile
import tv.jellybeam.audio.addStage
import tv.jellybeam.audio.cutoffHz
import tv.jellybeam.audio.deleteProfile
import tv.jellybeam.audio.duplicateProfile
import tv.jellybeam.audio.moveStage
import tv.jellybeam.audio.nextProfileName
import tv.jellybeam.audio.removeStage
import tv.jellybeam.audio.replaceStage
import tv.jellybeam.audio.setDefault
import tv.jellybeam.audio.sideCutDb
import tv.jellybeam.audio.step
import tv.jellybeam.audio.stepGain
import tv.jellybeam.audio.updateProfile
import tv.jellybeam.player.AudioOutputKind
import tv.jellybeam.ui.focus.LocalFocusGate
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.requestFocusUntilSuccess
import tv.jellybeam.ui.nav.LocalDrawerLeftEdgeSuppressed

/** The editor's transient state; the profiles themselves live in [AudioDsp]. */
data class AudioEditorUi(val editingId: String? = null, val deleteConfirm: Boolean = false)

/** Settings › Audio: every edit goes straight to [AudioDsp], so a playing profile is heard changing. */
class AudioSettingsViewModel(private val dsp: AudioDsp) : ViewModel() {
    val profiles: StateFlow<AudioProfilesState> = dsp.state

    private val _ui = MutableStateFlow(AudioEditorUi())
    val ui: StateFlow<AudioEditorUi> = _ui.asStateFlow()

    fun newProfile(baseName: String) {
        val id = dsp.newId()
        dsp.update { it.addProfile(id, nextProfileName(it.profiles, baseName)) }
        _ui.value = AudioEditorUi(editingId = id)
    }

    fun open(id: String) {
        _ui.value = AudioEditorUi(editingId = id)
    }

    /** Leaving the editor never keeps a blank name: it falls back to the next free "Profile N". */
    fun close(baseName: String) {
        val id = _ui.value.editingId ?: return
        dsp.update { state ->
            if (state.profile(id)?.name?.isBlank() == true) {
                state.updateProfile(id) { it.copy(name = nextProfileName(state.profiles, baseName)) }
            } else {
                state
            }
        }
        _ui.value = AudioEditorUi()
    }

    fun rename(id: String, name: String) = edit(id) { it.copy(name = name) }

    fun stepGain(id: String, direction: Int) = edit(id) { it.stepGain(direction) }

    fun stepCrossfeed(id: String, index: Int, param: CrossfeedParam, direction: Int) = edit(id) { p ->
        val stage = p.stages.getOrNull(index) as? DspStage.Crossfeed ?: return@edit p
        p.replaceStage(index, stage.step(param, direction))
    }

    fun addCrossfeed(id: String) = edit(id) { it.addStage(DspStage.Crossfeed()) }

    fun moveStage(id: String, index: Int, delta: Int) = edit(id) { it.moveStage(index, delta) }

    fun removeStage(id: String, index: Int) = edit(id) { it.removeStage(index) }

    fun duplicate(id: String, copyName: (String) -> String) {
        val newId = dsp.newId()
        dsp.update { state -> state.profile(id)?.let { state.duplicateProfile(id, newId, copyName(it.name)) } ?: state }
        _ui.value = AudioEditorUi(editingId = newId)
    }

    fun askDelete() {
        _ui.value = _ui.value.copy(deleteConfirm = true)
    }

    fun cancelDelete() {
        _ui.value = _ui.value.copy(deleteConfirm = false)
    }

    fun confirmDelete() {
        val id = _ui.value.editingId ?: return
        dsp.update { it.deleteProfile(id) }
        _ui.value = AudioEditorUi()
    }

    fun setDefault(output: AudioOutputKind, id: String?) = dsp.update { it.setDefault(output, id) }

    fun stepHeadphoneVolume(direction: Int) = dsp.stepHeadphoneVolume(direction)

    private fun edit(id: String, change: (AudioProfile) -> AudioProfile) = dsp.update { it.updateProfile(id, change) }
}

class AudioSettingsViewModelFactory(private val dsp: AudioDsp) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AudioSettingsViewModel(dsp) as T
}

private fun Double.format(digits: Int): String = String.format(Locale.US, "%.${digits}f", this)

/** "+1.5" / "-1.0" / "0.0": signed so a gain reads as a change. */
private fun signedDb(db: Double): String = if (db > 0) "+${db.format(1)}" else db.format(1)

/** "32 · -12.0 dB", or "0 · muted". */
@Composable
private fun headphoneVolumeText(steps: Int): String =
    if (steps <= 0) {
        stringResource(R.string.player_volume_flash_muted)
    } else {
        "$steps · ${stringResource(R.string.settings_audio_gain_value, ((steps - HeadphoneVolume.MAX) * HeadphoneVolume.STEP_DB).format(1))}"
    }

@Composable
private fun outputLabel(output: AudioOutputKind): String = stringResource(
    when (output) {
        AudioOutputKind.HEADPHONES -> R.string.settings_audio_output_headphones
        AudioOutputKind.SPEAKERS -> R.string.settings_audio_output_speakers
        else -> R.string.settings_audio_output_external
    },
)

@Composable
private fun outputDescription(output: AudioOutputKind): String = stringResource(
    when (output) {
        AudioOutputKind.HEADPHONES -> R.string.settings_desc_audio_output_headphones
        AudioOutputKind.SPEAKERS -> R.string.settings_desc_audio_output_speakers
        else -> R.string.settings_desc_audio_output_external
    },
)

@Composable
private fun stageName(stage: DspStage): String = when (stage) {
    is DspStage.Crossfeed -> stringResource(R.string.settings_audio_stage_crossfeed)
}

/** "Crossfeed · +0.0 dB", or "No stages · ..." for an empty chain. */
@Composable
private fun profileSummary(profile: AudioProfile): String {
    val stages = if (profile.stages.isEmpty()) {
        stringResource(R.string.settings_audio_no_stages)
    } else {
        profile.stages.map { stageName(it) }.joinToString(" → ")
    }
    return "$stages · ${stringResource(R.string.settings_audio_gain_value, signedDb(profile.gainDb))}"
}

/**
 * Settings › Audio: per-output defaults and the profile list, or one profile's editor. Back closes
 * the editor (or its delete confirmation) before it leaves Settings.
 */
@Composable
internal fun AudioSectionContent(
    viewModel: AudioSettingsViewModel = viewModel(factory = AudioSettingsViewModelFactory(AppGraph.audioProfiles)),
) {
    val state by viewModel.profiles.collectAsState()
    val ui by viewModel.ui.collectAsState()
    val baseName = stringResource(R.string.settings_audio_profile_default_name)
    val editing = state.profile(ui.editingId)

    BackHandler(enabled = editing != null) {
        if (ui.deleteConfirm) viewModel.cancelDelete() else viewModel.close(baseName)
    }

    // Swapping list and editor removes the focused control: hand focus to the new view's anchor.
    val focusGate = LocalFocusGate.current
    val editorAnchor = remember { FocusRequester() }
    val listAnchors = remember { mutableMapOf<String, FocusRequester>() }
    val newProfileAnchor = remember { FocusRequester() }
    var lastEditing by remember { mutableStateOf<String?>(null) }
    var returnTo by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.editingId) {
        val previous = lastEditing
        lastEditing = ui.editingId
        when {
            ui.editingId != null && ui.editingId != previous -> requestFocusUntilSuccess(focusGate) { editorAnchor.requestFocus() }
            ui.editingId == null && previous != null -> {
                val target = returnTo?.let { listAnchors[it] } ?: newProfileAnchor
                requestFocusUntilSuccess(focusGate) { target.requestFocus() }
            }
        }
        if (ui.editingId != null) returnTo = ui.editingId
    }

    if (editing == null) {
        ProfileList(state, viewModel, baseName, listAnchors, newProfileAnchor)
    } else {
        ProfileEditor(editing, ui.deleteConfirm, viewModel, baseName, editorAnchor)
    }
}

@Composable
private fun ProfileList(
    state: AudioProfilesState,
    viewModel: AudioSettingsViewModel,
    baseName: String,
    anchors: MutableMap<String, FocusRequester>,
    newProfileAnchor: FocusRequester,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        StepperRow(
            label = stringResource(R.string.settings_audio_headphone_volume),
            description = stringResource(R.string.settings_desc_audio_headphone_volume),
            value = headphoneVolumeText(state.headphoneVolume),
            onStep = viewModel::stepHeadphoneVolume,
            key = "audio/headphone_volume",
        )
        SectionHeader(stringResource(R.string.settings_audio_defaults_header))
        val options: List<String?> = listOf(null) + state.profiles.map { it.id }
        val noneLabel = stringResource(R.string.settings_audio_profile_none)
        PROFILE_OUTPUTS.forEach { output ->
            PresetChipRow(
                label = outputLabel(output),
                description = outputDescription(output),
                key = "audio/default/${output.name.lowercase()}",
                options = options,
                selected = state.defaults[output],
                onSelect = { viewModel.setDefault(output, it) },
                chipLabel = { id -> state.profile(id)?.name ?: noneLabel },
                chipKey = { it ?: "none" },
            )
        }

        SectionHeader(stringResource(R.string.settings_audio_profiles_header))
        if (state.profiles.isEmpty()) SettingsNoteRow(stringResource(R.string.settings_audio_profiles_empty))
        state.profiles.forEach { profile ->
            val anchor = anchors.getOrPut(profile.id) { FocusRequester() }
            ActionRow(
                label = profile.name,
                description = profileSummary(profile),
                onClick = { viewModel.open(profile.id) },
                key = "audio/profile/${profile.id}",
                modifier = Modifier.focusRequester(anchor),
            )
        }
        ActionRow(
            label = stringResource(R.string.settings_audio_new_profile),
            description = stringResource(R.string.settings_desc_audio_new_profile),
            onClick = { viewModel.newProfile(baseName) },
            key = "audio/new_profile",
            modifier = Modifier.focusRequester(newProfileAnchor),
        )
    }
}

@Composable
private fun ProfileEditor(
    profile: AudioProfile,
    deleteConfirm: Boolean,
    viewModel: AudioSettingsViewModel,
    baseName: String,
    anchor: FocusRequester,
) {
    val id = profile.id
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        SectionHeader(stringResource(R.string.settings_audio_editor_header), Modifier.padding(bottom = 4.dp))
        SettingsNoteRow(stringResource(R.string.settings_audio_live_note))
        DiscoverTextField(
            label = stringResource(R.string.settings_audio_name_label),
            value = profile.name,
            onValueChange = { viewModel.rename(id, it) },
            key = "audio/edit/name",
            keyboardOptions = KeyboardOptions(autoCorrect = false),
        )
        StepperRow(
            label = stringResource(R.string.settings_audio_gain),
            description = stringResource(R.string.settings_desc_audio_gain),
            value = stringResource(R.string.settings_audio_gain_value, signedDb(profile.gainDb)),
            onStep = { viewModel.stepGain(id, it) },
            key = "audio/edit/gain",
            modifier = Modifier.focusRequester(anchor),
        )

        SectionHeader(stringResource(R.string.settings_audio_stages_header))
        profile.stages.forEachIndexed { index, stage ->
            StageCard(id, index, stage, isFirst = index == 0, isLast = index == profile.stages.lastIndex, viewModel)
        }
        ChipFieldRow(
            label = stringResource(R.string.settings_audio_add_stage),
            description = stringResource(R.string.settings_desc_audio_add_stage),
            key = "audio/edit/add_stage",
        ) {
            SettingsChip(
                label = stringResource(R.string.settings_audio_stage_crossfeed),
                selected = false,
                onSelect = { viewModel.addCrossfeed(id) },
                key = "crossfeed",
            )
        }

        SectionHeader(stringResource(R.string.settings_audio_actions_header))
        if (deleteConfirm) {
            SettingsNoteRow(stringResource(R.string.settings_audio_delete_body))
            Row(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
                SettingsChip(
                    label = stringResource(R.string.settings_audio_delete_cancel),
                    selected = false,
                    onSelect = viewModel::cancelDelete,
                    key = "audio/edit/delete_cancel",
                )
                SettingsChip(
                    label = stringResource(R.string.settings_audio_delete_confirm),
                    selected = true,
                    onSelect = viewModel::confirmDelete,
                    key = "audio/edit/delete_confirm",
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
                SettingsChip(
                    label = stringResource(R.string.settings_audio_done),
                    selected = true,
                    onSelect = { viewModel.close(baseName) },
                    key = "audio/edit/done",
                )
                SettingsChip(
                    label = stringResource(R.string.settings_audio_duplicate),
                    selected = false,
                    onSelect = { viewModel.duplicate(id) { name -> context.getString(R.string.settings_audio_profile_copy_name, name) } },
                    key = "audio/edit/duplicate",
                )
                SettingsChip(
                    label = stringResource(R.string.settings_audio_delete),
                    selected = false,
                    onSelect = viewModel::askDelete,
                    key = "audio/edit/delete",
                )
            }
        }
    }
}

/** One stage: its name and move/remove chips, then a stepper per parameter. */
@Composable
private fun StageCard(
    id: String,
    index: Int,
    stage: DspStage,
    isFirst: Boolean,
    isLast: Boolean,
    viewModel: AudioSettingsViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        ChipFieldRow(label = "${index + 1}. ${stageName(stage)}", description = "", key = "audio/edit/stage/$index") {
            Row(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
                if (!isFirst) {
                    SettingsChip(
                        label = stringResource(R.string.settings_audio_stage_move_up),
                        selected = false,
                        onSelect = { viewModel.moveStage(id, index, -1) },
                        key = "up",
                    )
                }
                if (!isLast) {
                    SettingsChip(
                        label = stringResource(R.string.settings_audio_stage_move_down),
                        selected = false,
                        onSelect = { viewModel.moveStage(id, index, 1) },
                        key = "down",
                    )
                }
                SettingsChip(
                    label = stringResource(R.string.settings_audio_stage_remove),
                    selected = false,
                    onSelect = { viewModel.removeStage(id, index) },
                    key = "remove",
                )
            }
        }
        when (stage) {
            is DspStage.Crossfeed -> {
                StepperRow(
                    label = stringResource(R.string.settings_audio_crossfeed_strength),
                    description = stringResource(R.string.settings_desc_audio_crossfeed_strength, stage.sideCutDb().format(1)),
                    value = stage.strength.format(2),
                    onStep = { viewModel.stepCrossfeed(id, index, CrossfeedParam.STRENGTH, it) },
                    key = "audio/edit/stage/$index/strength",
                )
                StepperRow(
                    label = stringResource(R.string.settings_audio_crossfeed_range),
                    description = stringResource(R.string.settings_desc_audio_crossfeed_range, stage.cutoffHz()),
                    value = stage.range.format(2),
                    onStep = { viewModel.stepCrossfeed(id, index, CrossfeedParam.RANGE, it) },
                    key = "audio/edit/stage/$index/range",
                )
                StepperRow(
                    label = stringResource(R.string.settings_audio_crossfeed_slope),
                    description = stringResource(R.string.settings_desc_audio_crossfeed_slope),
                    value = stage.slope.format(2),
                    onStep = { viewModel.stepCrossfeed(id, index, CrossfeedParam.SLOPE, it) },
                    key = "audio/edit/stage/$index/slope",
                )
            }
        }
    }
}

/**
 * A value row the D-pad adjusts in place: Left/Right call [onStep] with -1/+1 (held keys repeat),
 * consumed so they never move focus to the rail. [ToggleRow]'s shape and focus registration.
 */
@Composable
internal fun StepperRow(
    label: String,
    description: String,
    value: String,
    onStep: (Int) -> Unit,
    key: String,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val descriptionAlpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(SETTINGS_DESCRIPTION_FADE_MS),
        label = "stepperRowDescriptionAlpha",
    )
    val memory = LocalSettingsFocusMemory.current
    val entryTarget = LocalSettingsPaneEntryKey.current
    // The drawer host sees Left before this row does; it stands down while a stepper holds focus.
    // Set from focus callbacks (old row loses, then new row gains), not effects, whose order between rows is unspecified.
    val drawerLeftSuppressed = LocalDrawerLeftEdgeSuppressed.current
    var holdsSuppression by remember { mutableStateOf(false) }
    DisposableEffect(drawerLeftSuppressed) {
        onDispose { if (holdsSuppression) drawerLeftSuppressed?.value = false }
    }

    RowCard(
        isFocused = isFocused,
        height = SETTINGS_ROW_HEIGHT,
        modifier = modifier
            .let { if (memory != null) it.focusKey(memory, key) else it }
            .let { it.paneEntry(entryTarget, key) }
            .onFocusChanged { state ->
                if (state.isFocused) {
                    drawerLeftSuppressed?.value = true
                    holdsSuppression = true
                } else if (holdsSuppression) {
                    drawerLeftSuppressed?.value = false
                    holdsSuppression = false
                }
            }
            .onPreviewKeyEvent { event ->
                val direction = when (event.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) onStep(direction)
                true
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = {}),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = rowLabelStyle())
            BasicText(
                text = description,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = descriptionAlpha },
                style = settingsDescriptionStyle(),
            )
        }
        BasicText(
            text = "‹  $value  ›",
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                fontWeight = FontWeight.SemiBold,
                color = if (isFocused) JellybeamTheme.Panna else JellybeamTheme.Panna2,
                fontSize = 16.sp,
            ),
        )
    }
}
