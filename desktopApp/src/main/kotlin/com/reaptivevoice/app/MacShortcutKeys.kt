package com.reaptivevoice.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed

/**
 * Turns a key pressed in this window into the Mac virtual key code the shortcut helper registers.
 */
fun macVirtualKeyCodeFor(key: Key): Int? {
    return when (key) {
        Key.A -> 0
        Key.S -> 1
        Key.D -> 2
        Key.F -> 3
        Key.H -> 4
        Key.G -> 5
        Key.Z -> 6
        Key.X -> 7
        Key.C -> 8
        Key.V -> 9
        Key.B -> 11
        Key.Q -> 12
        Key.W -> 13
        Key.E -> 14
        Key.R -> 15
        Key.Y -> 16
        Key.T -> 17
        Key.One -> 18
        Key.Two -> 19
        Key.Three -> 20
        Key.Four -> 21
        Key.Six -> 22
        Key.Five -> 23
        Key.Equals -> 24
        Key.Nine -> 25
        Key.Seven -> 26
        Key.Minus -> 27
        Key.Eight -> 28
        Key.Zero -> 29
        Key.RightBracket -> 30
        Key.O -> 31
        Key.U -> 32
        Key.LeftBracket -> 33
        Key.I -> 34
        Key.P -> 35
        Key.Enter -> 36
        Key.L -> 37
        Key.J -> 38
        Key.Apostrophe -> 39
        Key.K -> 40
        Key.Semicolon -> 41
        Key.Backslash -> 42
        Key.Comma -> 43
        Key.Slash -> 44
        Key.N -> 45
        Key.M -> 46
        Key.Period -> 47
        Key.Tab -> 48
        Key.Spacebar -> 49
        Key.Backspace -> 51
        Key.Grave -> 50
        Key.Escape -> 53
        Key.DirectionLeft -> 123
        Key.DirectionRight -> 124
        Key.DirectionDown -> 125
        Key.DirectionUp -> 126
        Key.F1 -> 122
        Key.F2 -> 120
        Key.F3 -> 99
        Key.F4 -> 118
        Key.F5 -> 96
        Key.F6 -> 97
        Key.F7 -> 98
        Key.F8 -> 100
        Key.F9 -> 101
        Key.F10 -> 109
        Key.F11 -> 103
        Key.F12 -> 111
        else -> null
    }
}

fun carbonModifiersFrom(keyEvent: KeyEvent): Int {
    var modifiers = 0
    if (keyEvent.isMetaPressed) {
        modifiers = modifiers or MAC_COMMAND_MODIFIER
    }
    if (keyEvent.isShiftPressed) {
        modifiers = modifiers or MAC_SHIFT_MODIFIER
    }
    if (keyEvent.isAltPressed) {
        modifiers = modifiers or MAC_OPTION_MODIFIER
    }
    if (keyEvent.isCtrlPressed) {
        modifiers = modifiers or MAC_CONTROL_MODIFIER
    }
    return modifiers
}

fun shortcutLabelFor(key: Key, modifiers: Int): String {
    val symbols = buildString {
        if (modifiers and MAC_CONTROL_MODIFIER != 0) {
            append("⌃")
        }
        if (modifiers and MAC_OPTION_MODIFIER != 0) {
            append("⌥")
        }
        if (modifiers and MAC_SHIFT_MODIFIER != 0) {
            append("⇧")
        }
        if (modifiers and MAC_COMMAND_MODIFIER != 0) {
            append("⌘")
        }
        append(shortcutKeyName(key))
    }
    return symbols
}

private fun shortcutKeyName(key: Key): String {
    return when (key) {
        Key.A -> "A"
        Key.B -> "B"
        Key.C -> "C"
        Key.D -> "D"
        Key.E -> "E"
        Key.F -> "F"
        Key.G -> "G"
        Key.H -> "H"
        Key.I -> "I"
        Key.J -> "J"
        Key.K -> "K"
        Key.L -> "L"
        Key.M -> "M"
        Key.N -> "N"
        Key.O -> "O"
        Key.P -> "P"
        Key.Q -> "Q"
        Key.R -> "R"
        Key.S -> "S"
        Key.T -> "T"
        Key.U -> "U"
        Key.V -> "V"
        Key.W -> "W"
        Key.X -> "X"
        Key.Y -> "Y"
        Key.Z -> "Z"
        Key.Zero -> "0"
        Key.One -> "1"
        Key.Two -> "2"
        Key.Three -> "3"
        Key.Four -> "4"
        Key.Five -> "5"
        Key.Six -> "6"
        Key.Seven -> "7"
        Key.Eight -> "8"
        Key.Nine -> "9"
        Key.F1 -> "F1"
        Key.F2 -> "F2"
        Key.F3 -> "F3"
        Key.F4 -> "F4"
        Key.F5 -> "F5"
        Key.F6 -> "F6"
        Key.F7 -> "F7"
        Key.F8 -> "F8"
        Key.F9 -> "F9"
        Key.F10 -> "F10"
        Key.F11 -> "F11"
        Key.F12 -> "F12"
        Key.Spacebar -> "Space"
        Key.Enter -> "Return"
        Key.Tab -> "Tab"
        Key.Backspace -> "Delete"
        Key.Escape -> "Escape"
        Key.DirectionLeft -> "Left"
        Key.DirectionRight -> "Right"
        Key.DirectionDown -> "Down"
        Key.DirectionUp -> "Up"
        Key.Minus -> "-"
        Key.Equals -> "="
        Key.LeftBracket -> "["
        Key.RightBracket -> "]"
        Key.Backslash -> "\\"
        Key.Semicolon -> ";"
        Key.Apostrophe -> "'"
        Key.Comma -> ","
        Key.Period -> "."
        Key.Slash -> "/"
        Key.Grave -> "`"
        else -> "Key"
    }
}
