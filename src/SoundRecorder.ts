import {
  NativeModules,
  NativeEventEmitter,
  EmitterSubscription,
} from "react-native";
import type { AudioAnalysis, RecordingInfo, SoundTypeEvent } from "./types";

const { SoundRecorder: NativeSoundRecorder } = NativeModules;

if (!NativeSoundRecorder) {
  throw new Error(
    "react-native-sound-analyzer: Native module not found. " +
      "Did you run `npx pod-install` (iOS) or rebuild the Android project?",
  );
}

const emitter = new NativeEventEmitter(NativeSoundRecorder);

export const SoundRecorder = {
  startRecording(): Promise<RecordingInfo> {
    return NativeSoundRecorder.startRecording();
  },

  stopRecording(): Promise<RecordingInfo> {
    return NativeSoundRecorder.stopRecording();
  },

  getIsRecording(): Promise<boolean> {
    return NativeSoundRecorder.getIsRecording();
  },

  getRecordingInfo(): Promise<RecordingInfo> {
    return NativeSoundRecorder.getRecordingInfo();
  },

  getCurrentAnalysis(): Promise<AudioAnalysis> {
    return NativeSoundRecorder.getCurrentAnalysis();
  },

  onAudioAnalysis(
    callback: (data: AudioAnalysis) => void,
  ): EmitterSubscription {
    return emitter.addListener("onAudioAnalysis", callback);
  },

  onSoundTypeDetected(
    callback: (data: SoundTypeEvent) => void,
  ): EmitterSubscription {
    return emitter.addListener("onSoundTypeDetected", callback);
  },
};
