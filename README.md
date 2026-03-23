# react-native-sound-analyzer

Real-time audio recording and sound type detection for React Native. Supports Android and iOS.

Detects five sound types: `Silent`, `Normal`, `Screaming`, `BackgroundNoise`, `Speaking+BackgroundNoise`.

---

## Installation

```bash
npm install react-native-sound-analyzer
```

### iOS

```bash
npx pod-install
```

### Android

No extra steps — auto-linked.

---

## Permissions

### Android

Add to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

### iOS

Add to `Info.plist`:

```xml
<key>NSMicrophoneUsageDescription</key>
<string>Microphone access is needed to analyze audio.</string>
```

---

## Usage

### Hook (recommended)

```tsx
import { useAudioAnalyzer } from "react-native-sound-analyzer";

export default function App() {
  const { isRecording, volume, noiseLevel, soundType, start, stop } =
    useAudioAnalyzer();

  return (
    <>
      <Text>{soundType}</Text>
      <Button
        title={isRecording ? "Stop" : "Start"}
        onPress={isRecording ? stop : start}
      />
    </>
  );
}
```

### Direct API

```tsx
import { SoundRecorder } from "react-native-sound-analyzer";

// Start recording
const info = await SoundRecorder.startRecording();
console.log(info.path); // file path of the recording

// Listen to real-time analysis
const sub = SoundRecorder.onAudioAnalysis((data) => {
  console.log(data.soundType, data.volume, data.noiseLevel);
});

// Listen to sound type changes only
const sub2 = SoundRecorder.onSoundTypeDetected((data) => {
  console.log(data.soundType); // e.g. "Screaming"
});

// Stop recording
const result = await SoundRecorder.stopRecording();
console.log(result.duration, result.sizeInKB);

// Clean up listeners
sub.remove();
sub2.remove();
```

---

## API

### `useAudioAnalyzer()`

| Return value  | Type                  | Description                   |
| ------------- | --------------------- | ----------------------------- |
| `isRecording` | `boolean`             | Whether recording is active   |
| `volume`      | `number`              | Current RMS volume level      |
| `noiseLevel`  | `number`              | Average frequency magnitude   |
| `soundType`   | `SoundType`           | Detected sound classification |
| `start()`     | `() => Promise<void>` | Start recording and analysis  |
| `stop()`      | `() => Promise<void>` | Stop recording and analysis   |

### `SoundRecorder`

| Method                    | Returns                  | Description                                               |
| ------------------------- | ------------------------ | --------------------------------------------------------- |
| `startRecording()`        | `Promise<RecordingInfo>` | Start recording                                           |
| `stopRecording()`         | `Promise<RecordingInfo>` | Stop and finalize file                                    |
| `getIsRecording()`        | `Promise<boolean>`       | Current recording state                                   |
| `getRecordingInfo()`      | `Promise<RecordingInfo>` | File path, duration, size                                 |
| `getCurrentAnalysis()`    | `Promise<AudioAnalysis>` | Snapshot of current levels                                |
| `onAudioAnalysis(cb)`     | `EmitterSubscription`    | Real-time analysis stream (~20Hz on Android, ~1Hz on iOS) |
| `onSoundTypeDetected(cb)` | `EmitterSubscription`    | Fires when sound type changes                             |

### Types

```ts
type SoundType =
  | "Silent"
  | "Normal"
  | "Screaming"
  | "BackgroundNoise"
  | "Speaking+BackgroundNoise";

interface RecordingInfo {
  path: string;
  type: string; // 'audio/wav' on Android, 'audio/m4a' on iOS
  duration: number; // seconds
  sizeInKB: number;
}

interface AudioAnalysis extends RecordingInfo {
  volume: number;
  noiseLevel: number;
  soundType: SoundType;
}
```

---

## Notes

- Android records as `.wav`, iOS records as `.m4a`
- Analysis update rate is ~20 per second on Android, ~1 per second on iOS
- Always remove event subscriptions when your component unmounts

---

## License

MIT
