import AppKit
import ApplicationServices
import AVFoundation
import Carbon
import Foundation

/// True when the focused field contains only the words this take already typed, ignoring a trailing space.
func fieldContainsOnlyPreviousDictation(_ fieldText: String, previousText: String) -> Bool {
    let field = fieldText.trimmingCharacters(in: .whitespacesAndNewlines)
    let previous = previousText.trimmingCharacters(in: .whitespacesAndNewlines)
    return !previous.isEmpty && field == previous
}

/// How many characters at the start of both texts are identical, counting each visible character (an emoji counts as one). Live dictation uses it to erase and retype only the words that changed.
func sharedLeadingCharacterCount(_ firstText: String, _ secondText: String) -> Int {
    var sharedCount = 0
    for (firstCharacter, secondCharacter) in zip(firstText, secondText) {
        if firstCharacter != secondCharacter {
            break
        }
        sharedCount += 1
    }
    return sharedCount
}

/// True when the field holds the expected words, ignoring all spaces and line breaks. Terminals wrap long lines, so the words can be split across lines on screen.
func textContainsIgnoringLineWrapping(_ fieldText: String, _ expectedText: String) -> Bool {
    let fieldWithoutSpaces = fieldText.filter { character in
        !character.isWhitespace
    }
    let expectedWithoutSpaces = expectedText.filter { character in
        !character.isWhitespace
    }
    return !expectedWithoutSpaces.isEmpty && fieldWithoutSpaces.contains(expectedWithoutSpaces)
}

/// Turns line breaks and tabs into spaces so a dictated sentence can never press Enter (run a command) or Tab (autocomplete) in a terminal.
func textSafeForTerminal(_ text: String) -> String {
    let collapsedText = text.components(separatedBy: CharacterSet(charactersIn: "\r\n\t")).joined(separator: " ")
    return collapsedText
}

/// Listens for ⌘D anywhere on the Mac, remembers the text field that was focused, and later types the transcript into that field. It also draws the small listening pill. The pill is a non-activating panel, so it does not pull focus away from the app the person was typing in.
final class DictationHelper: NSObject {
    static let shared = DictationHelper()

    private var mode = "idle"
    private var capturedTextElement: AXUIElement?
    private var previouslyTypedText = ""
    private var textLastPutOnClipboard = ""
    private var processThatReceivedThisTake = pid_t(0)
    /// The window holding the field this take types into, so Rvoice can return to it if the person moves to another app before the final text arrives.
    private var windowOfTheTakesField: AXUIElement?
    /// Where in the field this take's text starts (in UTF-16 units), read just before the first words are typed. Nil when the app does not report it.
    private var takeTextStartLocationInField: Int?
    private var mayCursorHaveMovedInTheTakesField = false
    private var clickAndKeyWatcher: Any?
    private var clipboardTextBeforeTake: String?
    private var didSaveClipboardForTake = false
    private var clipboardRestoreGeneration = 0
    private var hotKeyRef: EventHotKeyRef?
    private var keyboardTap: CFMachPort?
    private var didInstallHotkeyHandler = false
    private var shortcutKeyCode = UInt32(kVK_ANSI_D)
    private var shortcutModifiers = UInt32(cmdKey)
    private var eventSpec = EventTypeSpec(
        eventClass: OSType(kEventClassKeyboard),
        eventKind: UInt32(kEventHotKeyPressed)
    )
    private let parentProcessId: pid_t
    private var lastAccessibilityTrusted = false
    private let pillPanel = ListeningPillPanel()

    private override init() {
        let parentFromEnvironment = ProcessInfo.processInfo.environment["REAPTIVE_VOICE_PARENT_PID"].flatMap { pidString in
            Int32(pidString)
        }
        parentProcessId = parentFromEnvironment ?? getppid()
        super.init()
    }

