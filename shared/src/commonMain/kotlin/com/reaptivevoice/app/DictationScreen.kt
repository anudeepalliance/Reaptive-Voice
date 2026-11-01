package com.reaptivevoice.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.painterResource
import reaptive_voice.shared.generated.resources.Res
import reaptive_voice.shared.generated.resources.tray_icon

private data class MacPalette(
    val window: Color,
    val card: Color,
    val label: Color,
    val secondary: Color,
    val separator: Color,
    val accent: Color,
    val onAccent: Color,
    val field: Color,
    val ready: Color,
    val recording: Color,
    val working: Color,
)

private val darkMacPalette = MacPalette(
    window = Color(0xFF1C1C1E),
    card = Color(0xFF2C2C2E),
    label = Color(0xFFFFFFFF),
    secondary = Color(0xFF98989D),
    separator = Color(0xFF38383A),
    accent = Color(0xFF0A84FF),
    onAccent = Color(0xFFFFFFFF),
    field = Color(0xFF1C1C1E),
    ready = Color(0xFF30D158),
    recording = Color(0xFFFF453A),
    working = Color(0xFFFFD60A),
)

private val lightMacPalette = MacPalette(
    window = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    label = Color(0xFF000000),
    secondary = Color(0xFF8E8E93),
    separator = Color(0xFFE5E5EA),
    accent = Color(0xFF007AFF),
    onAccent = Color(0xFFFFFFFF),
    field = Color(0xFFFFFFFF),
    ready = Color(0xFF34C759),
    recording = Color(0xFFFF3B30),
    working = Color(0xFFFF9F0A),
)

private val LocalMacPalette = staticCompositionLocalOf { darkMacPalette }

/**
 * Menu bar icon for the desktop window. Kept here because the generated image resource is visible only inside this module.
 */
@Composable
fun reaptiveVoiceMenuBarIcon(): Painter {
    return painterResource(Res.drawable.tray_icon)
}

/**
 * The dictation window. The global shortcut is handled outside this screen, so the words can land in any Mac text field.
 */
