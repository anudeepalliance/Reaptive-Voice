package com.reaptivevoice.app

/**
 * The global shortcut that starts and stops a dictation take. Key code and modifiers are the Mac virtual key and the Carbon modifier flags.
 */
data class DictationShortcut(
    val keyCode: Int,
    val modifiers: Int,
    val label: String,
)

const val MAC_COMMAND_MODIFIER = 256
const val MAC_SHIFT_MODIFIER = 512
const val MAC_OPTION_MODIFIER = 2048
const val MAC_CONTROL_MODIFIER = 4096

fun defaultDictationShortcut(): DictationShortcut {
    return DictationShortcut(
        keyCode = 2,
        modifiers = MAC_COMMAND_MODIFIER,
        label = "⌘D",
    )
}

fun describeDictationShortcut(label: String): String {
    return "Press $label in any text field. Press it again to insert what you said."
}