    func start() {
        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)
        lastAccessibilityTrusted = AXIsProcessTrusted()
        if !lastAccessibilityTrusted {
            promptForAccessibility()
            lastAccessibilityTrusted = AXIsProcessTrusted()
        }
        let hotkeyError = registerCurrentShortcut()
        startReadingCommands()
        watchForClicksAndKeysFromThePerson()
        offerSpeakWithRvoiceInRightClickMenus()
        let accessibilityTimer = Timer(timeInterval: 2.0, repeats: true) { [weak self] _ in
            self?.reportAccessibilityIfItChanged()
        }
        RunLoop.main.add(accessibilityTimer, forMode: .common)
        emit([
            "type": "ready",
            "accessibilityTrusted": lastAccessibilityTrusted,
            "hotkeyRegistered": hotkeyError == nil,
            "hotkeyError": hotkeyError ?? "",
        ])
        app.run()
    }

    private func registerCurrentShortcut() -> String? {
        if let hotKeyRef {
            UnregisterEventHotKey(hotKeyRef)
            self.hotKeyRef = nil
        }
        let hotKeyId = EventHotKeyID(signature: OSType(0x5256_4448), id: 1)
        var registeredHotkey: EventHotKeyRef?
        let status = RegisterEventHotKey(
            shortcutKeyCode,
            shortcutModifiers,
            hotKeyId,
            GetApplicationEventTarget(),
            0,
            &registeredHotkey
        )
        if status != noErr {
            let tapMessage = installEventTap()
            if tapMessage.starts(with: "Using a keyboard tap") {
                return nil
            }
            return "RegisterEventHotKey failed with status \(status). \(tapMessage)"
        }
        hotKeyRef = registeredHotkey
        if let keyboardTap {
            CGEvent.tapEnable(tap: keyboardTap, enable: false)
        }
        if didInstallHotkeyHandler {
            return nil
        }
        let handlerStatus = InstallEventHandler(
            GetApplicationEventTarget(),
            { _, event, _ -> OSStatus in
                var pressedHotKeyId = EventHotKeyID()
                GetEventParameter(
                    event,
                    EventParamName(kEventParamDirectObject),
                    EventParamType(typeEventHotKeyID),
                    nil,
                    MemoryLayout<EventHotKeyID>.size,
                    nil,
                    &pressedHotKeyId
                )
                let wasEscapePressed = pressedHotKeyId.id == DictationHelper.escapeHotKeyNumber
                let deliver = {
                    if wasEscapePressed {
                        DictationHelper.shared.escapePressedDuringDictation()
                    } else {
                        DictationHelper.shared.hotkeyPressed(focusedElement: nil)
                    }
                }
                if Thread.isMainThread {
                    deliver()
                } else {
                    DispatchQueue.main.async(execute: deliver)
                }
                return noErr
            },
            1,
            &eventSpec,
            nil,
            nil
        )
        if handlerStatus != noErr {
            return "The shortcut handler was not installed (status \(handlerStatus))."
        }
        didInstallHotkeyHandler = true
        return nil
    }

    /// Used only when the normal hotkey registration fails. A tap can see ⌘D, but macOS will ask for Accessibility first.
    private func installEventTap() -> String {
        if let keyboardTap {
            CGEvent.tapEnable(tap: keyboardTap, enable: true)
            return "Using a keyboard tap instead."
        }
        let eventMask = CGEventMask(1 << CGEventType.keyDown.rawValue)
        guard let tap = CGEvent.tapCreate(
            tap: .cgSessionEventTap,
            place: .headInsertEventTap,
            options: .defaultTap,
            eventsOfInterest: eventMask,
            callback: { _, type, event, _ in
                if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
                    return Unmanaged.passUnretained(event)
                }
                if event.getIntegerValueField(.keyboardEventAutorepeat) != 0 {
                    return Unmanaged.passUnretained(event)
                }
                let keyCode = event.getIntegerValueField(.keyboardEventKeycode)
                let flags = event.flags
                if DictationHelper.shared.matchesShortcut(keyCode: keyCode, flags: flags) {
                    DispatchQueue.main.async {
                        DictationHelper.shared.hotkeyPressed(focusedElement: nil)
                    }
                    return nil
                }
                return Unmanaged.passUnretained(event)
            },
            userInfo: nil
        ) else {
            return "Could not watch the keyboard. Allow Accessibility for Rvoice."
        }
        keyboardTap = tap
        let source = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0)
        CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
        CGEvent.tapEnable(tap: tap, enable: true)
        return "Using a keyboard tap instead."
    }

    /// Notices when the person clicks or types after this take has started typing. Either one can move the cursor, and Rvoice's erase-and-retype only works while the cursor is still right after its own words, so it stops typing for the rest of the take. Keys Rvoice posts itself and the dictation shortcut are ignored.
    private func watchForClicksAndKeysFromThePerson() {
        clickAndKeyWatcher = NSEvent.addGlobalMonitorForEvents(
            matching: [.leftMouseDown, .rightMouseDown, .otherMouseDown, .keyDown]
        ) { [weak self] event in
            self?.rememberClickOrKeyFromThePerson(event)
        }
    }

    private func rememberClickOrKeyFromThePerson(_ event: NSEvent) {
        let escapeKeyCode: UInt16 = 53
        if event.type == .keyDown && event.keyCode == escapeKeyCode && (mode == "listening" || mode == "busy") {
            cancelDictationBecauseEscapeWasPressed()
            return
        }
        if previouslyTypedText.isEmpty {
            return
        }
        if event.type == .keyDown {
            let postingProcessId = event.cgEvent?.getIntegerValueField(.eventSourceUnixProcessID) ?? 0
            if postingProcessId == Int64(getpid()) {
                return
            }
            if matchesShortcut(keyCode: Int64(event.keyCode), flags: event.cgEvent?.flags ?? []) {
                return
            }
        }
        /* Clicks and keys in other apps cannot move the cursor in this take's field, so only the take's own app counts. */
        if processThatReceivedThisTake != 0 && appThatReceivesEvent(event) != processThatReceivedThisTake {
            return
        }
        mayCursorHaveMovedInTheTakesField = true
    }

    /// Which app a click or key press goes to: for a click, the owner of the window under it; for a key press, the app in front.
    private func appThatReceivesEvent(_ event: NSEvent) -> pid_t {
        if event.type != .keyDown && event.windowNumber > 0,
           let windowDetails = CGWindowListCopyWindowInfo([.optionIncludingWindow], CGWindowID(event.windowNumber)) as? [[String: Any]],
           let ownerProcessId = windowDetails.first?[kCGWindowOwnerPID as String] as? pid_t {
            return ownerProcessId
        }
        return NSWorkspace.shared.frontmostApplication?.processIdentifier ?? 0
    }

    /// Notes the app and window of the field focused when the take starts, so the final text can be delivered there even if the person has moved on.
    private func rememberWhichAppAndWindowThisTakeTypesInto() {
        var fieldOwner = pid_t(0)
        if let capturedTextElement {
            AXUIElementGetPid(capturedTextElement, &fieldOwner)
            var rawWindow: CFTypeRef?
            if AXUIElementCopyAttributeValue(capturedTextElement, kAXWindowAttribute as CFString, &rawWindow) == .success, let rawWindow {
                windowOfTheTakesField = (rawWindow as! AXUIElement)
            }
        }
        if fieldOwner == 0 {
            fieldOwner = NSWorkspace.shared.frontmostApplication?.processIdentifier ?? 0
        }
        processThatReceivedThisTake = fieldOwner
    }

    static let escapeHotKeyNumber: UInt32 = 2
    private var escapeHotKeyRef: EventHotKeyRef?

    /// Claims Escape as a shortcut for as long as a dictation runs, so it can cancel the take from any app. Shortcuts like this work without Accessibility, unlike watching the keyboard. Released again when the take ends, so Escape works normally the rest of the time.
    private func catchEscapeWhileDictating() {
        if escapeHotKeyRef != nil {
            return
        }
        var registeredEscape: EventHotKeyRef?
        let status = RegisterEventHotKey(
            UInt32(kVK_Escape),
            0,
            EventHotKeyID(signature: OSType(0x5256_4448), id: DictationHelper.escapeHotKeyNumber),
            GetApplicationEventTarget(),
            0,
            &registeredEscape
        )
        if status == noErr {
            escapeHotKeyRef = registeredEscape
        }
    }

    private func releaseEscapeAfterDictation() {
        if let escapeHotKeyRef {
            UnregisterEventHotKey(escapeHotKeyRef)
            self.escapeHotKeyRef = nil
        }
    }

    func escapePressedDuringDictation() {
        if mode == "listening" || mode == "busy" {
            cancelDictationBecauseEscapeWasPressed()
        }
    }

    /// Escape during a dictation stops it where it is: what is already typed stays, nothing more is typed. The app throws the rest of the recording away and then sets this helper back to idle.
    private func cancelDictationBecauseEscapeWasPressed() {
        mode = "busy"
        mayCursorHaveMovedInTheTakesField = true
        emit([
            "type": "hotkey",
            "phase": "cancel",
            "focusCaptured": false,
        ])
    }

    private func matchesShortcut(keyCode: Int64, flags: CGEventFlags) -> Bool {
        if keyCode != Int64(shortcutKeyCode) {
            return false
        }
        let wantsCommand = (shortcutModifiers & UInt32(cmdKey)) != 0
        let wantsShift = (shortcutModifiers & UInt32(shiftKey)) != 0
        let wantsOption = (shortcutModifiers & UInt32(optionKey)) != 0
        let wantsControl = (shortcutModifiers & UInt32(controlKey)) != 0
        return flags.contains(.maskCommand) == wantsCommand
            && flags.contains(.maskShift) == wantsShift
            && flags.contains(.maskAlternate) == wantsOption
            && flags.contains(.maskControl) == wantsControl
    }

    private func hotkeyPressed(focusedElement: AXUIElement?) {
        if mode == "listening" {
            mode = "busy"
            emit([
                "type": "hotkey",
                "phase": "stop",
                "focusCaptured": capturedTextElement != nil,
            ])
            return
        }
        if mode == "idle" {
            resetTypedTextForNewTake()
            capturedTextElement = focusedElement ?? copyFocusedElement()
            pillPanel.textCursorRectOnScreen = capturedTextElement.flatMap { element in
                textCursorRectOnScreen(of: element)
            }
            rememberWhichAppAndWindowThisTakeTypesInto()
            mode = "listening"
            catchEscapeWhileDictating()
            emit([
                "type": "hotkey",
                "phase": "start",
                "focusCaptured": capturedTextElement != nil || isParentFrontmost(),
            ])
            return
        }
        emit([
            "type": "hotkey",
            "phase": "ignored",
            "focusCaptured": false,
        ])
    }

    private func startReadingCommands() {
        Thread.detachNewThread {
            while let line = readLine(strippingNewline: true) {
                guard let data = line.data(using: .utf8),
                      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let command = object["command"] as? String else {
                    continue
                }
                DispatchQueue.main.async {
                    self.handleCommand(command, object)
                }
            }
        }
    }

    private func handleCommand(_ command: String, _ object: [String: Any]) {
        switch command {
        case "set-mode":
            if object["mode"] as? String == "idle" {
                mode = "idle"
                releaseEscapeAfterDictation()
                finishTypedTextAfterTake()
            }
        case "set-hotkey":
            shortcutKeyCode = (object["keyCode"] as? NSNumber)?.uint32Value ?? shortcutKeyCode
            shortcutModifiers = (object["modifiers"] as? NSNumber)?.uint32Value ?? shortcutModifiers
            let hotkeyError = registerCurrentShortcut()
            emit([
                "type": "shortcut",
                "registered": hotkeyError == nil,
                "error": hotkeyError ?? "",
            ])
        case "prompt-accessibility":
            promptForAccessibility()
        case "player-open":
            openSavedAudioInPlayer(path: object["path"] as? String ?? "", speed: (object["speed"] as? NSNumber)?.floatValue ?? 1)
        case "player-toggle":
            if let savedAudioPlayer {
                if savedAudioPlayer.isPlaying {
                    savedAudioPlayer.pause()
                } else {
                    savedAudioPlayer.play()
                }
                reportSavedAudioPlayerState()
            }
        case "player-skip":
            if let savedAudioPlayer {
                let jumpInSeconds = (object["seconds"] as? NSNumber)?.doubleValue ?? 0
                savedAudioPlayer.currentTime = min(max(savedAudioPlayer.currentTime + jumpInSeconds, 0), savedAudioPlayer.duration)
                reportSavedAudioPlayerState()
            }
        case "player-seek":
            if let savedAudioPlayer {
                let positionInSeconds = (object["seconds"] as? NSNumber)?.doubleValue ?? 0
                savedAudioPlayer.currentTime = min(max(positionInSeconds, 0), savedAudioPlayer.duration)
                reportSavedAudioPlayerState()
            }
        case "player-speed":
            savedAudioPlayer?.rate = (object["speed"] as? NSNumber)?.floatValue ?? 1
        case "player-close":
            closeSavedAudioPlayer()
        case "insert":
            let text = object["text"] as? String ?? ""
            insertCapturedText(text, isFinalText: object["final"] as? Bool == true)
        case "pill":
            if object["hide"] as? Bool == true {
                pillPanel.orderOut(nil)
                return
            }
            let title = object["title"] as? String ?? ""
            let detail = object["detail"] as? String ?? ""
            let tone = object["tone"] as? String ?? "listening"
            let level = (object["level"] as? NSNumber)?.doubleValue ?? 0
            if tone == "speaking" && pillPanel.isVisible == false {
                let mouseLocation = NSEvent.mouseLocation
                pillPanel.textCursorRectOnScreen = NSRect(x: mouseLocation.x, y: mouseLocation.y - 10, width: 1, height: 20)
            }
            pillPanel.update(title: title, detail: detail, tone: tone, level: CGFloat(level))
        default:
            break
        }
    }

    /// Types the latest text of this take into its field. Live updates (isFinalText false) wait while the person is in another app; the final text is still delivered to the take's field, see finishTakeInItsOwnFieldFromAnotherApp.
    private func insertCapturedText(_ text: String, isFinalText: Bool) {
        if text.isEmpty {
            emitInserted(ok: false, method: "none", message: "There was nothing to type.", verified: false)
            return
        }
        if !AXIsProcessTrusted() {
            emitInserted(ok: false, method: "none", message: insertFailureMessage(), verified: false)
            return
        }
        let element = copyFocusedElement()
        var owner = pid_t(0)
        if let element {
            AXUIElementGetPid(element, &owner)
        }
        if owner == 0 {
            owner = NSWorkspace.shared.frontmostApplication?.processIdentifier ?? 0
        }
        if processThatReceivedThisTake == parentProcessId || (processThatReceivedThisTake == 0 && owner == parentProcessId) {
            emitInserted(ok: true, method: "compose-window", message: "", verified: true)
            return
        }
        if !previouslyTypedText.isEmpty && mayCursorHaveMovedInTheTakesField {
            appendTypingLog("method=skipped-caret-moved owner=\(owner) expected=\(processThatReceivedThisTake)")
            emitInserted(ok: false, method: "caret-moved", message: "You clicked or typed in that app while speaking, so Rvoice stopped typing. The full text is on the clipboard.", verified: false)
            return
        }
        if processThatReceivedThisTake == 0 {
            processThatReceivedThisTake = owner
        }
        if owner != processThatReceivedThisTake {
            if !isFinalText {
                emitInserted(ok: false, method: "waiting-for-final", message: "", verified: false)
                return
            }
            let returnResult = finishTakeInItsOwnFieldFromAnotherApp(text)
            emitInserted(ok: returnResult.didLand, method: returnResult.method, message: returnResult.didLand ? "" : returnResult.message, verified: returnResult.verified)
            return
        }
        if previouslyTypedText.isEmpty, let element {
            takeTextStartLocationInField = selectedTextRange(of: element)?.location
        }
        let writeResult = replaceDictatedText(with: text, owner: owner, element: element)
        emitInserted(
            ok: writeResult.didLand,
            method: writeResult.method,
            message: writeResult.didLand ? "" : writeResult.message,
            verified: writeResult.verified
        )
    }

    /**
     * The person moved to another app before the final text arrived. First tries writing it straight into the take's field through Accessibility, which works in most native Mac apps without switching anything. If that app does not allow it, briefly brings the take's window back, types the final words there, and returns the person to the app and field they were in.
     */
    private func finishTakeInItsOwnFieldFromAnotherApp(_ text: String) -> DictationWriteResult {
        let takeAppBundleIdentifier = NSRunningApplication(processIdentifier: processThatReceivedThisTake)?.bundleIdentifier ?? ""
        let isTerminal = terminalBundleIdentifiers.contains(takeAppBundleIdentifier) || focusedElementLooksLikeTerminal(capturedTextElement)
        if !isTerminal, writeFinalTextStraightIntoTheTakesField(text) {
            appendTypingLog("method=background-accessibility verified=true length=\(text.count) bundle=\(takeAppBundleIdentifier)")
            return DictationWriteResult(didLand: true, verified: true, method: "background-accessibility", message: "")
        }
        let personsApp = NSRunningApplication(processIdentifier: frontmostAppProcessIdentifierRightNow())
        let personsFocusedElement = copyFocusedElement()
        let returnPersonToTheirApp = {
            guard let personsApp else {
                return
            }
            personsApp.activate()
            AXUIElementSetAttributeValue(AXUIElementCreateApplication(personsApp.processIdentifier), kAXFrontmostAttribute as CFString, kCFBooleanTrue)
            if let personsFocusedElement {
                AXUIElementSetAttributeValue(personsFocusedElement, kAXFocusedAttribute as CFString, kCFBooleanTrue)
            }
        }
        guard bringTheTakesWindowToTheFront() else {
            returnPersonToTheirApp()
            return DictationWriteResult(didLand: false, verified: false, method: "return-failed", message: "Rvoice could not get back to the app you were dictating into. The full text is on the clipboard.")
        }
        let writeResult = replaceDictatedText(with: text, owner: processThatReceivedThisTake, element: copyFocusedElement())
        returnPersonToTheirApp()
        appendTypingLog("method=returned-to-field landed=\(writeResult.didLand) bundle=\(takeAppBundleIdentifier)")
        return DictationWriteResult(didLand: writeResult.didLand, verified: writeResult.verified, method: "returned-to-field", message: writeResult.message)
    }

    /**
     * Replaces this take's text in its field without focusing it: selects exactly the words typed so far and writes the final text over them. Only reports success when reading the field back shows the final text; when the field did not change at all it reports failure so the switch-back route can be tried safely.
     */
    private func writeFinalTextStraightIntoTheTakesField(_ text: String) -> Bool {
        guard let field = capturedTextElement, let startLocation = takeTextStartLocationInField,
              let valueBefore = focusedFieldText(field) else {
            return false
        }
        let previousLength = (previouslyTypedText as NSString).length
        guard startLocation + previousLength <= (valueBefore as NSString).length,
              (valueBefore as NSString).substring(with: NSRange(location: startLocation, length: previousLength)) == previouslyTypedText else {
            return false
        }
        var rangeToReplace = CFRange(location: startLocation, length: previousLength)
        guard let rangeValue = AXValueCreate(.cfRange, &rangeToReplace),
              AXUIElementSetAttributeValue(field, kAXSelectedTextRangeAttribute as CFString, rangeValue) == .success,
              AXUIElementSetAttributeValue(field, kAXSelectedTextAttribute as CFString, text as CFTypeRef) == .success else {
            return false
        }
        Thread.sleep(forTimeInterval: 0.08)
        let valueAfter = focusedFieldText(field) ?? ""
        let textLength = (text as NSString).length
        let didLandInPlace = startLocation + textLength <= (valueAfter as NSString).length &&
            (valueAfter as NSString).substring(with: NSRange(location: startLocation, length: textLength)) == text
        if didLandInPlace {
            previouslyTypedText = text
        }
        return didLandInPlace
    }

    /// Makes the take's app the front app (asking macOS both directly and through Accessibility), raises the take's window and focuses its field, then waits up to 3 seconds until it really is in front.
    private func bringTheTakesWindowToTheFront() -> Bool {
        guard processThatReceivedThisTake != 0, let takeApp = NSRunningApplication(processIdentifier: processThatReceivedThisTake) else {
            return false
        }
        takeApp.activate()
        let takeAppElement = AXUIElementCreateApplication(processThatReceivedThisTake)
        AXUIElementSetAttributeValue(takeAppElement, kAXFrontmostAttribute as CFString, kCFBooleanTrue)
        if let windowOfTheTakesField {
            AXUIElementPerformAction(windowOfTheTakesField, kAXRaiseAction as CFString)
        }
        if let capturedTextElement {
            AXUIElementSetAttributeValue(capturedTextElement, kAXFocusedAttribute as CFString, kCFBooleanTrue)
        }
        for _ in 0..<60 {
            if frontmostAppProcessIdentifierRightNow() == processThatReceivedThisTake {
                Thread.sleep(forTimeInterval: 0.15)
                return true
            }
            Thread.sleep(forTimeInterval: 0.05)
        }
        return false
    }

    /// The app in front at this very moment, asked through Accessibility. NSWorkspace's answer only refreshes while the main thread is free, and this code waits on the main thread.
    private func frontmostAppProcessIdentifierRightNow() -> pid_t {
        var rawApplication: CFTypeRef?
        if AXUIElementCopyAttributeValue(AXUIElementCreateSystemWide(), kAXFocusedApplicationAttribute as CFString, &rawApplication) == .success,
           let rawApplication {
            var processIdentifier = pid_t(0)
            AXUIElementGetPid(rawApplication as! AXUIElement, &processIdentifier)
            if processIdentifier != 0 {
                return processIdentifier
            }
        }
        return NSWorkspace.shared.frontmostApplication?.processIdentifier ?? 0
    }

    private func selectedTextRange(of element: AXUIElement) -> CFRange? {
        var rawRange: CFTypeRef?
        guard AXUIElementCopyAttributeValue(element, kAXSelectedTextRangeAttribute as CFString, &rawRange) == .success,
              let rawRange, CFGetTypeID(rawRange) == AXValueGetTypeID() else {
            return nil
        }
        var range = CFRange()
        return AXValueGetValue(rawRange as! AXValue, .cfRange, &range) ? range : nil
    }

    private func emitInserted(ok: Bool, method: String, message: String, verified: Bool) {
        emit([
            "type": "inserted",
            "ok": ok,
            "method": method,
            "message": message,
            "verified": verified,
        ])
    }

    private func insertFailureMessage() -> String {
        if !AXIsProcessTrusted() {
            return "Turn Rvoice off and back on in Accessibility. The typing program was updated, so macOS is ignoring it until you do that."
        }
        return "The words did not stay in the text field. Click the field and try the shortcut again."
    }

    /// Forgets the previous take and puts the user's clipboard back once the field has had time to read the paste.
    private func resetTypedTextForNewTake() {
        finishTypedTextAfterTake()
        previouslyTypedText = ""
    }

    private func finishTypedTextAfterTake() {
        clipboardRestoreGeneration += 1
        let generation = clipboardRestoreGeneration
        let pastedText = textLastPutOnClipboard
        let savedClipboard = clipboardTextBeforeTake
        let shouldRestore = didSaveClipboardForTake
        didSaveClipboardForTake = false
        clipboardTextBeforeTake = nil
        previouslyTypedText = ""
        textLastPutOnClipboard = ""
        processThatReceivedThisTake = 0
        windowOfTheTakesField = nil
        takeTextStartLocationInField = nil
        mayCursorHaveMovedInTheTakesField = false
        guard shouldRestore else {
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            if generation != self.clipboardRestoreGeneration {
                return
            }
            let pasteboard = NSPasteboard.general
            guard pasteboard.string(forType: .string) == pastedText else {
                return
            }
            pasteboard.clearContents()
            if let savedClipboard {
                pasteboard.setString(savedClipboard, forType: .string)
            }
        }
    }

    private func isParentFrontmost() -> Bool {
        NSWorkspace.shared.frontmostApplication?.processIdentifier == parentProcessId
    }

    private struct DictationWriteResult {
        let didLand: Bool
        let verified: Bool
        let method: String
        let message: String
    }

    /// Replaces the words this take already typed with the latest, fuller transcript. Only the part that changed is erased (with Backspace) and pasted again, so earlier words stay put and the update is quick.
    ///
    /// When the field's text can be read back (most editors and browsers), the result is checked. Terminals and some apps do not share their text with macOS, so there the keystrokes are trusted instead; otherwise every live update would look like a failure and paste the sentence again on top of itself.
    private func replaceDictatedText(with text: String, owner: pid_t, element: AXUIElement?) -> DictationWriteResult {
        guard let source = CGEventSource(stateID: .hidSystemState) else {
            return DictationWriteResult(didLand: false, verified: false, method: "none", message: insertFailureMessage())
        }
        source.localEventsSuppressionInterval = 0
        let bundleIdentifier = NSRunningApplication(processIdentifier: owner)?.bundleIdentifier ?? ""
        let isTerminal = terminalBundleIdentifiers.contains(bundleIdentifier) || focusedElementLooksLikeTerminal(element)
        let fieldTextBeforeTyping = isTerminal ? nil : element.flatMap { focusedFieldText($0) }
        let canReadFieldBack = fieldTextBeforeTyping != nil
        let textToType = isTerminal ? textSafeForTerminal(text) : text
        let previousText = previouslyTypedText
        let method: String
        let pastedPart: String
        if let fieldTextBeforeTyping, fieldContainsOnlyPreviousDictation(fieldTextBeforeTyping, previousText: previousText) {
            postKey(virtualKey: 0x00, flags: .maskCommand, source: source)
            Thread.sleep(forTimeInterval: 0.04)
            pasteText(textToType, source: source)
            method = "select-all"
            pastedPart = textToType
        } else {
            let unchangedCharacterCount = sharedLeadingCharacterCount(previousText, textToType)
            let charactersToErase = previousText.count - unchangedCharacterCount
            pastedPart = String(textToType.dropFirst(unchangedCharacterCount))
            deleteCharacters(charactersToErase, source: source)
            if !pastedPart.isEmpty {
                pasteText(pastedPart, source: source)
            }
            if isTerminal {
                method = "terminal-keys"
            } else if canReadFieldBack {
                method = previousText.isEmpty ? "paste" : "retype-changed-words"
            } else {
                method = "unreadable-field-keys"
            }
        }
        previouslyTypedText = textToType
        if !canReadFieldBack {
            appendTypingLog(
                "method=\(method) verified=false erased-and-pasted=\(pastedPart.count) length=\(textToType.count) bundle=\(bundleIdentifier)"
            )
            return DictationWriteResult(didLand: true, verified: false, method: method, message: "")
        }
        let expected = textToType.trimmingCharacters(in: .whitespacesAndNewlines)
        let readBack = fieldTextAfterTyping(expected)
        let verified = !expected.isEmpty && readBack.map { fieldText in
            textContainsIgnoringLineWrapping(fieldText, expected)
        } == true
        let readBackSnippet = readBack.map { String($0.prefix(80)) } ?? "unreadable"
        appendTypingLog(
            "method=\(method) verified=\(verified) pasted=\(pastedPart.count) length=\(textToType.count) bundle=\(bundleIdentifier) read=[\(readBackSnippet)]"
        )
        if verified {
            return DictationWriteResult(didLand: true, verified: true, method: method, message: "")
        }
        let didFieldStayExactlyTheSame = readBack == fieldTextBeforeTyping
        if !pastedPart.isEmpty, didFieldStayExactlyTheSame, let freshElement = copyFocusedElement() {
            AXUIElementSetAttributeValue(
                freshElement,
                kAXSelectedTextAttribute as CFString,
                pastedPart as CFTypeRef
            )
            let afterAccessibilityWrite = fieldTextAfterTyping(expected)
            if afterAccessibilityWrite.map({ fieldText in textContainsIgnoringLineWrapping(fieldText, expected) }) == true {
                appendTypingLog("method=accessibility-fresh verified=true length=\(textToType.count)")
                return DictationWriteResult(didLand: true, verified: true, method: "accessibility-fresh", message: "")
            }
        }
        return DictationWriteResult(didLand: false, verified: false, method: method, message: insertFailureMessage())
    }

    private let terminalBundleIdentifiers: Set<String> = [
        "com.apple.Terminal",
        "com.googlecode.iterm2",
        "com.mitchellh.ghostty",
        "net.kovidgoyal.kitty",
        "io.alacritty",
        "org.alacritty",
        "com.github.wez.wezterm",
        "dev.warp.Warp-Stable",
        "dev.warp.Warp",
        "co.zeit.hyper",
        "org.tabby",
        "com.raphaelamorim.rio",
        "com.cmuxterm.app",
    ]

    /// Spots terminals that live inside another app, such as the VS Code or IntelliJ terminal panel, by the label macOS reads out for the focused element. Returns false when nothing is focused.
    private func focusedElementLooksLikeTerminal(_ element: AXUIElement?) -> Bool {
        guard let element else {
            return false
        }
        let labelAttributes = [
            kAXRoleDescriptionAttribute,
            kAXDescriptionAttribute,
            kAXTitleAttribute,
            kAXHelpAttribute,
            kAXIdentifierAttribute,
        ]
        for labelAttribute in labelAttributes {
            var rawLabel: CFTypeRef?
            let result = AXUIElementCopyAttributeValue(element, labelAttribute as CFString, &rawLabel)
            if result == .success, let label = rawLabel as? String, label.lowercased().contains("terminal") {
                return true
            }
        }
        return false
    }

    private func deleteCharacters(_ characterCount: Int, source: CGEventSource) {
        if characterCount <= 0 {
            return
        }
        for _ in 0..<characterCount {
            postKey(virtualKey: 0x33, flags: [], source: source)
        }
        Thread.sleep(forTimeInterval: 0.03)
    }

    private func pasteText(_ text: String, source: CGEventSource) {
        let pasteboard = NSPasteboard.general
        if !didSaveClipboardForTake {
            clipboardTextBeforeTake = pasteboard.string(forType: .string)
            didSaveClipboardForTake = true
        }
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        textLastPutOnClipboard = text
        Thread.sleep(forTimeInterval: 0.05)
        postKey(virtualKey: 0x09, flags: .maskCommand, source: source)
        Thread.sleep(forTimeInterval: 0.15)
    }

    private func postKey(virtualKey: CGKeyCode, flags: CGEventFlags, source: CGEventSource) {
        guard let keyDown = CGEvent(keyboardEventSource: source, virtualKey: virtualKey, keyDown: true),
              let keyUp = CGEvent(keyboardEventSource: source, virtualKey: virtualKey, keyDown: false) else {
            return
        }
        keyDown.flags = flags
        keyUp.flags = flags
        let keyboardType = Int64(LMGetKbdType())
        keyDown.setIntegerValueField(.keyboardEventKeyboardType, value: keyboardType)
        keyUp.setIntegerValueField(.keyboardEventKeyboardType, value: keyboardType)
        keyDown.post(tap: .cghidEventTap)
        Thread.sleep(forTimeInterval: 0.012)
        keyUp.post(tap: .cghidEventTap)
        Thread.sleep(forTimeInterval: 0.006)
    }

    private func fieldTextAfterTyping(_ expected: String) -> String? {
        var latest: String?
        for _ in 0..<5 {
            if let element = copyFocusedElement(), let value = focusedFieldText(element) {
                latest = value
                if !expected.isEmpty && textContainsIgnoringLineWrapping(value, expected) {
                    return value
                }
            }
            Thread.sleep(forTimeInterval: 0.08)
        }
        return latest
    }

    private func focusedFieldText(_ element: AXUIElement) -> String? {
        var rawValue: CFTypeRef?
        let result = AXUIElementCopyAttributeValue(element, kAXValueAttribute as CFString, &rawValue)
        guard result == .success else {
            return nil
        }
        return rawValue as? String
    }

    private func appendTypingLog(_ line: String) {
        let directory = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".reaptive-voice")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let logFile = directory.appendingPathComponent("dictation-typing.log")
        let stamped = "\(ISO8601DateFormatter().string(from: Date())) \(line)\n"
        guard let data = stamped.data(using: .utf8) else {
            return
        }
        if FileManager.default.fileExists(atPath: logFile.path),
           let handle = try? FileHandle(forWritingTo: logFile) {
            handle.seekToEndOfFile()
            handle.write(data)
            try? handle.close()
            return
        }
        try? data.write(to: logFile)
    }

    /// Asks the app where its text cursor is on screen. If the app will not say, falls back to the text field itself, but only when the field is small (a search box or a single line), because the middle of a big editor is not a useful place for the pill. Returns nil when neither is known.
    private func textCursorRectOnScreen(of element: AXUIElement) -> NSRect? {
        if let cursorRect = screenRectOfSelectedTextRange(of: element) {
            return cursorRect
        }
        guard let fieldRect = screenRectOfElement(element), fieldRect.height <= 120 else {
            return nil
        }
        return fieldRect
    }

    private func screenRectOfSelectedTextRange(of element: AXUIElement) -> NSRect? {
        var rawRange: CFTypeRef?
        guard AXUIElementCopyAttributeValue(element, kAXSelectedTextRangeAttribute as CFString, &rawRange) == .success,
              let rawRange else {
            return nil
        }
        var rawBounds: CFTypeRef?
        guard AXUIElementCopyParameterizedAttributeValue(
            element,
            kAXBoundsForRangeParameterizedAttribute as CFString,
            rawRange,
            &rawBounds
        ) == .success, let rawBounds, CFGetTypeID(rawBounds) == AXValueGetTypeID() else {
            return nil
        }
        var boundsInAccessibilityCoordinates = CGRect.zero
        guard AXValueGetValue(rawBounds as! AXValue, .cgRect, &boundsInAccessibilityCoordinates),
              boundsInAccessibilityCoordinates.height > 0 else {
            return nil
        }
        return screenRectFromAccessibilityRect(boundsInAccessibilityCoordinates)
    }

    private func screenRectOfElement(_ element: AXUIElement) -> NSRect? {
        var rawPosition: CFTypeRef?
        var rawSize: CFTypeRef?
        guard AXUIElementCopyAttributeValue(element, kAXPositionAttribute as CFString, &rawPosition) == .success,
              AXUIElementCopyAttributeValue(element, kAXSizeAttribute as CFString, &rawSize) == .success,
              let rawPosition, let rawSize else {
            return nil
        }
        var position = CGPoint.zero
        var size = CGSize.zero
        guard AXValueGetValue(rawPosition as! AXValue, .cgPoint, &position),
              AXValueGetValue(rawSize as! AXValue, .cgSize, &size),
              size.width > 0, size.height > 0 else {
            return nil
        }
        return screenRectFromAccessibilityRect(CGRect(origin: position, size: size))
    }

    /// Accessibility measures from the top-left of the main display, while windows are placed from the bottom-left, so the vertical position is flipped.
    private func screenRectFromAccessibilityRect(_ accessibilityRect: CGRect) -> NSRect {
        let mainDisplayHeight = NSScreen.screens.first?.frame.height ?? 0
        return NSRect(
            x: accessibilityRect.minX,
            y: mainDisplayHeight - accessibilityRect.maxY,
            width: accessibilityRect.width,
            height: accessibilityRect.height
        )
    }

    private func copyFocusedElement() -> AXUIElement? {
        let systemWide = AXUIElementCreateSystemWide()
        var rawValue: CFTypeRef?
        let result = AXUIElementCopyAttributeValue(
            systemWide,
            kAXFocusedUIElementAttribute as CFString,
            &rawValue
        )
        guard result == .success, let rawValue else {
            return nil
        }
        return (rawValue as! AXUIElement)
    }

    private func promptForAccessibility() {
        let promptKey = kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String
        let options = [promptKey: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)
        reportAccessibilityIfItChanged()
    }

    private func reportAccessibilityIfItChanged() {
        let trusted = AXIsProcessTrusted()
        if trusted == lastAccessibilityTrusted {
            return
        }
        lastAccessibilityTrusted = trusted
        emit([
            "type": "accessibility",
            "trusted": trusted,
        ])
    }

    private var savedAudioPlayer: AVAudioPlayer?
    private var savedAudioPlayerPath = ""
    private var savedAudioPlayerTimer: Timer?

    /// Plays a saved MP3 with Apple's audio player, which can pause, jump and change speed, and reports progress to the app four times a second. Anything already open is closed first.
    private func openSavedAudioInPlayer(path: String, speed: Float) {
        closeSavedAudioPlayer()
        guard let player = try? AVAudioPlayer(contentsOf: URL(fileURLWithPath: path)) else {
            emit(["type": "player-error", "message": "That audio file could not be opened."])
            return
        }
        player.enableRate = true
        player.rate = speed
        player.prepareToPlay()
        player.play()
        savedAudioPlayer = player
        savedAudioPlayerPath = path
        let timer = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in
            self?.reportSavedAudioPlayerState()
        }
        RunLoop.main.add(timer, forMode: .common)
        savedAudioPlayerTimer = timer
        reportSavedAudioPlayerState()
    }

    private func closeSavedAudioPlayer() {
        savedAudioPlayerTimer?.invalidate()
        savedAudioPlayerTimer = nil
        savedAudioPlayer?.stop()
        if savedAudioPlayer != nil {
            emit(["type": "player-closed"])
        }
        savedAudioPlayer = nil
        savedAudioPlayerPath = ""
    }

    private func reportSavedAudioPlayerState() {
        guard let savedAudioPlayer else {
            return
        }
        emit([
            "type": "player-state",
            "path": savedAudioPlayerPath,
            "position": savedAudioPlayer.currentTime,
            "length": savedAudioPlayer.duration,
            "playing": savedAudioPlayer.isPlaying,
        ])
    }

    private let speakSelectedTextService = SpeakSelectedTextService()

    /// Adds "Speak with Rvoice" to the right-click menu (and the Services menu) for selected text in every app. macOS reads the menu item from this app's Info.plist; registering the app's location makes sure macOS knows about it.
    private func offerSpeakWithRvoiceInRightClickMenus() {
        NSApplication.shared.servicesProvider = speakSelectedTextService
        let launchServicesRegisterTool = "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"
        let registerProcess = Process()
        registerProcess.executableURL = URL(fileURLWithPath: launchServicesRegisterTool)
        registerProcess.arguments = ["-f", Bundle.main.bundleURL.path]
        registerProcess.terminationHandler = { _ in
            DispatchQueue.main.async {
                NSUpdateDynamicServices()
            }
        }
        try? registerProcess.run()
    }

    /// Passes text to the app to be read out loud. Called by the right-click service.
    func askAppToSpeak(_ text: String) {
        let trimmedText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedText.isEmpty {
            return
        }
        let mouseLocation = NSEvent.mouseLocation
        pillPanel.textCursorRectOnScreen = NSRect(x: mouseLocation.x, y: mouseLocation.y - 10, width: 1, height: 20)
        emit(["type": "speak-request", "text": trimmedText])
    }

    /// Passes selected text to the app to be turned into an MP3 without reading it out loud. Called by the right-click service.
    func askAppToSaveAsAudio(_ text: String) {
        let trimmedText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedText.isEmpty {
            return
        }
        emit(["type": "save-audio-request", "text": trimmedText])
    }

    /// Tells the app which button the person clicked on the speaking pill: "next-speed", "rewind", "toggle-pause" or "stop".
    func askAppToControlSpeech(_ action: String) {
        emit(["type": "speech-control", "action": action])
    }

    private func emit(_ object: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: object),
              let line = String(data: data, encoding: .utf8) else {
            return
        }
        print(line)
        fflush(stdout)
    }
}

