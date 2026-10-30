# EMBER capability map from the supplied Python project

This document maps the capabilities described and wired in the supplied Python source package. It is a source-based inventory, not a claim that every capability already works on Android.

## Assistant and model layer
- Local, automatic and online model routing.
- Provider pool for configured services including OpenAI-compatible endpoints, Gemini, Anthropic, Groq, OpenRouter and Ollama.
- Multiple provider keys, cooldowns and fallback handling.
- Local model discovery and model benchmarking.
- Shared conversation memory and searchable long-term memory.
- Multi-agent consultation for research, coding, study, files, vision and planning.

## Voice and avatar
- Live voice conversation and speech output.
- Speech-to-text and text-to-speech modules.
- Optional offline voice path using cached Whisper and Kokoro components.
- Wake-word support when a compatible model is installed.
- Echo protection, interruption and voice profiles.
- Animated avatar with facial acting and transcript/audio-driven lip-sync.

## Knowledge, files and productivity
- File intake and document processing.
- Offline knowledge library with indexing/search for supported document types.
- Workspace and project mode with notes, tasks and study progress.
- Coding workspace with Git status, diffs, branches, logs and explicit commits.
- Study tools, teacher tools, quizzes and weak-topic tracking.
- Notifications, daily brief, workflow builder, task planning, undo history and crash recovery.

## Device and desktop automation
- Screen understanding and visual computer-agent modules.
- Desktop actions, application launching, file operations and system monitoring.
- Browser automation and web search.
- Optional game control and WhatsApp Web automation.
- Smart-home connector when configured by the user.
- Companion device pairing, clipboard sync and remote project-status endpoints.

## Privacy and control
- Separate autonomy controls for desktop, game, messaging and WhatsApp automation.
- Confirmation gates for selected sensitive actions.
- Never-touch application list.
- Emergency kill switch.
- Privacy audit features and temporary-memory mode.
- Device pairing controls and revocation.

## Optional dependencies and external services
Some features require external software, models, accounts, API keys or hardware. Examples include a local model server, provider API credentials, offline speech models, Playwright browser binaries, optional speaker-identification packages and a configured Home Assistant instance. The source package explicitly treats these as optional rather than pretending they are installed.

## Android translation boundary
The supplied Python app is a desktop application built around PyQt and operating-system automation libraries. Those modules do not become Android capabilities merely by placing the HTML interface inside an APK.

The Android project therefore keeps the supplied HTML as its UI and packages it in Capacitor. Android-only features must be implemented through reviewed native plugins and Android runtime permissions. Desktop-only actions must remain unavailable on the phone unless a separately paired desktop companion exposes a narrowly scoped, authenticated operation.

## Important safety defaults
- No automatic sending, deleting, installing, purchasing or other sensitive action without an explicit confirmation flow.
- No hidden microphone or camera capture.
- No background screen capture.
- No automatic WhatsApp replies until a user deliberately configures and enables that feature.
- The privacy kill switch must take precedence over all optional tools.
- Provider keys and personal memory must never be committed to the public repository.
