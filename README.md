This is a Mac dictation app. Press ⌘D in any text field, speak, then press ⌘D again. The words are typed where you were writing.

Speech recognition runs on this Mac with [LiquidAI LFM3-Audio-2B](https://huggingface.co/LiquidAI/LFM3-Audio-2B). The app uses the official GGUF build of that model, `LFM3-Audio-2B-Q8_0`, through llama.cpp. That is the on-device checkpoint. The window itself is Compose Multiplatform for desktop.

The weights live in `models/LFM3-Audio-2B/`:

- `LFM3-Audio-2B-Q8_0.gguf`
- `mmproj-LFM3-Audio-2B-Q8_0.gguf`

llama.cpp 0.8.0 or newer is required (`brew install llama.cpp`).

### Run

```bash
./gradlew :desktopApp:run
```

The first launch loads the model, which takes a little while. macOS will ask for two permissions:

- Microphone, for the Java process that records you
- Accessibility, for the app `Rvoice` (`native/macos/Rvoice.app`), so dictated text can be typed into other apps

⌘D is reserved while Rvoice is open, so other apps will not see that shortcut. Closing the window leaves dictation running. Quit from the menu bar icon.

A take can run as long as you like (it stops on its own after 20 minutes as a safety net). Long takes are cut at pauses every 15 to 25 seconds, so live updates stay quick. The language picker changes the instruction sent to the model (`Perform ASR in American English.`, and the other languages on the model card).