/// Receives the text when someone picks "Speak with Rvoice" or "Save as MP3 with Rvoice" from a right-click menu. macOS calls the method named in Info.plist (NSMessage) with the selected text on a pasteboard.
final class SpeakSelectedTextService: NSObject {
    @objc func speakSelectedText(_ pasteboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        let selectedText = pasteboard.string(forType: .string) ?? ""
        DictationHelper.shared.askAppToSpeak(selectedText)
    }

    @objc func saveSelectedTextAsAudio(_ pasteboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        let selectedText = pasteboard.string(forType: .string) ?? ""
        DictationHelper.shared.askAppToSaveAsAudio(selectedText)
    }
}

/// A small floating capsule that shows what Rvoice is doing. It never becomes the focused window, so the caret stays in the text field the person is dictating into.
final class ListeningPillPanel: NSPanel {
    private let pillView = PillContentView()

    init() {
        super.init(
            contentRect: NSRect(x: 0, y: 0, width: 260, height: 52),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        isFloatingPanel = true
        level = .statusBar
        hidesOnDeactivate = false
        isOpaque = false
        backgroundColor = .clear
        hasShadow = true
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
        ignoresMouseEvents = true
        contentView = pillView
        isReleasedWhenClosed = false
    }

    /// Where the text cursor (or the small text field) was when this take started, in screen points. Nil means "not known", and the pill then sits at the bottom of the screen.
    var textCursorRectOnScreen: NSRect?

    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }

