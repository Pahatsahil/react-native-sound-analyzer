import Foundation
import AVFoundation
import React

@objc(SoundRecorder)
class SoundRecorder: RCTEventEmitter, SoundAnalyzerListener {
  private var audioRecorder: AVAudioRecorder?
  private var audioAnalyzer: AudioAnalyzer?
  private var analyzerTimer: Timer?
  private var outputURL: URL?
  private var isRecording = false
  private var startTimeMillis: Int64 = 0
  private var audioFileSize: Int64 = 0
  private var audioFileType: String = "audio/m4a"
  private var sampleRate: Int = 44100
  
  override static func requiresMainQueueSetup() -> Bool {
    return false
  }
  
  override func supportedEvents() -> [String] {
         return ["onAudioAnalysis", "onSoundTypeDetected"]
     }
     
     // Add this method to implement SoundAnalyzerListener
     func onSoundTypeDetected(soundType: String) {
         self.sendEvent(withName: "onSoundTypeDetected", body: [
             "soundType": soundType,
             "timestamp": Date().timeIntervalSince1970 * 1000,
             "volume": audioAnalyzer?.volume ?? 0.0,
             "noiseLevel": audioAnalyzer?.noiseLevel ?? 0.0
         ])
     }
  
  @objc func getIsRecording(_ resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
    resolve(isRecording)
  }
  