@Composable
fun DictationScreen(
    screenState: DictationScreenState,
    isSystemInDarkTheme: Boolean,
    onLanguageSelected: (DictationLanguage) -> Unit,
    onDraftChange: (text: String, selectionStart: Int, selectionEnd: Int) -> Unit,
    onRequestAccessibility: () -> Unit,
    onCopyTranscript: (String) -> Unit,
    onBeginShortcutRecording: () -> Unit,
    onOpenAtLoginChange: (Boolean) -> Unit,
    onAppearanceSelected: (String) -> Unit,
    onMicrophoneSelected: (microphoneId: String, microphoneName: String) -> Unit,
    onAddWordReplacement: (misheardSpellingsText: String, correctWord: String) -> Unit,
    onEditWordReplacement: (wordReplacementId: Long, misheardSpellingsText: String, correctWord: String) -> Unit,
    onRemoveWordReplacement: (wordReplacementId: Long) -> Unit,
    onSpeakText: (String) -> Unit,
    onSpeakClipboard: () -> Unit,
    onStopSpeaking: () -> Unit,
    onSpeakingVoiceSelected: (SpeakingVoice) -> Unit,
    onPreviewSpeakingVoice: (SpeakingVoice) -> Unit,
    onToggleSpeechPause: () -> Unit,
    onRewindSpeech: () -> Unit,
    onSaveTextAsMp3: (String) -> Unit,
    onCancelSavingMp3: () -> Unit,
    onSpeakingSpeedSelected: (Float) -> Unit,
    onOpenSavedAudio: (SavedSpeechAudio) -> Unit,
    onDownloadSavedAudio: (SavedSpeechAudio) -> Unit,
    onDeleteSavedAudio: (SavedSpeechAudio) -> Unit,
    onRetryStartingVoice: () -> Unit,
    onToggleSavedAudioPlayback: () -> Unit,
    onSkipSavedAudioPlayback: (seconds: Int) -> Unit,
    onSeekSavedAudioPlayback: (positionInSeconds: Double) -> Unit,
    onCloseSavedAudioPlayer: () -> Unit,
) {
    val useDarkColors = when (screenState.appearanceMode) {
        "light" -> false
        "system" -> isSystemInDarkTheme
        else -> true
    }
    val palette = if (useDarkColors) {
        darkMacPalette
    } else {
        lightMacPalette
    }
    val colorScheme = if (useDarkColors) {
        darkColorScheme(
            background = palette.window,
            surface = palette.card,
            primary = palette.accent,
            onPrimary = palette.onAccent,
            onSurface = palette.label,
            onSurfaceVariant = palette.secondary,
            outline = palette.separator,
        )
    } else {
        lightColorScheme(
            background = palette.window,
            surface = palette.card,
            primary = palette.accent,
            onPrimary = palette.onAccent,
            onSurface = palette.label,
            onSurfaceVariant = palette.secondary,
            outline = palette.separator,
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography.copy(
            headlineMedium = TextStyle(
                fontFamily = FontFamily.Default,
                fontSize = 28.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                color = palette.label,
            ),
            bodyLarge = TextStyle(
                fontFamily = FontFamily.Default,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                color = palette.label,
            ),
            bodyMedium = TextStyle(
                fontFamily = FontFamily.Default,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = palette.secondary,
            ),
            labelLarge = TextStyle(
                fontFamily = FontFamily.Default,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.label,
            ),
        ),
    ) {
        CompositionLocalProvider(LocalMacPalette provides palette) {
            var selectedSettingsPage by remember { mutableStateOf(SettingsPage.General) }
            var selectedTabByPage by remember {
                mutableStateOf(
                    SettingsPage.entries.associateWith { settingsPage ->
                        settingsPage.tabs.first()
                    },
                )
            }
            var textToReadAloud by remember { mutableStateOf("") }
            val selectedTab = selectedTabByPage.getValue(selectedSettingsPage)
            val openTab = { settingsTab: SettingsTab ->
                selectedSettingsPage = settingsTab.page
                selectedTabByPage = selectedTabByPage + (settingsTab.page to settingsTab)
            }
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = palette.window,
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    SettingsSidebar(
                        screenState = screenState,
                        selectedSettingsPage = selectedSettingsPage,
                        onSettingsPageSelected = { settingsPage ->
                            selectedSettingsPage = settingsPage
                        },
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(palette.separator),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 28.dp, vertical = 22.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        PageTitleAndTabs(
                            settingsPage = selectedSettingsPage,
                            selectedTab = selectedTab,
                            screenState = screenState,
                            onTabSelected = openTab,
                        )
                        when (selectedTab) {
                            SettingsTab.GeneralSettings -> GeneralSettingsTab(
                                screenState = screenState,
                                onRequestAccessibility = onRequestAccessibility,
                                onBeginShortcutRecording = onBeginShortcutRecording,
                                onOpenAtLoginChange = onOpenAtLoginChange,
                                onAppearanceSelected = onAppearanceSelected,
                            )
                            SettingsTab.GeneralModel -> GeneralModelTab(screenState = screenState)
                            SettingsTab.VoiceDictation -> VoiceDictationTab(
                                screenState = screenState,
                                onLanguageSelected = onLanguageSelected,
                                onDraftChange = onDraftChange,
                                onMicrophoneSelected = onMicrophoneSelected,
                            )
                            SettingsTab.VoiceWordReplacements -> WordReplacementsPage(
                                wordReplacements = screenState.wordReplacements,
                                onAddWordReplacement = onAddWordReplacement,
                                onEditWordReplacement = onEditWordReplacement,
                                onRemoveWordReplacement = onRemoveWordReplacement,
                            )
                            SettingsTab.VoiceTranscriptions -> HistoryPage(
                                transcriptHistory = screenState.transcriptHistory,
                                onCopyTranscript = onCopyTranscript,
                            )
                            SettingsTab.SpeechReadAloud -> ReadAloudPage(
                                screenState = screenState,
                                textToReadAloud = textToReadAloud,
                                onTextToReadAloudChange = { updatedText ->
                                    textToReadAloud = updatedText
                                },
                                onSpeakText = onSpeakText,
                                onSpeakClipboard = onSpeakClipboard,
                                onStopSpeaking = onStopSpeaking,
                                onToggleSpeechPause = onToggleSpeechPause,
                                onRewindSpeech = onRewindSpeech,
                                onOpenVoicesPage = {
                                    openTab(SettingsTab.SpeechVoices)
                                },
                                onSaveTextAsMp3 = onSaveTextAsMp3,
                                onCancelSavingMp3 = onCancelSavingMp3,
                                onSpeakingSpeedSelected = onSpeakingSpeedSelected,
                                onOpenSavedAudioPage = {
                                    openTab(SettingsTab.SpeechSavedAudio)
                                },
                                onRetryStartingVoice = onRetryStartingVoice,
                            )
                            SettingsTab.SpeechVoices -> VoicesPage(
                                screenState = screenState,
                                onSpeakingVoiceSelected = onSpeakingVoiceSelected,
                                onPreviewSpeakingVoice = onPreviewSpeakingVoice,
                                onStopSpeaking = onStopSpeaking,
                            )
                            SettingsTab.SpeechSavedAudio -> SavedAudioPage(
                                savedSpeechAudioFiles = screenState.savedSpeechAudioFiles,
                                savedAudioPlayer = screenState.savedAudioPlayer,
                                onToggleSavedAudioPlayback = onToggleSavedAudioPlayback,
                                onSkipSavedAudioPlayback = onSkipSavedAudioPlayback,
                                onSeekSavedAudioPlayback = onSeekSavedAudioPlayback,
                                onCloseSavedAudioPlayer = onCloseSavedAudioPlayer,
                                onOpenSavedAudio = onOpenSavedAudio,
                                onDownloadSavedAudio = onDownloadSavedAudio,
                                onDeleteSavedAudio = onDeleteSavedAudio,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The three pages in the window's sidebar: app-wide settings, dictation (speech to text), and reading aloud (text to speech).
 */
private enum class SettingsPage(val sidebarLabel: String, val pageDescription: String) {
    General("General", "App-wide settings and the speech model."),
    Voice("Voice", "Dictation: speak, and Rvoice types what you said."),
    Speech("Speech", "Read aloud: Rvoice reads text to you."),
    ;

    val tabs: List<SettingsTab>
        get() = SettingsTab.entries.filter { it.page == this }
}

/**
 * The tabs along the top of each page, in the order they appear.
 */
private enum class SettingsTab(val page: SettingsPage, val tabLabel: String) {
    GeneralSettings(SettingsPage.General, "Settings"),
    GeneralModel(SettingsPage.General, "Model"),
    VoiceDictation(SettingsPage.Voice, "Dictation"),
    VoiceWordReplacements(SettingsPage.Voice, "Word replacements"),
    VoiceTranscriptions(SettingsPage.Voice, "Transcriptions"),
    SpeechReadAloud(SettingsPage.Speech, "Read aloud"),
    SpeechVoices(SettingsPage.Speech, "Voices"),
    SpeechSavedAudio(SettingsPage.Speech, "Saved audio"),
}

/**
 * The left column: app name, one row per page, and the live status at the bottom so it is visible on every page.
 */
@Composable
private fun SettingsSidebar(
    screenState: DictationScreenState,
    selectedSettingsPage: SettingsPage,
    onSettingsPageSelected: (SettingsPage) -> Unit,
) {
    val palette = LocalMacPalette.current
    Column(
        modifier = Modifier
            .width(190.dp)
            .fillMaxHeight()
            .background(palette.card.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Rvoice",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 10.dp, bottom = 14.dp),
        )
        SettingsPage.entries.forEach { settingsPage ->
            SidebarItem(
                label = settingsPage.sidebarLabel,
                itemCount = null,
                isSelected = settingsPage == selectedSettingsPage,
                onClick = {
                    onSettingsPageSelected(settingsPage)
                },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Box(modifier = Modifier.padding(start = 10.dp)) {
            StatusDot(screenState)
        }
    }
}

/**
 * The page's title and one-line description, then its tabs as a row of joined buttons. Tabs that hold lists show how many items they have.
 */
@Composable
private fun PageTitleAndTabs(
    settingsPage: SettingsPage,
    selectedTab: SettingsTab,
    screenState: DictationScreenState,
    onTabSelected: (SettingsTab) -> Unit,
) {
    val palette = LocalMacPalette.current
    Column {
        Text(text = settingsPage.sidebarLabel, style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = settingsPage.pageDescription, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(palette.card)
                .padding(3.dp),
        ) {
            settingsPage.tabs.forEach { settingsTab ->
                val isSelected = settingsTab == selectedTab
                val itemCount = when (settingsTab) {
                    SettingsTab.VoiceWordReplacements -> screenState.wordReplacements.size
                    SettingsTab.VoiceTranscriptions -> screenState.transcriptHistory.size
                    SettingsTab.SpeechSavedAudio -> screenState.savedSpeechAudioFiles.size
                    else -> 0
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isSelected) {
                                palette.accent
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            onTabSelected(settingsTab)
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tabTextColor = if (isSelected) {
                        palette.onAccent
                    } else {
                        palette.label
                    }
                    Text(text = settingsTab.tabLabel, color = tabTextColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    if (itemCount > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = itemCount.toString(), color = tabTextColor.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * One sidebar row. The selected page is filled with the accent color. A count, when given, sits on the right.
 */
@Composable
private fun SidebarItem(
    label: String,
    itemCount: Int?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalMacPalette.current
    val contentColor = if (isSelected) {
        palette.onAccent
    } else {
        palette.label
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(
                if (isSelected) {
                    palette.accent
                } else {
                    Color.Transparent
                },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (itemCount != null && itemCount > 0) {
            Text(
                text = itemCount.toString(),
                color = if (isSelected) {
                    palette.onAccent.copy(alpha = 0.85f)
                } else {
                    palette.secondary
                },
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * A small round button showing a standard icon, with a thin circular border that marks where to click. Hovering shows what it does, which is also what screen readers announce.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IconActionButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                Text(description)
            }
        },
        state = rememberTooltipState(),
    ) {
        OutlinedIconButton(
            onClick = onClick,
            enabled = enabled,
            border = BorderStroke(1.dp, if (enabled) tint.copy(alpha = 0.45f) else tint.copy(alpha = 0.15f)),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (enabled) tint else tint.copy(alpha = 0.35f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The main action in a row of icon buttons (play, pause, speak): a filled accent circle with a white icon, so it stands out from the outlined ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilledIconActionButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalMacPalette.current
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                Text(description)
            }
        },
        state = rememberTooltipState(),
    ) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = palette.accent,
                contentColor = palette.onAccent,
                disabledContainerColor = palette.card,
                disabledContentColor = palette.secondary,
            ),
        ) {
            Icon(imageVector = icon, contentDescription = description)
        }
    }
}

/**
 * One short line under the tabs that says what this tab is for.
 */
@Composable
private fun TabIntroduction(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

/**
 * Shown when a list page has nothing in it yet.
 */
@Composable
private fun EmptyListMessage(title: String, body: String) {
    val palette = LocalMacPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, palette.separator, RoundedCornerShape(10.dp))
            .padding(vertical = 28.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * App-wide settings: permission notices, the dictation shortcut, opening at login, and dark or light look.
 */
@Composable
private fun GeneralSettingsTab(
    screenState: DictationScreenState,
    onRequestAccessibility: () -> Unit,
    onBeginShortcutRecording: () -> Unit,
    onOpenAtLoginChange: (Boolean) -> Unit,
    onAppearanceSelected: (String) -> Unit,
) {
    TabIntroduction(text = screenState.statusLine)
    if (!screenState.isAccessibilityTrusted) {
        NoticeCard(
            title = "Allow Accessibility once",
            body = "Turn on Rvoice under System Settings, Privacy & Security, Accessibility. If an older Reaptive Voice row is still there, turn that off.",
            actionLabel = "Ask macOS",
            onAction = onRequestAccessibility,
        )
    }
    ShortcutRow(
        screenState = screenState,
        onBeginShortcutRecording = onBeginShortcutRecording,
    )
    LoginRow(
        isChecked = screenState.openAtLogin,
        onCheckedChange = onOpenAtLoginChange,
    )
    AppearanceRow(
        appearanceMode = screenState.appearanceMode,
        onAppearanceSelected = onAppearanceSelected,
    )
}

/**
 * Which model Rvoice uses and whether it has loaded, for both dictation and reading aloud.
 */
@Composable
private fun GeneralModelTab(screenState: DictationScreenState) {
    TabIntroduction(text = "Everything runs on this Mac. Nothing you say or read is sent anywhere.")
    ModelRow(modelFootnote = screenState.modelFootnote)
    NoticeCard(
        title = "Dictation",
        body = if (screenState.enginePhase == DictationEnginePhase.Ready) "Loaded and ready." else screenState.engineDetail,
        actionLabel = null,
        onAction = null,
    )
    NoticeCard(
        title = "Reading aloud",
        body = screenState.speakingVoiceProblem ?: if (screenState.isSpeakingVoiceReady) "Loaded and ready." else "Loading…",
        actionLabel = null,
        onAction = null,
    )
}

/**
 * Dictation settings: which microphone and language to use, and a box to try dictating into. Escape stops a dictation at once.
 */
@Composable
private fun VoiceDictationTab(
    screenState: DictationScreenState,
    onLanguageSelected: (DictationLanguage) -> Unit,
    onDraftChange: (text: String, selectionStart: Int, selectionEnd: Int) -> Unit,
    onMicrophoneSelected: (microphoneId: String, microphoneName: String) -> Unit,
) {
    val palette = LocalMacPalette.current
    TabIntroduction(text = "Press ${screenState.shortcutLabel} in any text field, speak, then press it again. Press Escape to stop and keep only what is already typed.")
    val microphoneProblem = screenState.microphoneProblem
    if (microphoneProblem != null) {
        NoticeCard(
            title = "Microphone",
            body = microphoneProblem,
            actionLabel = null,
            onAction = null,
        )
    }
    MicrophonePicker(
        screenState = screenState,
        onMicrophoneSelected = onMicrophoneSelected,
    )
    LanguagePicker(
        selectedLanguageEnglishName = screenState.selectedLanguageEnglishName,
        onLanguageSelected = onLanguageSelected,
    )
    Text(
        text = "Try it in this box",
        style = MaterialTheme.typography.labelLarge,
    )
    val draftValue = TextFieldValue(
        text = screenState.draftText,
        selection = TextRange(
            screenState.draftSelectionStart.coerceIn(0, screenState.draftText.length),
            screenState.draftSelectionEnd.coerceIn(0, screenState.draftText.length),
        ),
    )
    key(screenState.draftRevision) {
        OutlinedTextField(
            value = draftValue,
            onValueChange = { updated ->
                onDraftChange(updated.text, updated.selection.start, updated.selection.end)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = palette.label,
                unfocusedTextColor = palette.label,
                focusedBorderColor = palette.accent,
                unfocusedBorderColor = palette.separator,
                cursorColor = palette.accent,
                focusedContainerColor = palette.field,
                unfocusedContainerColor = palette.field,
            ),
            placeholder = {
                Text(
                    text = "Click here, press ${screenState.shortcutLabel}, speak, press it again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
        )
    }
    Text(
        text = "A take can run up to 20 minutes.",
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * Reads text out loud with the local model: a box to type or paste into, Speak and Stop buttons, and the voice and accent to use. Also explains how to read selected text in other apps.
 */
@Composable
private fun ReadAloudPage(
    screenState: DictationScreenState,
    textToReadAloud: String,
    onTextToReadAloudChange: (String) -> Unit,
    onSpeakText: (String) -> Unit,
    onSpeakClipboard: () -> Unit,
    onStopSpeaking: () -> Unit,
    onToggleSpeechPause: () -> Unit,
    onRewindSpeech: () -> Unit,
    onOpenVoicesPage: () -> Unit,
    onSaveTextAsMp3: (String) -> Unit,
    onCancelSavingMp3: () -> Unit,
    onSpeakingSpeedSelected: (Float) -> Unit,
    onOpenSavedAudioPage: () -> Unit,
    onRetryStartingVoice: () -> Unit,
) {
    val palette = LocalMacPalette.current
    TabIntroduction(text = "Type or paste text, then press the speaker to hear it now, or the save button to pick a folder and save it as an MP3. Hover over a button to see what it does.")
    val speakingVoiceProblem = screenState.speakingVoiceProblem
    if (speakingVoiceProblem != null) {
        NoticeCard(
            title = "The voice could not start",
            body = speakingVoiceProblem,
            actionLabel = "Try again",
            onAction = onRetryStartingVoice,
        )
    } else if (!screenState.isSpeakingVoiceReady) {
        NoticeCard(
            title = "The voice is loading",
            body = "This takes a few seconds after Rvoice starts. The buttons below turn on when it is ready.",
            actionLabel = null,
            onAction = null,
        )
    }
    OutlinedTextField(
        value = textToReadAloud,
        onValueChange = onTextToReadAloudChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = palette.label,
            unfocusedTextColor = palette.label,
            focusedBorderColor = palette.accent,
            unfocusedBorderColor = palette.separator,
            cursorColor = palette.accent,
            focusedContainerColor = palette.field,
            unfocusedContainerColor = palette.field,
        ),
        placeholder = {
            Text("Type or paste the text you want to hear.", style = MaterialTheme.typography.bodyMedium)
        },
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val canStartReading = screenState.isSpeakingVoiceReady && screenState.speechFileProgress == null
        if (screenState.isSpeaking) {
            IconActionButton(icon = Icons.Filled.Replay5, description = "Back 5 seconds", tint = palette.label, onClick = onRewindSpeech)
            FilledIconActionButton(
                icon = if (screenState.isSpeechPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                description = if (screenState.isSpeechPaused) "Resume" else "Pause",
                onClick = onToggleSpeechPause,
            )
            IconActionButton(icon = Icons.Filled.Stop, description = "Stop", tint = palette.recording, onClick = onStopSpeaking)
        } else {
            FilledIconActionButton(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                description = "Speak this text",
                enabled = canStartReading && textToReadAloud.isNotBlank(),
            ) {
                onSpeakText(textToReadAloud)
            }
            IconActionButton(
                icon = Icons.Outlined.SaveAlt,
                description = "Save as MP3… (choose where)",
                tint = palette.label,
                enabled = canStartReading && textToReadAloud.isNotBlank(),
            ) {
                onSaveTextAsMp3(textToReadAloud)
            }
            IconActionButton(
                icon = Icons.Outlined.ContentPaste,
                description = "Speak what is on the clipboard",
                tint = palette.label,
                enabled = canStartReading,
                onClick = onSpeakClipboard,
            )
        }
    }
    val speechFileProgress = screenState.speechFileProgress
    if (speechFileProgress != null) {
        SettingsGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Making MP3", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = if (speechFileProgress.totalPartCount == 0) {
                            "Starting…"
                        } else {
                            "Part ${speechFileProgress.finishedPartCount} of ${speechFileProgress.totalPartCount} done. It will appear on the Saved audio page."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = onCancelSavingMp3) {
                    Text("Cancel", color = palette.recording)
                }
            }
        }
    }
    Column {
        Text(text = "Speed", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(palette.card)
                .padding(3.dp),
        ) {
            speakingSpeeds.forEach { speed ->
                val isSelected = speed == screenState.speakingSpeed
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isSelected) {
                                palette.accent
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable {
                            onSpeakingSpeedSelected(speed)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = speakingSpeedLabel(speed),
                        color = if (isSelected) {
                            palette.onAccent
                        } else {
                            palette.label
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Changes the pace while speaking. Saved MP3s are always at normal speed.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    SettingsGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Voice", style = MaterialTheme.typography.labelLarge)
                Text(text = speakingVoiceForId(screenState.selectedSpeakingVoiceId).displayName, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onOpenVoicesPage) {
                Text("Change", color = palette.accent)
            }
        }
    }
    SettingsGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(text = "In other apps", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Select text in Safari, Chrome, Notes, Mail and most Mac apps, right-click it, and choose Speak with Rvoice or Save as MP3 with Rvoice (sometimes under Services). " +
                    "If it is missing, turn it on in System Settings, Keyboard, Keyboard Shortcuts, Services, Text. " +
                    "Click the pill or press ${screenState.shortcutLabel} to stop.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * MP3s made in the last seven days, newest first. Each can be opened in the Mac's audio player, downloaded to a folder of the person's choice to keep, or deleted.
 */
@Composable
private fun SavedAudioPage(
    savedSpeechAudioFiles: List<SavedSpeechAudio>,
    savedAudioPlayer: SavedAudioPlayerState?,
    onToggleSavedAudioPlayback: () -> Unit,
    onSkipSavedAudioPlayback: (seconds: Int) -> Unit,
    onSeekSavedAudioPlayback: (positionInSeconds: Double) -> Unit,
    onCloseSavedAudioPlayer: () -> Unit,
    onOpenSavedAudio: (SavedSpeechAudio) -> Unit,
    onDownloadSavedAudio: (SavedSpeechAudio) -> Unit,
    onDeleteSavedAudio: (SavedSpeechAudio) -> Unit,
) {
    val palette = LocalMacPalette.current
    TabIntroduction(
        text = "Everything Rvoice reads out loud or saves as MP3. Files are deleted after seven days, so download the ones you want to keep.",
    )
    if (savedAudioPlayer != null) {
        SavedAudioPlayerCard(
            playerState = savedAudioPlayer,
            onTogglePlayback = onToggleSavedAudioPlayback,
            onSkip = onSkipSavedAudioPlayback,
            onSeek = onSeekSavedAudioPlayback,
            onClose = onCloseSavedAudioPlayer,
        )
    }
    if (savedSpeechAudioFiles.isEmpty()) {
        EmptyListMessage(title = "No audio yet", body = "Read something out loud or use Save as MP3 on the Read aloud page.")
        return
    }
    SettingsGroup {
        savedSpeechAudioFiles.forEachIndexed { rowIndex, savedAudio ->
            if (rowIndex > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp)
                        .height(1.dp)
                        .background(palette.separator),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = savedAudio.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                    Spacer(modifier = Modifier.height(2.dp))
                    val howItWasMade = if (savedAudio.wasSpokenOutLoud) "Read out loud" else "Saved as MP3"
                    Text(
                        text = "${savedAudio.createdAtLabel}  ·  ${formatLengthAsMinutesAndSeconds(savedAudio.lengthInSeconds)}  ·  $howItWasMade",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                IconActionButton(icon = Icons.Filled.PlayArrow, description = "Play", tint = palette.accent) {
                    onOpenSavedAudio(savedAudio)
                }
                IconActionButton(icon = Icons.Filled.Download, description = "Download…", tint = palette.accent) {
                    onDownloadSavedAudio(savedAudio)
                }
                IconActionButton(icon = Icons.Outlined.Delete, description = "Delete", tint = palette.recording) {
                    onDeleteSavedAudio(savedAudio)
                }
            }
        }
    }
}

/**
 * The built-in player for a saved MP3: its title, a bar to drag to any point, the time played and total, and back 10 s, play or pause, forward 10 s, and close.
 */
@Composable
private fun SavedAudioPlayerCard(
    playerState: SavedAudioPlayerState,
    onTogglePlayback: () -> Unit,
    onSkip: (seconds: Int) -> Unit,
    onSeek: (positionInSeconds: Double) -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalMacPalette.current
    var draggedPositionFraction by remember { mutableStateOf<Float?>(null) }
    val lengthInSeconds = playerState.lengthInSeconds.coerceAtLeast(0.001)
    /* The player reports its position four times a second. Between reports the playhead keeps moving every frame by the time that has passed, so it glides instead of jumping. */
    var smoothPositionInSeconds by remember { mutableStateOf(playerState.positionInSeconds) }
    LaunchedEffect(playerState) {
        smoothPositionInSeconds = playerState.positionInSeconds
        if (!playerState.isPlaying) {
            return@LaunchedEffect
        }
        val reportArrivedAtNanoseconds = withFrameNanos { it }
        while (true) {
            withFrameNanos { frameNanoseconds ->
                val secondsSinceReport = (frameNanoseconds - reportArrivedAtNanoseconds) / 1_000_000_000.0
                smoothPositionInSeconds = (playerState.positionInSeconds + secondsSinceReport).coerceAtMost(playerState.lengthInSeconds)
            }
        }
    }
    val playedFraction = draggedPositionFraction ?: (smoothPositionInSeconds / lengthInSeconds).toFloat().coerceIn(0f, 1f)
    SettingsGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = playerState.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                IconActionButton(icon = Icons.Filled.Close, description = "Close player", tint = palette.secondary, onClick = onClose)
            }
            Slider(
                value = playedFraction,
                onValueChange = { fraction ->
                    draggedPositionFraction = fraction
                },
                onValueChangeFinished = {
                    draggedPositionFraction?.let { fraction ->
                        onSeek(fraction * lengthInSeconds)
                    }
                    draggedPositionFraction = null
                },
                colors = SliderDefaults.colors(
                    thumbColor = palette.accent,
                    activeTrackColor = palette.accent,
                    inactiveTrackColor = palette.separator,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatLengthAsMinutesAndSeconds(playedFraction * lengthInSeconds) + " / " + formatLengthAsMinutesAndSeconds(playerState.lengthInSeconds),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconActionButton(icon = Icons.Filled.Replay10, description = "Back 10 seconds", tint = palette.label) {
                    onSkip(-10)
                }
                FilledIconActionButton(
                    icon = if (playerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    description = if (playerState.isPlaying) "Pause" else "Play",
                    onClick = onTogglePlayback,
                )
                IconActionButton(icon = Icons.Filled.Forward10, description = "Forward 10 seconds", tint = palette.label) {
                    onSkip(10)
                }
            }
        }
    }
}

/**
 * The voices Rvoice can read in. Clicking a row picks that voice; Preview picks it and reads a short sample.
 */
@Composable
private fun VoicesPage(
    screenState: DictationScreenState,
    onSpeakingVoiceSelected: (SpeakingVoice) -> Unit,
    onPreviewSpeakingVoice: (SpeakingVoice) -> Unit,
    onStopSpeaking: () -> Unit,
) {
    val palette = LocalMacPalette.current
    TabIntroduction(
        text = "Pick the voice Rvoice reads text in. Press ▶ to hear it first.",
    )
    SettingsGroup {
        speakingVoices.forEachIndexed { rowIndex, voice ->
            if (rowIndex > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 44.dp)
                        .height(1.dp)
                        .background(palette.separator),
                )
            }
            val isSelected = voice.id == screenState.selectedSpeakingVoiceId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onSpeakingVoiceSelected(voice)
                    }
                    .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, if (isSelected) palette.accent else palette.secondary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(palette.accent),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = voice.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(text = voice.description, style = MaterialTheme.typography.bodyMedium)
                }
                if (screenState.isSpeaking && isSelected) {
                    IconActionButton(icon = Icons.Filled.Stop, description = "Stop", tint = palette.recording, onClick = onStopSpeaking)
                } else {
                    IconActionButton(
                        icon = Icons.Filled.PlayArrow,
                        description = "Preview this voice",
                        tint = palette.accent,
                        enabled = screenState.isSpeakingVoiceReady && !screenState.isSpeaking,
                    ) {
                        onPreviewSpeakingVoice(voice)
                    }
                }
            }
        }
    }
    Text(
        text = "All voices run on this Mac with LFM3-Audio-2B. American female sounds clearest. The British and Indian voices sound duller and very alike, because the model was trained mostly on American English.",
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * Past dictations from the last seven days, newest first, each with a copy button.
 */
@Composable
private fun HistoryPage(
    transcriptHistory: List<DictationTranscriptEntry>,
    onCopyTranscript: (String) -> Unit,
) {
    TabIntroduction(
        text = "Your dictations from the last seven days. Older ones are deleted.")
    if (transcriptHistory.isEmpty()) {
        EmptyListMessage(title = "Nothing dictated yet", body = "Your dictations will show up here.")
        return
    }
    transcriptHistory.forEach { entry ->
        HistoryRow(
            entry = entry,
            onCopy = {
                onCopyTranscript(entry.text)
            },
        )
    }
}

@Composable
private fun ModelRow(modelFootnote: String) {
    val palette = LocalMacPalette.current
    SettingsGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(text = "LiquidAI LFM3-Audio-2B", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = modelFootnote,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.label,
            )
        }
    }
}

@Composable
private fun StatusDot(screenState: DictationScreenState) {
    val palette = LocalMacPalette.current
    val color = when {
        screenState.isListening -> palette.recording
        screenState.isTranscribing -> palette.working
        screenState.isSpeaking -> palette.accent
        screenState.enginePhase == DictationEnginePhase.Ready -> palette.ready
        else -> palette.secondary
    }
    val label = when {
        screenState.isListening -> "Listening"
        screenState.isTranscribing -> "Writing"
        screenState.isSpeaking -> "Speaking"
        screenState.enginePhase == DictationEnginePhase.Ready -> "Ready"
        else -> "Not ready"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ShortcutRow(
    screenState: DictationScreenState,
    onBeginShortcutRecording: () -> Unit,
) {
    val palette = LocalMacPalette.current
    SettingsGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Keyboard shortcut", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (screenState.isRecordingShortcut) {
                        "Press the keys now. Escape cancels."
                    } else {
                        "Click the shortcut, then press the keys you want."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val chipColor = if (screenState.isRecordingShortcut) {
                palette.accent
            } else {
                palette.separator
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, chipColor, RoundedCornerShape(8.dp))
                    .background(palette.field)
                    .clickable {
                        onBeginShortcutRecording()
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = if (screenState.isRecordingShortcut) {
                        "Recording"
                    } else {
                        screenState.shortcutLabel
                    },
                    color = palette.label,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun LoginRow(
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = LocalMacPalette.current
    SettingsGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = { checked ->
                    onCheckedChange(checked)
                },
                colors = CheckboxDefaults.colors(
                    checkedColor = palette.accent,
                    checkmarkColor = palette.onAccent,
                    uncheckedColor = palette.secondary,
                ),
            )
            Text(
                text = "Open Rvoice when you log in",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun AppearanceRow(
    appearanceMode: String,
    onAppearanceSelected: (String) -> Unit,
) {
    val palette = LocalMacPalette.current
    Column {
        Text(text = "Appearance", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(palette.card)
                .padding(3.dp),
        ) {
            listOf(
                "dark" to "Dark",
                "light" to "Light",
                "system" to "System",
            ).forEach { choice ->
                val isSelected = appearanceMode == choice.first
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isSelected) {
                                palette.accent
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable {
                            onAppearanceSelected(choice.first)
                        }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = choice.second,
                        color = if (isSelected) {
                            palette.onAccent
                        } else {
                            palette.label
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(LocalMacPalette.current.card),
    ) {
        content()
    }
}

@Composable
private fun NoticeCard(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    val palette = LocalMacPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.card)
            .padding(14.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = body, style = MaterialTheme.typography.bodyMedium, color = palette.label)
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = palette.onAccent,
                ),
            ) {
                Text(
                    text = actionLabel,
                    color = palette.onAccent,
                )
            }
        }
    }
}

@Composable
private fun MicrophonePicker(
    screenState: DictationScreenState,
    onMicrophoneSelected: (microphoneId: String, microphoneName: String) -> Unit,
) {
    val palette = LocalMacPalette.current
    var isMenuOpen by remember { mutableStateOf(false) }
    Column {
        Text(text = "Microphone", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(6.dp))
        Box {
            TextButton(
                onClick = {
                    isMenuOpen = true
                },
            ) {
                Text(
                    text = screenState.selectedMicrophoneName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.accent,
                )
            }
            DropdownMenu(
                expanded = isMenuOpen,
                onDismissRequest = {
                    isMenuOpen = false
                },
            ) {
                DropdownMenuItem(
                    text = {
                        Text("System default")
                    },
                    onClick = {
                        isMenuOpen = false
                        onMicrophoneSelected("", "System default")
                    },
                )
                screenState.microphoneChoices.forEach { microphone ->
                    DropdownMenuItem(
                        text = {
                            Text(microphone.name)
                        },
                        onClick = {
                            isMenuOpen = false
                            onMicrophoneSelected(microphone.id, microphone.name)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguagePicker(
    selectedLanguageEnglishName: String,
    onLanguageSelected: (DictationLanguage) -> Unit,
) {
    val palette = LocalMacPalette.current
    var isMenuOpen by remember { mutableStateOf(false) }
    val selectedLanguage = dictationLanguages.firstOrNull { language ->
        language.englishName == selectedLanguageEnglishName
    } ?: dictationLanguages[1]
    Column {
        Text(text = "Language", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(6.dp))
        Box {
            TextButton(
                onClick = {
                    isMenuOpen = true
                },
            ) {
                Text(selectedLanguage.menuLabel, style = MaterialTheme.typography.bodyLarge, color = palette.accent)
            }
            DropdownMenu(
                expanded = isMenuOpen,
                onDismissRequest = {
                    isMenuOpen = false
                },
            ) {
                dictationLanguages.forEach { language ->
                    DropdownMenuItem(
                        text = {
                            Text(language.menuLabel)
                        },
                        onClick = {
                            isMenuOpen = false
                            onLanguageSelected(language)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Fixes for words the model keeps getting wrong, such as a brand name. The form at the top adds a fix; each row below reads "misheard spellings → correct word" and can be removed.
 */
@Composable
private fun WordReplacementsPage(
    wordReplacements: List<WordReplacement>,
    onAddWordReplacement: (misheardSpellingsText: String, correctWord: String) -> Unit,
    onEditWordReplacement: (wordReplacementId: Long, misheardSpellingsText: String, correctWord: String) -> Unit,
    onRemoveWordReplacement: (wordReplacementId: Long) -> Unit,
) {
    val palette = LocalMacPalette.current
    var idOfWordReplacementBeingEdited by remember { mutableStateOf<Long?>(null) }
    var misheardSpellingsText by remember { mutableStateOf("") }
    var correctWordText by remember { mutableStateOf("") }
    val canAddWordReplacement = misheardSpellingsFromCommaSeparatedText(misheardSpellingsText).isNotEmpty() &&
        correctWordText.isNotBlank()
    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = palette.label,
        unfocusedTextColor = palette.label,
        focusedBorderColor = palette.accent,
        unfocusedBorderColor = palette.separator,
        cursorColor = palette.accent,
        focusedContainerColor = palette.field,
        unfocusedContainerColor = palette.field,
    )
    TabIntroduction(
        text = "When the model writes a word wrong, Rvoice types your spelling instead. Capitals, spaces and hyphens the model adds are ignored.",
    )
    SettingsGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = "Add a replacement", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = misheardSpellingsText,
                onValueChange = { updatedText ->
                    misheardSpellingsText = updatedText
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = textFieldColors,
                label = {
                    Text("When Rvoice hears")
                },
                placeholder = {
                    Text("Peacopals, Peako pals", style = MaterialTheme.typography.bodyMedium)
                },
                supportingText = {
                    Text("Separate several spellings with commas.", style = MaterialTheme.typography.bodyMedium)
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = correctWordText,
                    onValueChange = { updatedText ->
                        correctWordText = updatedText
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = textFieldColors,
                    label = {
                        Text("Type instead")
                    },
                    placeholder = {
                        Text("Peekopals", style = MaterialTheme.typography.bodyMedium)
                    },
                )
                Button(
                    onClick = {
                        onAddWordReplacement(misheardSpellingsText, correctWordText)
                        misheardSpellingsText = ""
                        correctWordText = ""
                    },
                    enabled = canAddWordReplacement,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = palette.onAccent,
                    ),
                ) {
                    Text("Add")
                }
            }
        }
    }
    Text(text = "Your replacements", style = MaterialTheme.typography.labelLarge)
    if (wordReplacements.isEmpty()) {
        EmptyListMessage(title = "No replacements yet", body = "Add a word above and it will be fixed in every dictation.")
        return
    }
    SettingsGroup {
        wordReplacements.forEachIndexed { rowIndex, wordReplacement ->
            if (rowIndex > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp)
                        .height(1.dp)
                        .background(palette.separator),
                )
            }
            if (idOfWordReplacementBeingEdited == wordReplacement.id) {
                WordReplacementEditor(
                    wordReplacement = wordReplacement,
                    textFieldColors = textFieldColors,
                    onSave = { editedSpellingsText, editedCorrectWord ->
                        onEditWordReplacement(wordReplacement.id, editedSpellingsText, editedCorrectWord)
                        idOfWordReplacementBeingEdited = null
                    },
                    onCancel = {
                        idOfWordReplacementBeingEdited = null
                    },
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = wordReplacement.correctWord, style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Replaces " + wordReplacement.misheardSpellings.joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    IconActionButton(icon = Icons.Outlined.Edit, description = "Edit", tint = palette.accent) {
                        idOfWordReplacementBeingEdited = wordReplacement.id
                    }
                    IconActionButton(icon = Icons.Outlined.Delete, description = "Remove", tint = palette.recording) {
                        onRemoveWordReplacement(wordReplacement.id)
                    }
                }
            }
        }
    }
}

/**
 * One word fix opened for editing: the misheard spellings (comma separated) and the correct word, with Save and Cancel. Save is off while either box is empty.
 */
@Composable
private fun WordReplacementEditor(
    wordReplacement: WordReplacement,
    textFieldColors: androidx.compose.material3.TextFieldColors,
    onSave: (misheardSpellingsText: String, correctWord: String) -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalMacPalette.current
    var editedSpellingsText by remember(wordReplacement.id) { mutableStateOf(wordReplacement.misheardSpellings.joinToString(", ")) }
    var editedCorrectWord by remember(wordReplacement.id) { mutableStateOf(wordReplacement.correctWord) }
    val canSave = misheardSpellingsFromCommaSeparatedText(editedSpellingsText).isNotEmpty() && editedCorrectWord.isNotBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = editedSpellingsText,
            onValueChange = { updatedText ->
                editedSpellingsText = updatedText
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = textFieldColors,
            label = {
                Text("When Rvoice hears")
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = editedCorrectWord,
                onValueChange = { updatedText ->
                    editedCorrectWord = updatedText
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = textFieldColors,
                label = {
                    Text("Type instead")
                },
            )
            TextButton(onClick = onCancel) {
                Text("Cancel", color = palette.secondary)
            }
            Button(
                onClick = {
                    onSave(editedSpellingsText, editedCorrectWord)
                },
                enabled = canSave,
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = palette.onAccent,
                ),
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: DictationTranscriptEntry,
    onCopy: () -> Unit,
) {
    val palette = LocalMacPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.card)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.text, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(4.dp))
            val whereItWent = if (entry.didInsertIntoFocusedText) {
                "Typed into the focused field"
            } else {
                "Copied to the clipboard"
            }
            Text(
                text = "${entry.spokenAtLabel}  ·  $whereItWent",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        IconActionButton(icon = Icons.Outlined.ContentCopy, description = "Copy", tint = palette.accent, onClick = onCopy)
    }
}
