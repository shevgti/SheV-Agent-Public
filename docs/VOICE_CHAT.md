# SheV voice conversation

The main screen is a full-screen conversation. Voice mode is started with the microphone button and uses the Android speech recognition service for Russian input.

The current interaction is turn-based:
1. SheV listens until Android speech recognition returns a result.
2. The recognized text is sent to the selected chat backend.
3. The assistant reply is spoken using Android TextToSpeech.
4. When speech playback finishes, listening starts again while voice mode remains enabled.

This is not simultaneous full-duplex streaming. Recognition and speech synthesis are provided by Android services on the device, so installed language packs and device speech services affect availability and quality.

The small settings control opens the optional OmniRoute gateway configuration. The default screen does not show projects, tasks, memory panels, tools, or dashboard cards.

The voice UI does not grant the model general device control. Tool execution requires separately implemented, permission-gated adapters and explicit confirmation for sensitive actions.
