package com.reaptivevoice.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.json.JSONObject
import java.io.File

fun main() {
    System.setProperty("apple.awt.application.name", "Rvoice")
    System.setProperty("apple.awt.enableTemplateImages", "true")
    applySavedMacAppearance()
    val coordinator = DictationCoordinator()
    coordinator.start()
    Runtime.getRuntime().addShutdownHook(
        Thread {
            coordinator.shutdown()
        },
    )
    application {
        val isSystemInDarkTheme = isSystemInDarkTheme()
        var isSettingsWindowOpen by remember { mutableStateOf(true) }
        var bringWindowToFrontRequestCount by remember { mutableStateOf(0) }
        val showRvoiceWindowInFront = {
            isSettingsWindowOpen = true
            bringWindowToFrontRequestCount++
        }
        LaunchedEffect(Unit) {
            reopenWindowWhenDockIconIsClicked {
                showRvoiceWindowInFront()
            }
        }
        Tray(
            icon = reaptiveVoiceMenuBarIcon(),
            menu = {
                Item(
                    text = "Open Rvoice",
                    onClick = {
                        showRvoiceWindowInFront()
                    },
                )
                Item(
                    text = "Speak clipboard",
                    onClick = {
                        coordinator.speakClipboardOutLoud()
                    },
                )
                Item(
                    text = "Stop speaking",
                    onClick = {
                        coordinator.stopSpeaking()
                    },
                )
                Separator()
                Item(
                    text = "Quit",
                    onClick = {
                        coordinator.shutdown()
                        exitApplication()
                    },
                )
            },
        )
        if (isSettingsWindowOpen) {
            Window(
                onCloseRequest = {
                    isSettingsWindowOpen = false
                },
                state = rememberWindowState(width = 720.dp, height = 820.dp),
                title = "Rvoice",
                onKeyEvent = { keyEvent ->
                    handleWindowKey(coordinator, keyEvent)
                },
            ) {
                LaunchedEffect(bringWindowToFrontRequestCount) {
                    bringWindowToFrontAndActivateRvoice(window)
                }
                LaunchedEffect(Unit) {
                    val testDestination = System.getenv("RVOICE_TEST_SAVE_MP3_TO")
                    if (testDestination != null) {
                        while (!coordinator.screenState.isSpeakingVoiceReady) {
                            kotlinx.coroutines.delay(500)
                        }
                        coordinator.saveTextAsMp3("This MP3 was saved to a folder chosen in the save window.", File(testDestination))
                        System.err.println("RVOICE-DEBUG save requested to $testDestination")
                    }
                }
                val screenState = coordinator.screenState
                DictationScreen(
                    screenState = screenState,
                    isSystemInDarkTheme = isSystemInDarkTheme,
                    onLanguageSelected = { language ->
                        coordinator.selectLanguage(language)
                    },
                    onDraftChange = { text, selectionStart, selectionEnd ->
                        coordinator.replaceDraft(text, selectionStart, selectionEnd)
                    },
                    onRequestAccessibility = {
                        coordinator.promptForAccessibility()
                    },
                    onCopyTranscript = { transcript ->
                        coordinator.copyTranscript(transcript)
                    },
                    onBeginShortcutRecording = {
                        coordinator.beginShortcutRecording()
                    },
                    onOpenAtLoginChange = { isEnabled ->
                        coordinator.setOpenAtLogin(isEnabled)
                    },
                    onAppearanceSelected = { appearanceMode ->
                        coordinator.selectAppearance(appearanceMode)
                    },
                    onMicrophoneSelected = { microphoneId, microphoneName ->
                        coordinator.selectMicrophone(microphoneId, microphoneName)
                    },
                    onAddWordReplacement = { misheardSpellingsText, correctWord ->
                        coordinator.addWordReplacement(misheardSpellingsText, correctWord)
                    },
                    onEditWordReplacement = { wordReplacementId, misheardSpellingsText, correctWord ->
                        coordinator.saveEditedWordReplacement(wordReplacementId, misheardSpellingsText, correctWord)
                    },
                    onRemoveWordReplacement = { wordReplacementId ->
                        coordinator.removeWordReplacement(wordReplacementId)
                    },
                    onSpeakText = { text ->
                        coordinator.speakTextOutLoud(text)
                    },
                    onSpeakClipboard = {
                        coordinator.speakClipboardOutLoud()
                    },
                    onStopSpeaking = {
                        coordinator.stopSpeaking()
                    },
                    onSpeakingVoiceSelected = { voice ->
                        coordinator.selectSpeakingVoice(voice)
                    },
                    onPreviewSpeakingVoice = { voice ->
                        coordinator.previewSpeakingVoice(voice)
                    },
                    onToggleSpeechPause = {
                        coordinator.toggleSpeechPause()
                    },
                    onRewindSpeech = {
                        coordinator.rewindSpeech()
                    },
                    onSaveTextAsMp3 = { text ->
                        System.err.println("RVOICE-DEBUG save clicked, text length ${text.length}")
                        val destination = askWhereToSaveWithMacSaveWindow(window, "Save MP3", coordinator.suggestedDownloadFileNameForText(text))
                        if (destination != null) {
                            coordinator.saveTextAsMp3(text, destination)
                        }
                    },
                    onCancelSavingMp3 = {
                        coordinator.cancelSavingMp3()
                    },
                    onSpeakingSpeedSelected = { speed ->
                        coordinator.selectSpeakingSpeed(speed)
                    },
                    onOpenSavedAudio = { savedAudio ->
                        coordinator.openSavedAudio(savedAudio)
                    },
                    onDownloadSavedAudio = { savedAudio ->
                        val destination = askWhereToSaveWithMacSaveWindow(window, "Download MP3", coordinator.suggestedDownloadFileName(savedAudio))
                        if (destination != null) {
                            coordinator.downloadSavedAudio(savedAudio, destination)
                        }
                    },
                    onDeleteSavedAudio = { savedAudio ->
                        coordinator.deleteSavedAudio(savedAudio)
                    },
                    onRetryStartingVoice = {
                        coordinator.retryStartingSpeakingVoice()
                    },
                    onToggleSavedAudioPlayback = {
                        coordinator.toggleSavedAudioPlayback()
                    },
                    onSkipSavedAudioPlayback = { seconds ->
                        coordinator.skipSavedAudioPlayback(seconds)
                    },
                    onSeekSavedAudioPlayback = { positionInSeconds ->
                        coordinator.seekSavedAudioPlayback(positionInSeconds)
                    },
                    onCloseSavedAudioPlayer = {
                        coordinator.closeSavedAudioPlayer()
                    },
                )
            }
        }
    }
}