  @objc func startRecording(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
    // Check microphone permission
    AVAudioSession.sharedInstance().requestRecordPermission { [weak self] allowed in
      guard let self = self else { return }
      
      if !allowed {
        reject("PERMISSION_DENIED", "Microphone and storage permissions required", nil)
        return
      }
      
      DispatchQueue.main.async {
        do {
          // Configure audio session
          try AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .default)
          try AVAudioSession.sharedInstance().setActive(true)
          
          // Create output file URL
          let outputURL = self.createOutputFile()
          self.outputURL = outputURL
          
          // Set up audio settings
          let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
//            AVFormatIDKey: Int(kAudioFormatLinearPCM),
            AVSampleRateKey: self.sampleRate,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 128000,
//            AVLinearPCMBitDepthKey: 16,
//            AVLinearPCMIsFloatKey: false,
//            AVLinearPCMIsBigEndianKey: false,
//            AVLinearPCMIsNonInterleaved: false,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
          ]
          
          // Create and start the recorder
          self.audioRecorder = try AVAudioRecorder(url: outputURL, settings: settings)
          self.audioRecorder?.isMeteringEnabled = true
          self.audioRecorder?.prepareToRecord()
          let success = self.audioRecorder?.record() ?? false
          
          if success {
            self.isRecording = true
            self.startTimeMillis = Int64(Date().timeIntervalSince1970 * 1000)
            self.audioFileSize = 0
            
            // Create audio analyzer
            self.audioAnalyzer = AudioAnalyzer()
            self.audioAnalyzer?.setListener(self)
            
            // Start analysis timer
            self.onAudioAnalysis()
            
            // Return response
            let result: [String: Any] = [
              "path": outputURL.path,
              "type": self.audioFileType,
              "duration": 0.0,
              "sizeInKB": 0.0
            ]
            resolve(result)
          } else {
            reject("RECORD_ERROR", "Failed to start recording", nil)
          }
        } catch {
          reject("RECORD_ERROR", "Recording failed: \(error.localizedDescription)", nil)
        }
      }
    }
  }
  
  @objc func stopRecording(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
    if !isRecording {
      reject("NOT_RECORDING", "Not currently recording", nil)
      return
    }
    
    DispatchQueue.main.async {
      // Stop analysis timer
      self.analyzerTimer?.invalidate()
      self.analyzerTimer = nil
      
      // Stop recording
      self.audioRecorder?.stop()
      self.isRecording = false
      
      guard let outputURL = self.outputURL else {
        reject("FILE_ERROR", "Output file not available", nil)
        return
      }
      
      do {
        // Get file attributes
        let fileAttributes = try FileManager.default.attributesOfItem(atPath: outputURL.path)
        let fileSize = fileAttributes[.size] as? NSNumber ?? NSNumber(value: 0)
        self.audioFileSize = fileSize.int64Value
        
        // Calculate duration
        let durationInSeconds: Double = Double(Int64(Date().timeIntervalSince1970 * 1000) - self.startTimeMillis) / 1000.0
        
        // Return response
        let result: [String: Any] = [
          "path": outputURL.path,
          "type": self.audioFileType,
          "duration": durationInSeconds,
          "sizeInKB": Double(fileSize.intValue) / 1024.0
        ]
        resolve(result)
      } catch {
        reject("FILE_ERROR", "Error finalizing audio file: \(error.localizedDescription)", nil)
      }
    }
  }
  
  @objc func getRecordingInfo(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
    guard let outputURL = self.outputURL, FileManager.default.fileExists(atPath: outputURL.path) else {
      reject("NO_FILE", "No recording file available", nil)
      return
    }
    
    do {
      // Get file attributes
      let fileAttributes = try FileManager.default.attributesOfItem(atPath: outputURL.path)
      let fileSize = fileAttributes[.size] as? NSNumber ?? NSNumber(value: 0)
      
      // Calculate duration
      let duration: Double
      if isRecording {
        duration = Double(Int64(Date().timeIntervalSince1970 * 1000) - startTimeMillis) / 1000.0
      } else {
        duration = calculateWavDuration(wavURL: outputURL, sampleRate: self.sampleRate)
      }
      
      // Return response
      let result: [String: Any] = [
        "path": outputURL.path,
        "type": self.audioFileType,
        "duration": duration,
        "sizeInKB": Double(fileSize.intValue) / 1024.0
      ]
      resolve(result)
    } catch {
      reject("FILE_ERROR", "Error getting file info: \(error.localizedDescription)", nil)
    }
  }
  
  private func onAudioAnalysis() {
    if audioAnalyzer == nil {
           audioAnalyzer = AudioAnalyzer()
           audioAnalyzer?.setListener(self)
       }
    // Create analyzer timer to update every second
    analyzerTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
      guard let self = self, self.isRecording, let recorder = self.audioRecorder else { return }
      
      recorder.updateMeters()
      
      // Get audio levels
      let averagePower = recorder.averagePower(forChannel: 0)
      let peakPower = recorder.peakPower(forChannel: 0)
      
      // Update analyzer with current levels
      self.audioAnalyzer?.analyze(averagePower: averagePower, peakPower: peakPower)
      
      // Get file size
      if let outputURL = self.outputURL {
        do {
          let fileAttributes = try FileManager.default.attributesOfItem(atPath: outputURL.path)
          if let fileSize = fileAttributes[.size] as? NSNumber {
            self.audioFileSize = fileSize.int64Value
          }
        } catch {
          print("Error getting file size: \(error.localizedDescription)")
        }
      }
      
      // Send analysis event
      let duration = Double(Int64(Date().timeIntervalSince1970 * 1000) - self.startTimeMillis) / 1000.0
      self.sendAnalysisEvent(duration: duration)
    }
  }
  
  // Update the sendAnalysisEvent method
   private func sendAnalysisEvent(duration: Double) {
       guard let analyzer = audioAnalyzer, let outputURL = outputURL else { return }
       print(duration,"DURATION")
       self.sendEvent(withName: "onAudioAnalysis", body: [
           "volume": analyzer.volume,
           "noiseLevel": analyzer.noiseLevel,
           "soundType": analyzer.detectedSoundType,
           "path": outputURL.path,
           "type": audioFileType,
           "duration": duration,
           "sizeInKB": Double(audioFileSize) / 1024.0
       ])
   }
   
   // Add this method to match Kotlin's getCurrentAnalysis
   @objc func getCurrentAnalysis(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
       let result: [String: Any] = [
           "volume": audioAnalyzer?.volume ?? 0.0,
           "noiseLevel": audioAnalyzer?.noiseLevel ?? 0.0,
           "soundType": audioAnalyzer?.detectedSoundType ?? "Silent",
           "isRecording": isRecording
       ]
       resolve(result)
   }
  
  private func createOutputFile() -> URL {
    let dateFormatter = DateFormatter()
    dateFormatter.dateFormat = "yyyyMMdd_HHmmss"
    let timestamp = dateFormatter.string(from: Date())
    let fileName = "audio_\(timestamp).m4a"
    
    // Get documents directory
    let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    let recordingsDirectory = documentsPath.appendingPathComponent("recordings", isDirectory: true)
    
    // Create recordings directory if it doesn't exist
    try? FileManager.default.createDirectory(at: recordingsDirectory, withIntermediateDirectories: true)
    
    return recordingsDirectory.appendingPathComponent(fileName)
  }
  
  private func calculateWavDuration(wavURL: URL, sampleRate: Int) -> Double {
    do {
      let audioFile = try AVAudioFile(forReading: wavURL)
      let frameCount = Double(audioFile.length)
      let duration = frameCount / Double(audioFile.processingFormat.sampleRate)
      return duration
    } catch {
      print("Error calculating duration: \(error.localizedDescription)")
      // Fallback: estimate from file size
      let fileSize = (try? FileManager.default.attributesOfItem(atPath: wavURL.path)[.size] as? NSNumber)?.intValue ?? 0
      // Subtract 44 bytes for header, assuming 16-bit mono
      let audioDataSize = Double(fileSize - 44)
      let bytesPerSecond = Double(sampleRate * 2) // 16-bit mono = 2 bytes per sample
      return audioDataSize / bytesPerSecond
    }
  }
}


protocol SoundAnalyzerListener: AnyObject {
    func onSoundTypeDetected(soundType: String)
}

class AudioAnalyzer {
    weak var listener: SoundAnalyzerListener?
    private let fft = FFT(n: 1024)
    
    // Analysis properties - matching Kotlin exactly
    var volume: Double = 0.0
    var noiseLevel: Double = 0.0
    var detectedSoundType: String = "Silent"
    var isScream: Bool = false

    // For throttling sound type notifications - matching Kotlin
    private var lastSoundType: String = "Silent"
    private var lastNotificationTime: Int64 = 0
    private let notificationCooldown: Int64 = 500 // 500ms cooldown
    
