# LLMDroid

An Android app that lets you control your phone with natural language — by typing or by voice — using any OpenAI-compatible LLM. It reads the screen via the Accessibility API, sends the UI tree to the model, and executes the actions the model returns.

Works fully on **GrapheneOS** (and stock Android) with no Google services required.

## Features

- **Accessibility-driven automation** — the agent reads the live UI tree and performs taps, swipes, typing, scrolling, app launches, URL navigation, and more
- **Wake word detection** — say "Hey Dicio" to activate hands-free; uses [OpenWakeWord](https://github.com/dscripka/openWakeWord) via TFLite, runs fully on-device
- **Offline speech recognition** — [Vosk](https://alphacephei.com/vosk/) at 44 100 Hz, no Google STT required; ~40 MB model downloaded once on first use
- **Any OpenAI-compatible backend** — works with local servers (Ollama, llama.cpp, LM Studio) or hosted APIs (OpenAI, Anthropic-compatible proxies)
- **Heads-up notification while listening** — pops up over any screen when wake word fires, updates live with partial transcription
- **In-app mic button** — type or speak directly from the chat screen when the wake word isn't needed
- **Conversation history** — multi-turn context window kept across agent steps
- **Logs screen** — full trace of every UI turn, LLM response, and action result

## How it works

Each agent turn:
1. The accessibility service reads the on-screen UI into an indexed flat list
2. That list, the goal, and recent action results are sent to the LLM
3. The model responds with a JSON object: a message, a `done` flag, and a list of actions
4. Actions are executed in order; then the loop repeats until `done` or max iterations

The model never needs tool-calling support — everything is plain JSON in the system prompt.

## Requirements

- Android 7.0+ (API 24), tested on Android 15 / GrapheneOS
- Microphone permission (for voice input and wake word)
- Draw over other apps permission (for the overlay pill)
- Accessibility service enabled (the core of how actions are executed)
- A running OpenAI-compatible LLM endpoint

## Setup

1. Install the APK
2. Open the app and go to **Settings**
3. Enable the **Accessibility Service** and **Draw Over Other Apps** permissions
4. Set your LLM **Base URL** and optionally an **API Key** and **Model** name
5. Tap **Test API** to verify the connection
6. Grant **Microphone** permission if you want voice input
7. Toggle **Enable 'Hey Dicio' listener** to start the always-on wake word service

On first wake word use, the app downloads two models automatically:
- OpenWakeWord (~3 MB, TFLite)
- Vosk small English (~40 MB)

## Supported actions

The LLM can issue any of these in a single response (up to 10 per turn):

| Action | Description |
|--------|-------------|
| `tap` | Tap a UI element by index |
| `longpress` | Long-press a UI element |
| `focus` | Focus a UI element |
| `replace_text` | Replace text in a field |
| `type` | Type text (no target index needed) |
| `paste` | Paste clipboard into a field |
| `clear` | Clear a text field |
| `enter` | Press the Enter/Return key |
| `back` | Press Back |
| `home` | Press Home |
| `notifications` | Open notification shade |
| `recents` | Open recents/overview |
| `wait` | Wait N milliseconds |
| `swipe` | Swipe between two screen coordinates |
| `launch` | Launch an app by package name |
| `open_url` | Open a URL in the default browser |
| `open_settings` | Open a specific Android settings page |
| `keyevent` | Send a key event by code |
| `intent` | Fire an arbitrary Android intent |
| `clipboard_set` | Write text to the clipboard |
| `clipboard_get` | Read text from the clipboard |

## Project structure

```
app/src/main/java/com/alanix/llmdroid/
├── accessibility/
│   ├── GestureExecutor.kt       # Executes accessibility actions
│   ├── LLMAccessibilityService.kt
│   └── ScreenTreeBuilder.kt     # Flattens the UI into an indexed list
├── agent/
│   ├── AgentLoop.kt             # Main LLM → action loop
│   ├── AgentService.kt          # Foreground service wrapping AgentLoop
│   ├── CommandParser.kt         # Parses LLM JSON responses
│   └── WakeWordService.kt       # Always-on wake word + Vosk transcription
├── data/
│   ├── ConversationHistory.kt
│   └── SettingsStore.kt         # DataStore-backed settings
├── io/
│   ├── stt/VoskManager.kt       # Vosk model lifecycle + SpeechService
│   ├── tts/AndroidTts.kt        # TextToSpeech wrapper
│   └── wake/
│       ├── OwwModel.kt          # TFLite OpenWakeWord inference
│       └── WakeWordDetector.kt  # Model download + frame processing
├── model/                       # Data classes
├── network/
│   ├── OpenAiClient.kt          # HTTP client for LLM API
│   └── OpenAiModels.kt
├── overlay/
│   ├── AgentOverlay.kt          # System overlay window
│   └── OverlayPill.kt           # Floating status pill
└── ui/
    ├── VoiceInput.kt            # In-app mic button logic
    └── screens/
        ├── ChatScreen.kt
        ├── LogsScreen.kt
        └── SettingsScreen.kt
```
