export interface AudioAnalysis {
  volume: number;
  noiseLevel: number;
  soundType: SoundType;
  path: string;
  type: string;
  duration: number;
  sizeInKB: number;
}

export interface RecordingInfo {
  path: string;
  type: string;
  duration: number;
  sizeInKB: number;
}

export type SoundType =
  | "Silent"
  | "Screaming"
  | "Speaking+BackgroundNoise"
  | "BackgroundNoise"
  | "Normal";

export interface SoundTypeEvent {
  soundType: SoundType;
  timestamp: number;
  volume: number;
  noiseLevel: number;
}