    func update(title: String, detail: String, tone: String, level: CGFloat) {
        if tone != pillView.tone {
            pillView.forgetMicrophoneLevels()
        }
        pillView.title = title
        pillView.detail = detail
        pillView.tone = tone
        ignoresMouseEvents = tone != "speaking"
        pillView.rememberMicrophoneLevel(level)
        placeNearTheTextCursor(width: pillView.preferredWidth())
        pillView.startAnimating()
        orderFrontRegardless()
    }

    override func orderOut(_ sender: Any?) {
        pillView.stopAnimating()
        super.orderOut(sender)
    }

    /// Puts the pill just above the text cursor, or just below it when there is no room above. Without a known cursor position it goes to the bottom middle of the screen.
    private func placeNearTheTextCursor(width: CGFloat) {
        let height: CGFloat = 52
        let gapFromCursor: CGFloat = 10
        if let cursorRect = textCursorRectOnScreen,
           let screen = NSScreen.screens.first(where: { candidateScreen in
               candidateScreen.frame.intersects(cursorRect.insetBy(dx: -1, dy: -1))
           }) {
            let visibleFrame = screen.visibleFrame
            let x = min(max(cursorRect.midX - width / 2, visibleFrame.minX + 8), visibleFrame.maxX - width - 8)
            let yAbove = cursorRect.maxY + gapFromCursor
            let yBelow = cursorRect.minY - gapFromCursor - height
            let y = yAbove + height <= visibleFrame.maxY ? yAbove : max(yBelow, visibleFrame.minY + 8)
            setFrame(NSRect(x: x, y: y, width: width, height: height), display: true)
            return
        }
        guard let screen = NSScreen.main ?? NSScreen.screens.first else {
            return
        }
        let visibleFrame = screen.visibleFrame
        setFrame(
            NSRect(x: visibleFrame.midX - width / 2, y: visibleFrame.minY + 28, width: width, height: height),
            display: true
        )
    }
}