    func setListener(_ listener: SoundAnalyzerListener?) {
        self.listener = listener
    }
    func analyze(averagePower: Float, peakPower: Float) {
    // Convert dB to linear scale (approximating buffer values)
    let peakLinear = pow(10.0, Double(peakPower) / 20.0) * 32767.0
    let avgLinear = pow(10.0, Double(averagePower) / 20.0) * 32767.0
    
    // Simulate buffer for FFT analysis
    let bufferSize = 1024
    let simulatedBuffer = generateSimulatedBuffer(peakValue: peakLinear, avgValue: avgLinear, size: bufferSize)
    
    // Calculate volume (RMS)
    var sum: Double = 0.0
    for value in simulatedBuffer {
        sum += value * value
    }
    self.volume = sqrt(sum / Double(simulatedBuffer.count))
    
    // Prepare data for FFT
    var real = [Double](repeating: 0.0, count: 1024)
    for i in 0..<min(simulatedBuffer.count, 1024) {
        real[i] = simulatedBuffer[i]
    }
    var imag = [Double](repeating: 0.0, count: 1024)
    
    // Perform FFT
    fft.fft(real: &real, imag: &imag)
    
    // Calculate magnitude
    var magnitude = [Double](repeating: 0.0, count: 512)
    for i in 0..<512 {
        magnitude[i] = sqrt(real[i] * real[i] + imag[i] * imag[i])
    }
    
    // Compute noiseLevel
    let totalMagnitude = magnitude.reduce(0, +)
    self.noiseLevel = totalMagnitude / Double(magnitude.count)
    
    // Frequency analysis for scream detection
    let sampleRate = 44100
    let highFreqStart = min((3000 * 1024 / sampleRate), 511)
    let highFreqEnd = min((8000 * 1024 / sampleRate), 511)
    
    var highFreqEnergy: Double = 0.0
    if highFreqEnd > highFreqStart {
        let slice = magnitude[highFreqStart...highFreqEnd]
        highFreqEnergy = slice.reduce(0, +) / Double(slice.count)
    }
    
    // Simple scream threshold (you can tune this!)
    self.isScream = highFreqEnergy > 500.0 && self.volume > 2000.0
}

    
    // Helper function to simulate buffer from iOS audio levels
    private func generateSimulatedBuffer(peakValue: Double, avgValue: Double, size: Int) -> [Double] {
        var buffer = Array(repeating: 0.0, count: size)
        
        // Generate a synthetic audio buffer based on the peak and average values
        for i in 0..<size {
            // Create a mix of sine waves to simulate real audio
            let t = Double(i) / Double(size)
            let baseWave = sin(t * 2 * Double.pi * 440) // 440 Hz base frequency
            let harmonic = sin(t * 2 * Double.pi * 880) * 0.3 // 880 Hz harmonic
            let noise = Double.random(in: -1...1) * 0.1 // Small amount of noise
            
            let sample = (baseWave + harmonic + noise) * avgValue
            buffer[i] = max(-32767, min(32767, sample))
        }
        
        return buffer
    }
}

class FFT {
    private let n: Int
    private let cos: [Double]
    private let sin: [Double]
    
    init(n: Int) {
        guard n > 0 && (n & (n - 1)) == 0 else {
            fatalError("n must be a power of 2")
        }
        
        self.n = n
        var cosArray = [Double]()
        var sinArray = [Double]()
        
        for i in 0..<(n / 2) {
            cosArray.append(Darwin.cos(-2.0 * Double.pi * Double(i) / Double(n)))
            sinArray.append(Darwin.sin(-2.0 * Double.pi * Double(i) / Double(n)))
        }
        
        self.cos = cosArray
        self.sin = sinArray
    }
    
    func fft(real: inout [Double], imag: inout [Double]) {
        let bits = n.trailingZeroBitCount
        
        // Bit-reverse permutation
        for i in 0..<n {
            let j = reverseBits(UInt32(i), bits: UInt32(bits))
            if j > i {
                real.swapAt(i, Int(j))
                imag.swapAt(i, Int(j))
            }
        }
        
        // Cooley-Tukey FFT
        var size = 2
        while size <= n {
            let halfSize = size / 2
            let tableStep = n / size
            
            var i = 0
            while i < n {
                var k = 0
                for j in i..<(i + halfSize) {
                    let tReal = cos[k] * real[j + halfSize] - sin[k] * imag[j + halfSize]
                    let tImag = sin[k] * real[j + halfSize] + cos[k] * imag[j + halfSize]
                    real[j + halfSize] = real[j] - tReal
                    imag[j + halfSize] = imag[j] - tImag
                    real[j] += tReal
                    imag[j] += tImag
                    k += tableStep
                }
                i += size
            }
            size *= 2
        }
    }
    
    private func reverseBits(_ value: UInt32, bits: UInt32) -> UInt32 {
        var reversed: UInt32 = 0
        var val = value
        for _ in 0..<bits {
            reversed = (reversed << 1) | (val & 1)
            val >>= 1
        }
        return reversed
    }
}