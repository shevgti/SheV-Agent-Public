# SheV voice conversation

The main screen is a full-screen conversation. Voice mode is started with the microphone button and uses the Android speech recognition service for Russian input.

## Voice turn flow

1. SheV listens until Android speech recognition returns a result.
2. The recognized text is sent to the selected chat backend.
3. The assistant reply is spoken using Android TextToSpeech or the optional Supertonic neural voice pack.
4. Voice mode attempts to keep SpeechRecognizer active during playback. When Android reports the beginning of user speech, the app stops current TextToSpeech / Supertonic AudioTrack output and treats the new speech as the next user turn (voice barge-in).

## Important limitations

This is best-effort voice barge-in, not true simultaneous full-duplex streaming. SpeechRecognizer and speech synthesis are separate Android services, and their concurrent microphone/audio behavior varies by device, Android version, speech service, speaker volume and acoustic echo cancellation. The assistant's own loudspeaker output may be picked up by the microphone and can cause false interruption on some devices. The implementation must be tested on physical devices; if the recognizer service suspends during playback, voice interruption will not work reliably. A continuous low-latency duplex audio pipeline is still future work.

The voice UI does not grant the model general device control. Tool execution requires separately implemented, permission-gated adapters and explicit confirmation for sensitive actions.