/// Draws the pill. While listening it shows a pulsing red dot, a waveform that follows the microphone, and the elapsed time. While speaking it shows a blue dot, a waveform that follows Rvoice's voice, and rewind, play or pause, and stop buttons. While working it shows an amber ripple and a one-word label. When done it shows a green tick; on an error it shows the reason as text.
final class PillContentView: NSView {
    var title = ""
    var detail = ""
    var tone = "listening"

    private let waveformBarCount = 22
    private var recentMicrophoneLevels = [CGFloat]()
    private var smoothedBarHeights = [CGFloat]()
    private var animationTimer: Timer?
    private var animationStartedAt = Date()

    private let backgroundColor = NSColor(calibratedRed: 0.11, green: 0.086, blue: 0.078, alpha: 0.96)
    private let listeningColor = NSColor(calibratedRed: 0.90, green: 0.30, blue: 0.24, alpha: 1)
    private let workingColor = NSColor(calibratedRed: 0.90, green: 0.66, blue: 0.26, alpha: 1)
    private let doneColor = NSColor(calibratedRed: 0.42, green: 0.76, blue: 0.50, alpha: 1)
    private let speakingColor = NSColor(calibratedRed: 0.38, green: 0.62, blue: 0.98, alpha: 1)

    /// Clicks on the speaking pill's buttons go to the app; clicks anywhere else on the pill do nothing.
    override func mouseDown(with event: NSEvent) {
        guard tone == "speaking" else {
            return
        }
        switch speakingPillButton(at: convert(event.locationInWindow, from: nil)) {
        case .speed:
            DictationHelper.shared.askAppToControlSpeech("next-speed")
        case .rewind:
            DictationHelper.shared.askAppToControlSpeech("rewind")
        case .playOrPause:
            DictationHelper.shared.askAppToControlSpeech("toggle-pause")
        case .stop:
            DictationHelper.shared.askAppToControlSpeech("stop")
        case nil:
            break
        }
    }

