import { useState, useEffect, useRef } from "react";
import { SoundRecorder } from "./SoundRecorder";
import type { AudioAnalysis, SoundType } from "./types";

interface UseAudioAnalyzerResult {
  isRecording: boolean;
  volume: number;
  noiseLevel: number;
  soundType: SoundType;
  start: () => Promise<void>;
  stop: () => Promise<void>;
}

export function useAudioAnalyzer(): UseAudioAnalyzerResult {
  const [isRecording, setIsRecording] = useState(false);
  const [analysis, setAnalysis] = useState<
    Pick<AudioAnalysis, "volume" | "noiseLevel" | "soundType">
  >({
    volume: 0,
    noiseLevel: 0,
    soundType: "Silent",
  });

  const subscriptionRef = useRef<ReturnType<
    typeof SoundRecorder.onAudioAnalysis
  > | null>(null);

  useEffect(() => {
    return () => {
      subscriptionRef.current?.remove();
    };
  }, []);

  async function start() {
    await SoundRecorder.startRecording();
    setIsRecording(true);
    subscriptionRef.current = SoundRecorder.onAudioAnalysis((data) => {
      setAnalysis({
        volume: data.volume,
        noiseLevel: data.noiseLevel,
        soundType: data.soundType,
      });
    });
  }

  async function stop() {
    subscriptionRef.current?.remove();
    await SoundRecorder.stopRecording();
    setIsRecording(false);
  }

  return { isRecording, start, stop, ...analysis };
}