/**
 * Makes the window visible, un-minimises it, raises it above other windows and makes Rvoice the active app. Used by "Open Rvoice" and the Dock icon, where the window may already be open but hidden behind other apps.
 */
private fun bringWindowToFrontAndActivateRvoice(window: java.awt.Window) {
    window.isVisible = true
    if (window is java.awt.Frame && (window.extendedState and java.awt.Frame.ICONIFIED) != 0) {
        window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
    }
    if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND)) {
        java.awt.Desktop.getDesktop().requestForeground(true)
    }
    window.toFront()
    window.requestFocus()
}

/**
 * Clicking Rvoice's Dock icon while it is running opens the window, as Mac apps do. Does nothing where the system does not report Dock clicks.
 */
private fun reopenWindowWhenDockIconIsClicked(showWindow: () -> Unit) {
    if (!java.awt.Desktop.isDesktopSupported()) {
        return
    }
    runCatching {
        java.awt.Desktop.getDesktop().addAppEventListener(
            java.awt.desktop.AppReopenedListener {
                showWindow()
            },
        )
    }
}

/**
 * Shows the standard macOS save window over Rvoice's window, starting in Downloads with the suggested file name. Returns the chosen file, or null when the person cancels. Attaching it to the Rvoice window keeps it from opening behind other windows.
 */
private fun askWhereToSaveWithMacSaveWindow(ownerWindow: java.awt.Frame, saveWindowTitle: String, suggestedFileName: String): File? {
    val saveWindow = java.awt.FileDialog(ownerWindow, saveWindowTitle, java.awt.FileDialog.SAVE)
    saveWindow.directory = File(System.getProperty("user.home"), "Downloads").absolutePath
    saveWindow.file = suggestedFileName
    System.err.println("RVOICE-DEBUG showing save window, owner visible=${ownerWindow.isVisible}")
    saveWindow.isVisible = true
    System.err.println("RVOICE-DEBUG save window closed: ${saveWindow.directory} ${saveWindow.file}")
    val chosenDirectory = saveWindow.directory ?: return null
    val chosenFileName = saveWindow.file ?: return null
    val fileNameWithExtension = if (chosenFileName.endsWith(".mp3", ignoreCase = true)) chosenFileName else "$chosenFileName.mp3"
    return File(chosenDirectory, fileNameWithExtension)
}

private fun handleWindowKey(coordinator: DictationCoordinator, keyEvent: androidx.compose.ui.input.key.KeyEvent): Boolean {
    if (keyEvent.type != KeyEventType.KeyDown) {
        return false
    }
    if (coordinator.screenState.isRecordingShortcut) {
        val isBareEscape = keyEvent.key == Key.Escape && carbonModifiersFrom(keyEvent) == 0
        if (isBareEscape) {
            coordinator.cancelShortcutRecording()
            return true
        }
        val modifiers = carbonModifiersFrom(keyEvent)
        val keyCode = macVirtualKeyCodeFor(keyEvent.key)
        if (modifiers == 0 || keyCode == null) {
            return true
        }
        coordinator.applyRecordedShortcut(
            keyCode = keyCode,
            modifiers = modifiers,
            label = shortcutLabelFor(keyEvent.key, modifiers),
        )
        return true
    }
    val screenState = coordinator.screenState
    if (keyEvent.key == Key.Escape && (screenState.isListening || screenState.isTranscribing)) {
        coordinator.cancelDictationFromEscapeKey()
        return true
    }
    val pressedKeyCode = macVirtualKeyCodeFor(keyEvent.key)
    val isSavedShortcut = !screenState.isGlobalHotkeyRegistered &&
        pressedKeyCode == screenState.shortcutKeyCode &&
        carbonModifiersFrom(keyEvent) == screenState.shortcutModifiers
    if (isSavedShortcut) {
        coordinator.onCommandDInsideThisWindow()
        return true
    }
    return false
}

private fun applySavedMacAppearance() {
    val appearanceMode = savedAppearanceMode()
    val useDarkChrome = when (appearanceMode) {
        "light" -> false
        "system" -> isMacInterfaceDark()
        else -> true
    }
    val appearanceName = if (useDarkChrome) {
        "NSAppearanceNameDarkAqua"
    } else {
        "NSAppearanceNameAqua"
    }
    System.setProperty("apple.awt.application.appearance", appearanceName)
}

private fun savedAppearanceMode(): String {
    val settingsFile = File(System.getProperty("user.home"), ".reaptive-voice/settings.json")
    if (!settingsFile.isFile) {
        return "dark"
    }
    return try {
        JSONObject(settingsFile.readText()).optString("appearanceMode", "dark").ifBlank { "dark" }
    } catch (_: Exception) {
        "dark"
    }
}

private fun isMacInterfaceDark(): Boolean {
    val process = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.readBytes().decodeToString().trim()
    process.waitFor()
    return text.equals("Dark", ignoreCase = true)
}