    private var isShowingAMessage: Bool {
        tone == "done" || tone == "error"
    }

    func rememberMicrophoneLevel(_ level: CGFloat) {
        guard tone == "listening" || tone == "speaking" else {
            return
        }
        recentMicrophoneLevels.append(min(max(level, 0), 1))
        if recentMicrophoneLevels.count > waveformBarCount {
            recentMicrophoneLevels.removeFirst(recentMicrophoneLevels.count - waveformBarCount)
        }
    }

    func forgetMicrophoneLevels() {
        recentMicrophoneLevels.removeAll()
        smoothedBarHeights.removeAll()
        animationStartedAt = Date()
    }

    /// Listening and working stay compact. Messages grow to fit their text, up to 420 points wide.
    func preferredWidth() -> CGFloat {
        if tone == "speaking" {
            return 320
        }
        if !isShowingAMessage {
            return 260
        }
        let textWidth = max(
            (title as NSString).size(withAttributes: titleAttributes()).width,
            (detail as NSString).size(withAttributes: detailAttributes()).width
        )
        return min(max(textWidth + 70, 200), 420)
    }

    func startAnimating() {
        needsDisplay = true
        if animationTimer != nil {
            return
        }
        let timer = Timer(timeInterval: 1.0 / 30.0, repeats: true) { [weak self] _ in
            self?.needsDisplay = true
        }
        RunLoop.main.add(timer, forMode: .common)
        animationTimer = timer
    }

    func stopAnimating() {
        animationTimer?.invalidate()
        animationTimer = nil
    }

    override func draw(_ dirtyRect: NSRect) {
        let capsule = bounds.insetBy(dx: 1, dy: 1)
        let capsulePath = NSBezierPath(roundedRect: capsule, xRadius: capsule.height / 2, yRadius: capsule.height / 2)
        backgroundColor.setFill()
        capsulePath.fill()
        let secondsSinceStateStarted = CGFloat(Date().timeIntervalSince(animationStartedAt))
        switch tone {
        case "listening":
            drawStatusDot(color: listeningColor, pulsePhase: secondsSinceStateStarted)
            drawLiveWaveform()
            drawRightLabel(detail, monospaced: true)
        case "speaking":
            drawStatusDot(color: speakingColor, pulsePhase: secondsSinceStateStarted)
            drawLiveWaveform(color: speakingColor)
            drawPlaybackControls()
        case "working":
            drawStatusDot(color: workingColor, pulsePhase: nil)
            drawRipple(secondsSinceStateStarted: secondsSinceStateStarted)
            drawRightLabel(title, monospaced: false)
        case "done":
            drawTick(color: doneColor)
            drawMessage()
        default:
            drawStatusDot(color: listeningColor, pulsePhase: nil)
            drawMessage()
        }
    }

    /// The dot gently grows and fades about once a second while listening; otherwise it is still.
    private func drawStatusDot(color: NSColor, pulsePhase: CGFloat?) {
        let centerX: CGFloat = 26
        let centerY = bounds.midY
        let dotSize: CGFloat = 12
        if let pulsePhase {
            let pulse = (sin(pulsePhase * .pi * 2 / 1.2) + 1) / 2
            let haloSize = dotSize + 8 * pulse
            color.withAlphaComponent(0.35 * (1 - pulse)).setFill()
            NSBezierPath(ovalIn: NSRect(x: centerX - haloSize / 2, y: centerY - haloSize / 2, width: haloSize, height: haloSize)).fill()
        }
        color.setFill()
        NSBezierPath(ovalIn: NSRect(x: centerX - dotSize / 2, y: centerY - dotSize / 2, width: dotSize, height: dotSize)).fill()
    }

    /// The bars fill the space between the dot and the label on the right. A one-word working label needs more room than the clock.
    private func waveformArea() -> NSRect {
        let roomForRightLabel: CGFloat = tone == "working" ? 112 : (tone == "speaking" ? 16 + speakingPillButtonWidth * CGFloat(SpeakingPillButton.allCases.count) + 10 : 72)
        return NSRect(x: 46, y: 12, width: bounds.width - 46 - roomForRightLabel, height: bounds.height - 24)
    }

    /// The newest microphone level is on the right; older ones slide left. Bars ease toward their target height so the motion looks smooth between updates.
    private func drawLiveWaveform(color: NSColor = NSColor.white) {
        let area = waveformArea()
        if smoothedBarHeights.count != waveformBarCount {
            smoothedBarHeights = Array(repeating: 0, count: waveformBarCount)
        }
        let paddedLevels = Array(repeating: CGFloat(0), count: max(0, waveformBarCount - recentMicrophoneLevels.count)) + recentMicrophoneLevels
        for barIndex in 0..<waveformBarCount {
            let target = min(1, pow(paddedLevels[barIndex], 0.6) * 1.15)
            smoothedBarHeights[barIndex] += (target - smoothedBarHeights[barIndex]) * 0.35
        }
        drawBars(in: area, heights: smoothedBarHeights, color: color)
    }

    /// A wave that travels left to right across the bars, so the pill visibly keeps working while the model runs.
    private func drawRipple(secondsSinceStateStarted: CGFloat) {
        let heights = (0..<waveformBarCount).map { barIndex -> CGFloat in
            let wavePosition = CGFloat(barIndex) / CGFloat(waveformBarCount) * .pi * 2 - secondsSinceStateStarted * 5
            return 0.18 + 0.5 * (sin(wavePosition) + 1) / 2
        }
        drawBars(in: waveformArea(), heights: heights, color: workingColor)
    }

    private func drawBars(in area: NSRect, heights: [CGFloat], color: NSColor) {
        let gap: CGFloat = 3
        let barWidth = (area.width - gap * CGFloat(heights.count - 1)) / CGFloat(heights.count)
        let minimumBarHeight: CGFloat = 3
        for (barIndex, heightFraction) in heights.enumerated() {
            let barHeight = max(minimumBarHeight, area.height * heightFraction)
            let barRect = NSRect(
                x: area.minX + CGFloat(barIndex) * (barWidth + gap),
                y: area.midY - barHeight / 2,
                width: barWidth,
                height: barHeight
            )
            color.withAlphaComponent(heightFraction < 0.05 ? 0.3 : 0.95).setFill()
            NSBezierPath(roundedRect: barRect, xRadius: barWidth / 2, yRadius: barWidth / 2).fill()
        }
    }

    /// The playback buttons on the right of the speaking pill, left to right: speed, back five seconds, play or pause, stop.
    private enum SpeakingPillButton: CaseIterable {
        case speed
        case rewind
        case playOrPause
        case stop
    }

    private let speakingPillButtonWidth: CGFloat = 32

    /// Where each playback button sits, as a square centred in its slot.
    private func rectOfSpeakingPillButton(_ button: SpeakingPillButton) -> NSRect {
        let buttonIndex = CGFloat(SpeakingPillButton.allCases.firstIndex(of: button) ?? 0)
        let firstButtonX = bounds.width - 16 - speakingPillButtonWidth * CGFloat(SpeakingPillButton.allCases.count)
        return NSRect(x: firstButtonX + buttonIndex * speakingPillButtonWidth, y: bounds.midY - 15, width: speakingPillButtonWidth, height: 30)
    }

    private func speakingPillButton(at point: NSPoint) -> SpeakingPillButton? {
        SpeakingPillButton.allCases.first { button in
            rectOfSpeakingPillButton(button).insetBy(dx: 0, dy: -8).contains(point)
        }
    }

    /// Draws rewind (two left triangles), pause (two bars) or play (a right triangle) while paused, and stop (a square).
    private func drawPlaybackControls() {
        let iconColor = NSColor(calibratedWhite: 1, alpha: 0.88)
        iconColor.setFill()
        let detailParts = detail.split(separator: "|").map(String.init)
        let isPaused = detailParts.first == "paused"
        let speedLabel = detailParts.count > 1 ? detailParts[1] : "1x"
        for button in SpeakingPillButton.allCases {
            let center = NSPoint(x: rectOfSpeakingPillButton(button).midX, y: rectOfSpeakingPillButton(button).midY)
            switch button {
            case .speed:
                let speedAttributes: [NSAttributedString.Key: Any] = [
                    .font: NSFont.monospacedDigitSystemFont(ofSize: 11, weight: .bold),
                    .foregroundColor: iconColor,
                ]
                let speedText = speedLabel as NSString
                let speedSize = speedText.size(withAttributes: speedAttributes)
                speedText.draw(at: NSPoint(x: center.x - speedSize.width / 2, y: center.y - speedSize.height / 2), withAttributes: speedAttributes)
            case .rewind:
                for triangleOffset in [CGFloat(-4.5), CGFloat(2.5)] {
                    let triangle = NSBezierPath()
                    triangle.move(to: NSPoint(x: center.x + triangleOffset - 3.5, y: center.y))
                    triangle.line(to: NSPoint(x: center.x + triangleOffset + 3.5, y: center.y + 5))
                    triangle.line(to: NSPoint(x: center.x + triangleOffset + 3.5, y: center.y - 5))
                    triangle.close()
                    triangle.fill()
                }
            case .playOrPause:
                if isPaused {
                    let triangle = NSBezierPath()
                    triangle.move(to: NSPoint(x: center.x - 4, y: center.y + 6.5))
                    triangle.line(to: NSPoint(x: center.x + 6, y: center.y))
                    triangle.line(to: NSPoint(x: center.x - 4, y: center.y - 6.5))
                    triangle.close()
                    triangle.fill()
                } else {
                    for barOffset in [CGFloat(-4), CGFloat(1.5)] {
                        NSBezierPath(
                            roundedRect: NSRect(x: center.x + barOffset, y: center.y - 6.5, width: 3.5, height: 13),
                            xRadius: 1,
                            yRadius: 1
                        ).fill()
                    }
                }
            case .stop:
                NSBezierPath(
                    roundedRect: NSRect(x: center.x - 5, y: center.y - 5, width: 10, height: 10),
                    xRadius: 2,
                    yRadius: 2
                ).fill()
            }
        }
    }

    private func drawRightLabel(_ text: String, monospaced: Bool) {
        let font = monospaced
            ? NSFont.monospacedDigitSystemFont(ofSize: 13, weight: .medium)
            : NSFont.systemFont(ofSize: 12, weight: .medium)
        let attributes: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: NSColor(calibratedWhite: 1, alpha: 0.8),
        ]
        let shortText = String(text.prefix(16))
        let size = (shortText as NSString).size(withAttributes: attributes)
        (shortText as NSString).draw(
            at: NSPoint(x: bounds.width - 20 - size.width, y: bounds.midY - size.height / 2),
            withAttributes: attributes
        )
    }

    private func drawTick(color: NSColor) {
        let centerX: CGFloat = 26
        let centerY = bounds.midY
        color.setFill()
        NSBezierPath(ovalIn: NSRect(x: centerX - 9, y: centerY - 9, width: 18, height: 18)).fill()
        let tick = NSBezierPath()
        tick.move(to: NSPoint(x: centerX - 4.5, y: centerY))
        tick.line(to: NSPoint(x: centerX - 1, y: centerY - 3.5))
        tick.line(to: NSPoint(x: centerX + 4.5, y: centerY + 3.5))
        tick.lineWidth = 2
        tick.lineCapStyle = .round
        tick.lineJoinStyle = .round
        backgroundColor.setStroke()
        tick.stroke()
    }

    private func titleAttributes() -> [NSAttributedString.Key: Any] {
        let paragraphStyle = NSMutableParagraphStyle()
        paragraphStyle.lineBreakMode = .byTruncatingTail
        return [
            .font: NSFont.systemFont(ofSize: 14, weight: .semibold),
            .foregroundColor: NSColor.white,
            .paragraphStyle: paragraphStyle,
        ]
    }

    private func detailAttributes() -> [NSAttributedString.Key: Any] {
        let paragraphStyle = NSMutableParagraphStyle()
        paragraphStyle.lineBreakMode = .byTruncatingTail
        return [
            .font: NSFont.systemFont(ofSize: 11, weight: .regular),
            .foregroundColor: NSColor(calibratedWhite: 1, alpha: 0.7),
            .paragraphStyle: paragraphStyle,
        ]
    }

    /// Title and one line of detail, cut with "…" if they do not fit. A done message shows only its title, because the words are already in the field.
    private func drawMessage() {
        let textLeftEdge: CGFloat = 46
        let textWidth = bounds.width - textLeftEdge - 22
        let showsDetail = tone == "error" && !detail.isEmpty
        let titleY: CGFloat = showsDetail ? 26 : bounds.midY - 9
        (title as NSString).draw(
            with: NSRect(x: textLeftEdge, y: titleY, width: textWidth, height: 18),
            options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine],
            attributes: titleAttributes()
        )
        if showsDetail {
            (detail as NSString).draw(
                with: NSRect(x: textLeftEdge, y: 10, width: textWidth, height: 15),
                options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine],
                attributes: detailAttributes()
            )
        }
    }
}

@main
struct DictationHelperMain {
    static func main() {
        if CommandLine.arguments.contains("--self-test") {
            let ownsWholeField = fieldContainsOnlyPreviousDictation("Okay.", previousText: "Okay. ")
            let leavesSurroundingText = !fieldContainsOnlyPreviousDictation("hello Okay.", previousText: "Okay. ")
            let ignoresEmpty = !fieldContainsOnlyPreviousDictation("Okay.", previousText: " ")
            let keepsUnchangedWords = sharedLeadingCharacterCount("Hello there ", "Hello there, friend. ") == 11
            let countsEmojiAsOneCharacter = sharedLeadingCharacterCount("hi 👋🏽 a", "hi 👋🏽 b") == 5
            let toleratesWrappedLines = textContainsIgnoringLineWrapping("> hello there how\n  are you", "there how are you")
            let neverPressesEnterInTerminal = textSafeForTerminal("ls\nrm -rf x\r\tdone") == "ls rm -rf x  done"
            let pass = ownsWholeField && leavesSurroundingText && ignoresEmpty
                && keepsUnchangedWords && countsEmojiAsOneCharacter && neverPressesEnterInTerminal && toleratesWrappedLines
            print("SELF_TEST pass=\(pass)")
            fflush(stdout)
            exit(pass ? 0 : 1)
        }
        DictationHelper.shared.start()
    }
}
