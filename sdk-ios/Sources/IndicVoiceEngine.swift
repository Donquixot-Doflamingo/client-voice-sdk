// IndicVoiceEngine.swift
// The iOS engines of the Indic Voice SDK and the facade that routes between them:
//   tier 1  AppleSpeechEngine (SFSpeechRecognizer) + AppleTtsEngine (AVSpeechSynthesizer.write)
//   tier 2  SherpaSttEngine / SherpaTtsEngine / SherpaVad on sherpa-onnx 1.13.8 (SherpaOnnx.swift wrappers)
//   tier 3  HostedSttEngine / HostedTtsEngine (IndicVoiceCore.swift)
// plus MicrophoneSource (AVAudioEngine tap -> 16 kHz Int16 frames), AudioPlayback (AVAudioPlayer) and VoiceSdk,
// the port of sdk-core's IndicVoice.kt (tier policy, capabilities, transcribe/benchmark, live sessions, synthesize).
//
// Threading: everything here is called from background GCD queues (the Expo module never calls into the SDK on
// the main thread). Blocking waits use DispatchSemaphore; Apple callbacks are funnelled onto private serial queues.

import Foundation
import AVFoundation
import Speech
import SherpaOnnxC

// MARK: - Audio session

/// Keeps AVAudioSession in a state that suits what the SDK is doing right now: playAndRecord (speaker, voice chat
/// processing) while any microphone session is open, playback otherwise.
final class AudioSessionManager {
    static let shared = AudioSessionManager()
    private let lock = NSLock()
    private var recorders = 0
    private var playing = false

    func beginRecording() throws {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker])
            try session.setActive(true, options: [])
        } catch {
            throw VoiceError(.ENGINE_FAILED, "AVAudioSession could not be activated for recording: \(error.localizedDescription)")
        }
        lock.lock(); recorders += 1; lock.unlock()
    }

    func endRecording() {
        lock.lock()
        recorders = max(0, recorders - 1)
        let idle = recorders == 0 && !playing
        lock.unlock()
        if idle { try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation]) }
    }

    func beginPlayback() throws {
        lock.lock(); let rec = recorders; playing = true; lock.unlock()
        let session = AVAudioSession.sharedInstance()
        do {
            if rec == 0 { try session.setCategory(.playback, mode: .default, options: []) }
            try session.setActive(true, options: [])
        } catch {
            lock.lock(); playing = false; lock.unlock()
            throw VoiceError(.ENGINE_FAILED, "AVAudioSession could not be activated for playback: \(error.localizedDescription)")
        }
    }

    func endPlayback() {
        lock.lock(); playing = false; let idle = recorders == 0; lock.unlock()
        if idle { try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation]) }
    }
}

// MARK: - Microphone

/// AVAudioEngine input tap. Delivers the raw hardware-format buffers (for Apple Speech) and the same audio converted
/// to 16 kHz mono Int16 frames (for the VAD/segmenter, level meter and tiers 2/3).
final class MicrophoneSource {
    private let engine = AVAudioEngine()
    private var converter: AVAudioConverter?
    private var targetFormat: AVAudioFormat?
    private let lock = NSLock()
    private var isRunning = false

    func start(onBuffer: ((AVAudioPCMBuffer) -> Void)?, onFrame: (([Int16]) -> Void)?) throws {
        lock.lock(); let already = isRunning; lock.unlock()
        if already { return }
        try AudioSessionManager.shared.beginRecording()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            AudioSessionManager.shared.endRecording()
            throw VoiceError(.NO_ENGINE, "No audio input is available (simulator without a microphone?)")
        }
        if let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: true) {
            targetFormat = target
            converter = AVAudioConverter(from: format, to: target)
        }
        lock.lock(); isRunning = true; lock.unlock()
        input.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] buffer, _ in
            guard let self = self else { return }
            self.lock.lock(); let run = self.isRunning; self.lock.unlock()
            if !run { return }
            onBuffer?(buffer)
            if let cb = onFrame {
                let frame = self.toPcm16k(buffer)
                if !frame.isEmpty { cb(frame) }
            }
        }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            lock.lock(); isRunning = false; lock.unlock()
            input.removeTap(onBus: 0)
            AudioSessionManager.shared.endRecording()
            throw VoiceError(.ENGINE_FAILED, "AVAudioEngine could not start: \(error.localizedDescription)")
        }
    }

    func stop() {
        lock.lock(); let was = isRunning; isRunning = false; lock.unlock()
        if !was { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        AudioSessionManager.shared.endRecording()
    }

    private func toPcm16k(_ buffer: AVAudioPCMBuffer) -> [Int16] {
        if let conv = converter, let target = targetFormat, buffer.format.sampleRate > 0 {
            let ratio = target.sampleRate / buffer.format.sampleRate
            let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 64
            if let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) {
                var supplied = false
                var error: NSError? = nil
                let status = conv.convert(to: out, error: &error) { _, outStatus -> AVAudioBuffer? in
                    if supplied {
                        outStatus.pointee = .noDataNow
                        return nil
                    }
                    supplied = true
                    outStatus.pointee = .haveData
                    return buffer
                }
                if status != .error, let ch = out.int16ChannelData {
                    let n = Int(out.frameLength)
                    if n > 0 { return Array(UnsafeBufferPointer(start: ch[0], count: n)) }
                    return []
                }
            }
        }
        return MicrophoneSource.manualDownmix(buffer)
    }

    /// Fallback when AVAudioConverter is unavailable: channel 0 + linear resampling to 16 kHz.
    static func manualDownmix(_ buffer: AVAudioPCMBuffer) -> [Int16] {
        let n = Int(buffer.frameLength)
        if n == 0 { return [] }
        let channels = max(1, Int(buffer.format.channelCount))
        let stride = buffer.format.isInterleaved ? channels : 1
        var mono = [Float](repeating: 0, count: n)
        if let f = buffer.floatChannelData {
            let p = f[0]
            for i in 0..<n { mono[i] = p[i * stride] }
        } else if let s = buffer.int16ChannelData {
            let p = s[0]
            for i in 0..<n { mono[i] = Float(p[i * stride]) / 32768 }
        } else if let s = buffer.int32ChannelData {
            let p = s[0]
            for i in 0..<n { mono[i] = Float(Double(p[i * stride]) / 2147483648.0) }
        } else {
            return []
        }
        let inRate = buffer.format.sampleRate
        if inRate <= 0 { return [] }
        let ratio = inRate / 16000.0
        let outN = Int(Double(n) / ratio)
        var out = [Int16](repeating: 0, count: outN)
        for i in 0..<outN {
            let pos = Double(i) * ratio
            let i0 = Int(pos)
            let i1 = min(i0 + 1, n - 1)
            let frac = Float(pos - Double(i0))
            let v = mono[i0] * (1 - frac) + mono[i1] * frac
            out[i] = v.isFinite ? Int16(max(-32768, min(32767, v * 32767))) : 0
        }
        return out
    }
}

// MARK: - Playback

final class AudioPlayback: NSObject, AVAudioPlayerDelegate {
    private let lock = NSLock()
    private var player: AVAudioPlayer?
    private var onDone: (() -> Void)?

    func play(_ audio: PcmAudio, onDone: @escaping () -> Void) throws {
        stop()
        try AudioSessionManager.shared.beginPlayback()
        let p: AVAudioPlayer
        do {
            p = try AVAudioPlayer(data: audio.toWavData(), fileTypeHint: AVFileType.wav.rawValue)
        } catch {
            AudioSessionManager.shared.endPlayback()
            throw VoiceError(.ENGINE_FAILED, "AVAudioPlayer could not open the audio: \(error.localizedDescription)")
        }
        p.delegate = self
        p.prepareToPlay()
        lock.lock(); player = p; self.onDone = onDone; lock.unlock()
        if !p.play() {
            lock.lock(); player = nil; self.onDone = nil; lock.unlock()
            AudioSessionManager.shared.endPlayback()
            onDone()
            throw VoiceError(.ENGINE_FAILED, "AVAudioPlayer refused to play")
        }
    }

    func stop() {
        lock.lock()
        let p = player
        let cb = onDone
        player = nil
        onDone = nil
        lock.unlock()
        guard let current = p else { return }
        current.stop()
        AudioSessionManager.shared.endPlayback()
        cb?()
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        lock.lock()
        var cb: (() -> Void)? = nil
        if self.player === player {
            cb = onDone
            self.player = nil
            onDone = nil
        }
        lock.unlock()
        if cb != nil { AudioSessionManager.shared.endPlayback() }
        cb?()
    }

    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        IVLog.warn("playback decode error: \(error?.localizedDescription ?? "?")")
        audioPlayerDidFinishPlaying(player, successfully: false)
    }
}

final class ResultBox<T> {
    var value: T
    init(_ v: T) { value = v }
}

// MARK: - Tier 1: Apple Speech (STT)

/// Tier 1 STT on iOS: SFSpeechRecognizer. Live mode is the primary path (Apple does partials; the SDK endpoints by
/// watching for `endpointSilenceMs` without a new partial, since Apple keeps a session open for up to a minute).
/// Batch mode (SFSpeechURLRecognitionRequest on a WAV file) serves transcribeFile/benchmark. Only the locales Apple
/// supports are available: of the SDK's 23 languages that is Hindi (hi-IN) and English (en-IN / en-US ...).
final class AppleSpeechEngine: SttEngine, LiveCapable {
    let id = "os:apple-speech"
    let tier = Tier.os
    let cfg: SttConfig
    let defaultLanguage: Language
    let liveConfig: LiveConfig
    let queue = DispatchQueue(label: "dev.indicvoice.apple-speech")
    let callbackQueue: OperationQueue = {
        let q = OperationQueue()
        q.maxConcurrentOperationCount = 1
        q.name = "dev.indicvoice.apple-speech.callbacks"
        return q
    }()

    init(cfg: SttConfig, defaultLanguage: Language) {
        self.cfg = cfg
        self.defaultLanguage = defaultLanguage
        self.liveConfig = cfg.live
    }

    // MARK: locales

    private static let supportedLocales: [String] = {
        return SFSpeechRecognizer.supportedLocales().map { AppleSpeechEngine.normalizeLocale($0.identifier) }.sorted()
    }()

    static func normalizeLocale(_ s: String) -> String {
        return s.replacingOccurrences(of: "_", with: "-").lowercased()
    }

    /// The Apple locale to use for a language, or nil when Apple Speech does not have the language at all.
    func localeIdentifier(for language: Language) -> String? {
        let want = AppleSpeechEngine.normalizeLocale(language.bcp47)
        if AppleSpeechEngine.supportedLocales.contains(want) { return language.bcp47 }
        // Any region of the same language (e.g. en-US when en-IN is missing).
        let prefix = language.code.lowercased() + "-"
        if let alt = AppleSpeechEngine.supportedLocales.first(where: { $0.hasPrefix(prefix) }) { return alt }
        return nil
    }

    // MARK: permissions

    static func authorizationName() -> String {
        switch SFSpeechRecognizer.authorizationStatus() {
        case .authorized: return "authorized"
        case .denied: return "denied"
        case .restricted: return "restricted"
        case .notDetermined: return "notDetermined"
        @unknown default: return "unknown"
        }
    }

    /// Prompts for speech-recognition permission when undetermined (must not run on the main thread).
    static func ensureSpeechAuthorized() -> Bool {
        switch SFSpeechRecognizer.authorizationStatus() {
        case .authorized:
            return true
        case .notDetermined:
            if Thread.isMainThread { return false }
            let sem = DispatchSemaphore(value: 0)
            let box = ResultBox<Bool>(false)
            SFSpeechRecognizer.requestAuthorization { status in
                box.value = (status == .authorized)
                sem.signal()
            }
            _ = sem.wait(timeout: .now() + .seconds(120))
            return box.value
        default:
            return false
        }
    }

    /// Prompts for microphone permission when undetermined (must not run on the main thread).
    static func ensureMicrophoneAuthorized() -> Bool {
        let session = AVAudioSession.sharedInstance()
        switch session.recordPermission {
        case .granted:
            return true
        case .undetermined:
            if Thread.isMainThread { return false }
            let sem = DispatchSemaphore(value: 0)
            let box = ResultBox<Bool>(false)
            session.requestRecordPermission { granted in
                box.value = granted
                sem.signal()
            }
            _ = sem.wait(timeout: .now() + .seconds(120))
            return box.value
        default:
            return false
        }
    }

    // MARK: availability

    func availability(_ language: Language?) -> Availability {
        let lang = language ?? defaultLanguage
        var details: [String: Any] = ["authorization": AppleSpeechEngine.authorizationName()]
        guard let locId = localeIdentifier(for: lang) else {
            return .no("Apple Speech has no \(lang.englishName) (it supports Hindi and English among the SDK's languages)", details)
        }
        details["locale"] = locId
        guard let rec = SFSpeechRecognizer(locale: Locale(identifier: locId)) else {
            return .no("Apple Speech could not create a recognizer for \(locId)", details)
        }
        details["onDeviceRecognizer"] = rec.supportsOnDeviceRecognition
        if !rec.isAvailable {
            return .no("Apple Speech is not available right now for \(locId) (offline and no on-device model?)", details)
        }
        if !cfg.tier1AllowOnline && !rec.supportsOnDeviceRecognition {
            return .no("\(lang.englishName) has no on-device Apple model and the online OS engine is disallowed in config", details)
        }
        switch SFSpeechRecognizer.authorizationStatus() {
        case .denied, .restricted:
            return .no("Speech recognition permission denied (Settings → Privacy → Speech Recognition)", details)
        default:
            break
        }
        if language == nil { details["note"] = "Apple Speech cannot auto-detect; using the default language \(lang.code)" }
        details["batchTranscription"] = true
        return Availability(available: true, reason: nil, details: details)
    }

    private func makeRecognizer(for language: Language?) throws -> (SFSpeechRecognizer, String) {
        let lang = language ?? defaultLanguage
        guard let locId = localeIdentifier(for: lang) else {
            throw VoiceError(.LANGUAGE_UNSUPPORTED, "Apple Speech has no \(lang.englishName)", tier: tier)
        }
        guard let rec = SFSpeechRecognizer(locale: Locale(identifier: locId)) else {
            throw VoiceError(.NO_ENGINE, "Apple Speech could not create a recognizer for \(locId)", tier: tier)
        }
        if !rec.isAvailable { throw VoiceError(.NO_ENGINE, "Apple Speech is not available right now for \(locId)", tier: tier) }
        rec.queue = callbackQueue
        return (rec, locId)
    }

    private func wantsOnDevice(_ rec: SFSpeechRecognizer) -> Bool {
        return rec.supportsOnDeviceRecognition && (cfg.tier1PreferOnDevice || !cfg.tier1AllowOnline)
    }

    /// Word list (with timings) and mean confidence from an Apple transcription. Partial results carry 0 confidence.
    static func wordsAndConfidence(_ t: SFTranscription) -> ([Word]?, Double?) {
        var words: [Word] = []
        var confSum = 0.0
        var confN = 0
        for seg in t.segments {
            let text = seg.substring.trimmingCharacters(in: .whitespacesAndNewlines)
            if text.isEmpty { continue }
            let c = Double(seg.confidence)
            words.append(Word(text: text, startMs: Int(seg.timestamp * 1000), endMs: Int((seg.timestamp + seg.duration) * 1000), confidence: c > 0 ? c : nil))
            if c > 0 { confSum += c; confN += 1 }
        }
        return (words.isEmpty ? nil : words, confN > 0 ? confSum / Double(confN) : nil)
    }

    static func mapError(_ e: Error) -> VoiceError {
        let ns = e as NSError
        let msg = ns.localizedDescription
        if ns.domain == "kAFAssistantErrorDomain" {
            switch ns.code {
            case 1110: return VoiceError(.BAD_AUDIO, "no speech recognised", tier: .os)
            case 1101: return VoiceError(.ENGINE_FAILED, "Apple on-device speech recognition is unavailable for this language (\(msg))", tier: .os)
            case 203: return VoiceError(.ENGINE_FAILED, "Apple Speech asked to retry (\(msg))", tier: .os)
            case 216: return VoiceError(.CANCELLED, "recognition cancelled", tier: .os)
            default: break
            }
        }
        if ns.domain == "kLSRErrorDomain" && ns.code == 301 { return VoiceError(.CANCELLED, "recognition cancelled", tier: .os) }
        let lower = msg.lowercased()
        if ns.domain == NSURLErrorDomain || lower.contains("network") || lower.contains("internet") || lower.contains("connection") {
            return VoiceError(.NETWORK, "Apple Speech needs network and failed: \(msg)", tier: .os)
        }
        return VoiceError(.ENGINE_FAILED, "Apple Speech error \(ns.domain)/\(ns.code): \(msg)", tier: .os)
    }

    // MARK: batch

    func transcribe(_ audio: PcmAudio, options: SttOptions) throws -> TranscriptResult {
        if Thread.isMainThread { throw VoiceError(.ENGINE_FAILED, "transcribe() must not be called on the main thread", tier: tier) }
        guard AppleSpeechEngine.ensureSpeechAuthorized() else {
            throw VoiceError(.PERMISSION_DENIED, "Speech recognition permission not granted", tier: tier)
        }
        let pcm = audio.resampled(16000)
        if pcm.samples.isEmpty { throw VoiceError(.BAD_AUDIO, "Empty audio", tier: tier) }
        let (recognizer, locId) = try makeRecognizer(for: options.language)
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("indicvoice-apple-\(UUID().uuidString).wav")
        try pcm.writeWav(to: tmp)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let request = SFSpeechURLRecognitionRequest(url: tmp)
        request.shouldReportPartialResults = false
        request.taskHint = .dictation
        if wantsOnDevice(recognizer) { request.requiresOnDeviceRecognition = true }
        if #available(iOS 16.0, *) { request.addsPunctuation = true }
        if !options.keyterms.isEmpty { request.contextualStrings = options.keyterms }

        let sem = DispatchSemaphore(value: 0)
        let finalBox = ResultBox<SFSpeechRecognitionResult?>(nil)
        let lastBox = ResultBox<SFSpeechRecognitionResult?>(nil)
        let errorBox = ResultBox<Error?>(nil)
        let resultLock = NSLock()
        let t0 = nowMs()
        let task = recognizer.recognitionTask(with: request) { result, error in
            resultLock.lock()
            if let r = result {
                lastBox.value = r
                if r.isFinal { finalBox.value = r }
            }
            if let e = error { errorBox.value = e }
            let finished = finalBox.value != nil || errorBox.value != nil
            resultLock.unlock()
            if finished { sem.signal() }
        }
        let timeout = max(15.0, Double(pcm.durationMs) * 3 / 1000)
        if sem.wait(timeout: .now() + timeout) == .timedOut {
            task.cancel()
            throw VoiceError(.TIMEOUT, "Apple Speech did not answer within \(Int(timeout)) s", tier: tier)
        }
        resultLock.lock()
        let finalResult = finalBox.value
        let lastResult = lastBox.value
        let failure = errorBox.value
        resultLock.unlock()
        let latency = nowMs() - t0
        let result = finalResult ?? lastResult
        if result == nil, let e = failure { throw AppleSpeechEngine.mapError(e) }
        guard let r = result else { throw VoiceError(.BAD_AUDIO, "no speech recognised", tier: tier) }
        let text = r.bestTranscription.formattedString
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { throw VoiceError(.BAD_AUDIO, "no speech recognised", tier: tier) }
        let (words, confidence) = AppleSpeechEngine.wordsAndConfidence(r.bestTranscription)
        return TranscriptResult.fromText(text, tier: tier, engine: id, latencyMs: latency, audioMs: pcm.durationMs, engineLang: locId,
                                         hint: options.language, allowed: options.allowedLanguages, words: words, confidence: confidence,
                                         raw: ["locale": locId, "onDevice": wantsOnDevice(recognizer)])
    }

    // MARK: live

    func startLive(options: SttOptions, listener: LiveSttListener) throws -> LiveSttSession {
        guard AppleSpeechEngine.ensureMicrophoneAuthorized() else {
            throw VoiceError(.PERMISSION_DENIED, "Microphone permission not granted", tier: tier)
        }
        guard AppleSpeechEngine.ensureSpeechAuthorized() else {
            throw VoiceError(.PERMISSION_DENIED, "Speech recognition permission not granted", tier: tier)
        }
        let (recognizer, locId) = try makeRecognizer(for: options.language)
        let session = AppleLiveSession(engine: self, recognizer: recognizer, localeId: locId, onDevice: wantsOnDevice(recognizer),
                                       options: options, listener: listener)
        try session.begin()
        return session
    }

    func close() {}
}

/// One live Apple Speech session: microphone tap -> SFSpeechAudioBufferRecognitionRequest, partials -> onPartial,
/// endpointing by silence (no new partial for endpointSilenceMs) or max utterance length, final -> onFinal.
final class AppleLiveSession: LiveSttSession {
    let tier = Tier.os
    private let engine: AppleSpeechEngine
    private let options: SttOptions
    private let listener: LiveSttListener
    private let recognizer: SFSpeechRecognizer
    private let localeId: String
    private let onDevice: Bool
    private let request = SFSpeechAudioBufferRecognitionRequest()
    private let mic = MicrophoneSource()
    private let queue: DispatchQueue
    private let audioClosed = AtomicFlag()

    private enum State { case listening, ending, finished }
    private var state = State.listening
    private var task: SFSpeechRecognitionTask?
    private var timer: DispatchSourceTimer?
    private var finalDeadline: DispatchWorkItem?
    private let startedAt = Date()
    private var speechStartedAt: Date?
    private var lastChangeAt = Date()
    private var lastText = ""
    private var lastResult: SFSpeechRecognitionResult?
    private var endAudioAt: Date?
    private var userStopped = false

    init(engine: AppleSpeechEngine, recognizer: SFSpeechRecognizer, localeId: String, onDevice: Bool, options: SttOptions, listener: LiveSttListener) {
        self.engine = engine
        self.recognizer = recognizer
        self.localeId = localeId
        self.onDevice = onDevice
        self.options = options
        self.listener = listener
        self.queue = engine.queue
    }

    func begin() throws {
        request.shouldReportPartialResults = true
        request.taskHint = .dictation
        if onDevice { request.requiresOnDeviceRecognition = true }
        if #available(iOS 16.0, *) { request.addsPunctuation = true }
        if !options.keyterms.isEmpty { request.contextualStrings = options.keyterms }

        try mic.start(onBuffer: { [weak self] buffer in
            guard let self = self, !self.audioClosed.isSet else { return }
            self.request.append(buffer)
        }, onFrame: { [weak self] frame in
            guard let self = self else { return }
            self.listener.onLevel(PcmAudio(samples: frame, sampleRate: 16000).rmsDb())
        })

        queue.sync {
            self.task = self.recognizer.recognitionTask(with: self.request) { [weak self] result, error in
                guard let self = self else { return }
                self.queue.async { self.handle(result: result, error: error) }
            }
            let t = DispatchSource.makeTimerSource(queue: self.queue)
            t.schedule(deadline: .now() + .milliseconds(100), repeating: .milliseconds(100))
            t.setEventHandler { [weak self] in self?.tick() }
            t.resume()
            self.timer = t
        }
        listener.onTierChanged(.os, "Apple Speech (\(localeId), \(onDevice ? "on-device" : "server"))")
        listener.onReady()
    }

    func stop() {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.userStopped = true
            self.endUtterance(reason: "stop")
        }
    }

    func cancel() {
        queue.async { [weak self] in self?.finish() }
    }

    // All of the following run on `queue`.

    private func tick() {
        guard state == .listening else { return }
        let now = Date()
        if let s = speechStartedAt {
            let sinceChange = now.timeIntervalSince(lastChangeAt) * 1000
            let sinceStart = now.timeIntervalSince(s) * 1000
            if sinceChange >= Double(engine.liveConfig.endpointSilenceMs) {
                endUtterance(reason: "silence")
            } else if sinceStart >= Double(engine.liveConfig.maxUtteranceMs) {
                endUtterance(reason: "max utterance length")
            }
        } else if now.timeIntervalSince(startedAt) >= 45 {
            // Apple ends its own sessions after about a minute; give up earlier with a clear error.
            listener.onError(VoiceError(.BAD_AUDIO, "no speech recognised", tier: .os))
            finish()
        }
    }

    private func endUtterance(reason: String) {
        guard state == .listening else { return }
        state = .ending
        _ = audioClosed.trySet()
        IVLog.debug("apple live: ending utterance (\(reason))")
        if speechStartedAt != nil { listener.onSpeechEnd() }
        mic.stop()
        endAudioAt = Date()
        request.endAudio()
        let work = DispatchWorkItem { [weak self] in self?.finalTimeout() }
        finalDeadline = work
        queue.asyncAfter(deadline: .now() + .seconds(3), execute: work)
    }

    private func finalTimeout() {
        guard state == .ending else { return }
        if !lastText.isEmpty {
            deliverFinal(from: lastResult, text: lastText, synthesized: true)
        } else if !userStopped {
            listener.onError(VoiceError(.BAD_AUDIO, "no speech recognised", tier: .os))
        }
        finish()
    }

    private func handle(result: SFSpeechRecognitionResult?, error: Error?) {
        if state == .finished { return }
        if let r = result {
            let text = r.bestTranscription.formattedString
            if !text.isEmpty && speechStartedAt == nil {
                speechStartedAt = Date()
                listener.onSpeechStart()
            }
            lastResult = r
            if r.isFinal {
                if text.isEmpty {
                    if !userStopped { listener.onError(VoiceError(.BAD_AUDIO, "no speech recognised", tier: .os)) }
                } else {
                    deliverFinal(from: r, text: text, synthesized: false)
                }
                finish()
                return
            }
            if text != lastText {
                lastText = text
                lastChangeAt = Date()
                listener.onPartial(text, nil)
            }
            return
        }
        if let e = error {
            let mapped = AppleSpeechEngine.mapError(e)
            if state == .ending {
                // endAudio() was called; Apple sometimes reports an error instead of a final result.
                if !lastText.isEmpty {
                    deliverFinal(from: lastResult, text: lastText, synthesized: true)
                } else if !userStopped && mapped.code != .CANCELLED {
                    listener.onError(mapped.code == .BAD_AUDIO ? mapped : VoiceError(.BAD_AUDIO, "no speech recognised", tier: .os))
                }
                finish()
                return
            }
            if mapped.code != .CANCELLED { listener.onError(mapped) }
            finish()
        }
    }

    private func deliverFinal(from result: SFSpeechRecognitionResult?, text: String, synthesized: Bool) {
        let now = Date()
        let end = endAudioAt ?? now
        var audioMs = 0
        if let s = speechStartedAt { audioMs = Int(end.timeIntervalSince(s) * 1000) }
        let latencyMs = Int(now.timeIntervalSince(endAudioAt ?? speechStartedAt ?? startedAt) * 1000)
        var words: [Word]? = nil
        var confidence: Double? = nil
        if let r = result {
            let (w, c) = AppleSpeechEngine.wordsAndConfidence(r.bestTranscription)
            words = w
            confidence = c
        }
        let tr = TranscriptResult.fromText(text, tier: .os, engine: engine.id, latencyMs: max(0, latencyMs), audioMs: max(0, audioMs),
                                           engineLang: localeId, hint: options.language, allowed: options.allowedLanguages, words: words,
                                           confidence: confidence, raw: ["locale": localeId, "onDevice": onDevice, "synthesizedFinal": synthesized])
        listener.onFinal(tr)
    }

    private func finish() {
        if state == .finished { return }
        state = .finished
        _ = audioClosed.trySet()
        timer?.cancel()
        timer = nil
        finalDeadline?.cancel()
        finalDeadline = nil
        mic.stop()
        if let t = task, t.state != .completed { t.cancel() }
        task = nil
        listener.onStopped()
    }
}

// MARK: - Tier 1: Apple TTS

/// Tier 1 TTS: AVSpeechSynthesizer rendered to PCM with `write(_:toBufferCallback:)` so playback, benchmarking and
/// WAV export work the same way as for the other tiers.
final class AppleTtsEngine: TtsEngine {
    let id = "os:apple-tts"
    let tier = Tier.os
    private let cfg: TtsConfig
    private let lock = NSLock()

    init(cfg: TtsConfig) { self.cfg = cfg }

    private func qualityRank(_ v: AVSpeechSynthesisVoice) -> Int {
        if #available(iOS 16.0, *) {
            if v.quality == .premium { return 3 }
        }
        return v.quality == .enhanced ? 2 : 1
    }

    private func qualityLabel(_ v: AVSpeechSynthesisVoice) -> String {
        switch qualityRank(v) {
        case 3: return "premium"
        case 2: return "enhanced"
        default: return "default"
        }
    }

    private func genderLabel(_ v: AVSpeechSynthesisVoice) -> String? {
        switch v.gender {
        case .male: return "male"
        case .female: return "female"
        default: return nil
        }
    }

    /// Voices for a language, exact locale (hi-IN, en-IN) first, then by quality.
    private func voicesFor(_ language: Language) -> [AVSpeechSynthesisVoice] {
        let code = language.code.lowercased()
        let exact = language.bcp47.lowercased()
        let matching = AVSpeechSynthesisVoice.speechVoices().filter { v in
            let l = v.language.lowercased()
            return l == code || l.hasPrefix(code + "-")
        }
        return matching.sorted { a, b in
            let ae = a.language.lowercased() == exact
            let be = b.language.lowercased() == exact
            if ae != be { return ae }
            return qualityRank(a) > qualityRank(b)
        }
    }

    func availability(_ language: Language) -> Availability {
        let voices = voicesFor(language)
        if voices.isEmpty {
            return .no("Apple TTS has no \(language.englishName) voice (add one in Settings → Accessibility → Spoken Content → Voices)")
        }
        return Availability(available: true, reason: nil, details: ["voices": voices.map { $0.name }, "offlineVoices": voices.count])
    }

    func voices(_ language: Language?) -> [VoiceInfo] {
        let langs = language.map { [$0] } ?? Language.all
        var out: [VoiceInfo] = []
        for l in langs {
            for v in voicesFor(l) {
                out.append(VoiceInfo(id: "os:" + v.identifier, language: l, tier: tier, name: v.name, gender: genderLabel(v),
                                     offline: true, quality: qualityLabel(v), installed: true))
            }
        }
        return out
    }

    func synthesize(_ options: TtsOptions) throws -> TtsResult {
        let text = options.text.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { throw VoiceError(.BAD_AUDIO, "Empty text", tier: tier) }
        let candidates = voicesFor(options.language)
        var wanted = options.voiceId ?? ""
        if wanted.hasPrefix("os:") { wanted = String(wanted.dropFirst(3)) }
        var voice: AVSpeechSynthesisVoice? = nil
        if !wanted.isEmpty {
            voice = AVSpeechSynthesisVoice.speechVoices().first { $0.identifier == wanted || $0.name.lowercased() == wanted.lowercased() }
        }
        if voice == nil { voice = candidates.first }
        guard let chosen = voice else {
            throw VoiceError(.LANGUAGE_UNSUPPORTED, "Apple TTS has no \(options.language.englishName) voice", tier: tier)
        }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = chosen
        let speed = Float(max(0.1, min(4.0, options.speed)))
        let wantedRate = AVSpeechUtteranceDefaultSpeechRate * speed
        utterance.rate = max(AVSpeechUtteranceMinimumSpeechRate, min(AVSpeechUtteranceMaximumSpeechRate, wantedRate))

        lock.lock()
        defer { lock.unlock() }
        let t0 = nowMs()
        let capture = AppleTtsCapture()
        let (samples, sampleRate) = try capture.run(utterance, timeoutSeconds: 30)
        let latency = nowMs() - t0
        if samples.isEmpty || sampleRate <= 0 { throw VoiceError(.ENGINE_FAILED, "Apple TTS produced no audio", tier: tier) }
        var pcm = PcmAudio.fromFloat(samples, sampleRate: Int(sampleRate))
        let target = options.sampleRate ?? cfg.sampleRate
        if target > 0 && target != pcm.sampleRate { pcm = pcm.resampled(target) }
        return TtsResult(audio: pcm, language: options.language, voiceId: "os:" + chosen.identifier, tier: tier, engine: id, latencyMs: latency)
    }

    func close() {}
}

/// Collects the PCM buffers of one AVSpeechSynthesizer.write() call. Completion is signalled by an empty buffer
/// after data (iOS convention), by the delegate, or by 1.5 s without new buffers (defensive: some iOS versions
/// deliver neither reliably).
final class AppleTtsCapture: NSObject, AVSpeechSynthesizerDelegate {
    private let synthesizer = AVSpeechSynthesizer()
    private let lock = NSLock()
    private let sem = DispatchSemaphore(value: 0)
    private var samples: [Float] = []
    private var sampleRate: Double = 0
    private var gotData = false
    private var done = false
    private var lastDataAt = Date()

    func run(_ utterance: AVSpeechUtterance, timeoutSeconds: Double) throws -> ([Float], Double) {
        synthesizer.delegate = self
        synthesizer.usesApplicationAudioSession = true
        DispatchQueue.main.async {
            self.synthesizer.write(utterance) { [weak self] buffer in
                self?.handle(buffer)
            }
        }
        let start = Date()
        while true {
            _ = sem.wait(timeout: .now() + .milliseconds(250))
            lock.lock()
            let finished = done
            let hasData = gotData
            let idle = Date().timeIntervalSince(lastDataAt)
            lock.unlock()
            if finished { break }
            if hasData && idle > 1.5 { break }
            if Date().timeIntervalSince(start) > timeoutSeconds {
                _ = synthesizer.stopSpeaking(at: .immediate)
                if !hasData { throw VoiceError(.TIMEOUT, "Apple TTS timed out", tier: .os) }
                break
            }
        }
        lock.lock()
        defer { lock.unlock() }
        return (samples, sampleRate)
    }

    private func handle(_ buffer: AVAudioBuffer) {
        guard let pcm = buffer as? AVAudioPCMBuffer else { return }
        let n = Int(pcm.frameLength)
        lock.lock()
        defer { lock.unlock() }
        if n == 0 {
            if gotData { done = true; sem.signal() }
            return
        }
        if sampleRate == 0 { sampleRate = pcm.format.sampleRate }
        gotData = true
        lastDataAt = Date()
        let channels = max(1, Int(pcm.format.channelCount))
        let stride = pcm.format.isInterleaved ? channels : 1
        samples.reserveCapacity(samples.count + n)
        if let f = pcm.floatChannelData {
            let p = f[0]
            for i in 0..<n { samples.append(p[i * stride]) }
        } else if let s = pcm.int16ChannelData {
            let p = s[0]
            for i in 0..<n { samples.append(Float(p[i * stride]) / 32768) }
        } else if let s = pcm.int32ChannelData {
            let p = s[0]
            for i in 0..<n { samples.append(Float(Double(p[i * stride]) / 2147483648.0)) }
        }
        sem.signal()
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        lock.lock(); done = true; lock.unlock()
        sem.signal()
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        lock.lock(); done = true; lock.unlock()
        sem.signal()
    }
}

// MARK: - Tier 2: sherpa-onnx

enum SherpaRuntime {
    static let version: String = {
        if let p = SherpaOnnxGetVersionStr() { return String(cString: p) }
        return "1.13.8"
    }()

    static func availability() -> Availability {
        return Availability(available: true, reason: nil, details: ["runtime": "sherpa-onnx \(version)"])
    }
}

private func requirePath(_ spec: PackSpec, _ dir: URL, _ key: String) throws -> String {
    guard let v = spec.model.str(key), !v.isEmpty else { throw VoiceError(.CONFIG_ERROR, "pack '\(spec.id)' has no model.\(key)") }
    let p = dir.appendingPathComponent(v).path
    if !FileManager.default.fileExists(atPath: p) { throw VoiceError(.PACK_CORRUPT, "pack '\(spec.id)': missing \(v)") }
    return p
}

private func optionalPath(_ spec: PackSpec, _ dir: URL, _ key: String) -> String {
    guard let v = spec.model.str(key) else { return "" }
    return v.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        .map { dir.appendingPathComponent($0).path }.joined(separator: ",")
}

/// Non-streaming recognition over any sherpa-onnx offline model type the pack declares.
final class SherpaSttEngine: SttEngine {
    let id: String
    let tier = Tier.onDevice
    private let spec: PackSpec
    private let numThreads: Int
    private let isWhisper: Bool
    private var whisperLanguage = ""
    private let lock = NSLock()
    private let recognizer: SherpaOnnxOfflineRecognizer

    private struct Paths {
        var model = ""
        var encoder = ""
        var decoder = ""
        var joiner = ""
        var tokens = ""
    }
    private let paths: Paths

    init(spec: PackSpec, dir: URL, numThreads: Int) throws {
        self.spec = spec
        self.id = "sherpa:\(spec.id)"
        self.numThreads = max(1, numThreads)
        self.isWhisper = spec.engine == "whisper"
        var p = Paths()
        p.tokens = try requirePath(spec, dir, "tokens")
        switch spec.engine {
        case "dolphin-ctc", "omnilingual-ctc", "nemo-ctc", "zipformer-ctc", "sense-voice":
            p.model = try requirePath(spec, dir, "model")
        case "transducer":
            p.encoder = try requirePath(spec, dir, "encoder")
            p.decoder = try requirePath(spec, dir, "decoder")
            p.joiner = try requirePath(spec, dir, "joiner")
        case "whisper":
            p.encoder = try requirePath(spec, dir, "encoder")
            p.decoder = try requirePath(spec, dir, "decoder")
        default:
            throw VoiceError(.CONFIG_ERROR, "Unknown STT engine '\(spec.engine)' in pack '\(spec.id)'")
        }
        self.paths = p
        let engineName = spec.engine
        let threads = self.numThreads
        self.recognizer = autoreleasepool { () -> SherpaOnnxOfflineRecognizer in
            var config = SherpaSttEngine.buildConfig(engine: engineName, paths: p, numThreads: threads, whisperLanguage: "")
            return withUnsafePointer(to: &config) { SherpaOnnxOfflineRecognizer(config: $0) }
        }
    }

    private static func buildConfig(engine: String, paths: Paths, numThreads: Int, whisperLanguage: String) -> SherpaOnnxOfflineRecognizerConfig {
        let modelConfig: SherpaOnnxOfflineModelConfig
        switch engine {
        case "dolphin-ctc":
            modelConfig = sherpaOnnxOfflineModelConfig(tokens: paths.tokens, numThreads: numThreads, provider: "cpu",
                                                       dolphin: sherpaOnnxOfflineDolphinModelConfig(model: paths.model))
        case "omnilingual-ctc":
            modelConfig = sherpaOnnxOfflineModelConfig(tokens: paths.tokens, numThreads: numThreads, provider: "cpu",
                                                       omnilingual: sherpaOnnxOfflineOmnilingualAsrCtcModelConfig(model: paths.model))
        case "nemo-ctc":
            modelConfig = sherpaOnnxOfflineModelConfig(tokens: paths.tokens, nemoCtc: sherpaOnnxOfflineNemoEncDecCtcModelConfig(model: paths.model),
                                                       numThreads: numThreads, provider: "cpu", modelType: "nemo_ctc")
        case "zipformer-ctc":
            modelConfig = sherpaOnnxOfflineModelConfig(tokens: paths.tokens, numThreads: numThreads, provider: "cpu",
                                                       zipformerCtc: sherpaOnnxOfflineZipformerCtcModelConfig(model: paths.model))
        case "sense-voice":
            modelConfig = sherpaOnnxOfflineModelConfig(tokens: paths.tokens, numThreads: numThreads, provider: "cpu",
                                                       senseVoice: sherpaOnnxOfflineSenseVoiceModelConfig(model: paths.model, language: "auto"))
        case "transducer":
            modelConfig = sherpaOnnxOfflineModelConfig(tokens: paths.tokens,
                                                       transducer: sherpaOnnxOfflineTransducerModelConfig(encoder: paths.encoder, decoder: paths.decoder, joiner: paths.joiner),
                                                       numThreads: numThreads, provider: "cpu", modelType: "transducer")
        default: // whisper
            modelConfig = sherpaOnnxOfflineModelConfig(tokens: paths.tokens,
                                                       whisper: sherpaOnnxOfflineWhisperModelConfig(encoder: paths.encoder, decoder: paths.decoder, language: whisperLanguage, task: "transcribe"),
                                                       numThreads: numThreads, provider: "cpu", modelType: "whisper")
        }
        return sherpaOnnxOfflineRecognizerConfig(featConfig: sherpaOnnxFeatureConfig(sampleRate: 16000, featureDim: 80),
                                                 modelConfig: modelConfig, decodingMethod: "greedy_search")
    }

    func availability(_ language: Language?) -> Availability {
        if spec.supports(language) { return Availability(available: true, reason: nil, details: ["packId": spec.id]) }
        return .no("pack '\(spec.id)' does not cover \(language?.code ?? "auto")")
    }

    func transcribe(_ audio: PcmAudio, options: SttOptions) throws -> TranscriptResult {
        let pcm = audio.resampled(16000)
        if pcm.samples.isEmpty { throw VoiceError(.BAD_AUDIO, "Empty audio", tier: tier) }
        let t0 = nowMs()
        var text = ""
        var lang = ""
        var tokens: [String] = []
        var timestamps: [Float] = []
        lock.lock()
        defer { lock.unlock() }
        autoreleasepool {
            if isWhisper {
                // Whisper can be forced to a language or left to detect it; the config can be swapped in place.
                var want = ""
                if let l = options.language, spec.languages.contains(l) { want = l.code }
                if want != whisperLanguage {
                    var config = SherpaSttEngine.buildConfig(engine: spec.engine, paths: paths, numThreads: numThreads, whisperLanguage: want)
                    withUnsafePointer(to: &config) { recognizer.setConfig(config: $0) }
                    whisperLanguage = want
                }
            }
            let result = recognizer.decode(samples: pcm.toFloat(), sampleRate: 16000)
            text = result.text
            lang = result.lang
            timestamps = result.timestamps
            tokens = SherpaSttEngine.readTokens(result)
        }
        let latency = nowMs() - t0
        let words = SherpaSttEngine.wordsFromTokens(tokens, timestamps, text)
        return TranscriptResult.fromText(text, tier: tier, engine: id, latencyMs: latency, audioMs: pcm.durationMs,
                                         engineLang: lang.isEmpty ? nil : lang, hint: options.language, allowed: options.allowedLanguages,
                                         words: words, confidence: nil, raw: ["tokens": tokens.count, "modelLang": lang])
    }

    private static func readTokens(_ r: SherpaOnnxOfflineRecognitionResult) -> [String] {
        let count = r.count
        guard count > 0, let arr = r.result.pointee.tokens_arr else { return [] }
        var out: [String] = []
        out.reserveCapacity(count)
        for i in 0..<count {
            if let p = arr[i] { out.append(String(cString: p)) } else { out.append("") }
        }
        return out
    }

    /// Groups sub-word tokens (sentencepiece "▁" / leading space) into words with timestamps when available.
    private static func wordsFromTokens(_ tokens: [String], _ timestamps: [Float], _ text: String) -> [Word]? {
        if tokens.isEmpty || timestamps.count != tokens.count { return nil }
        var words: [Word] = []
        var cur = ""
        var start: Float = 0
        var last: Float = 0
        func flush(_ end: Float) {
            let w = cur.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "।॥,.!?;:"))
            if !w.isEmpty { words.append(Word(text: w, startMs: Int(start * 1000), endMs: Int(end * 1000), confidence: nil)) }
            cur = ""
        }
        for i in 0..<tokens.count {
            let tok = tokens[i]
            let startsWord = tok.hasPrefix("▁") || tok.hasPrefix(" ")
            var clean = tok
            if clean.hasPrefix("▁") { clean = String(clean.dropFirst()) }
            clean = String(clean.drop(while: { $0 == " " }))
            if startsWord && !cur.isEmpty { flush(timestamps[i]) }
            if cur.isEmpty { start = timestamps[i] }
            cur += clean
            last = timestamps[i]
        }
        flush(last + 0.2)
        // Only trust this segmentation when it agrees with the whitespace split; otherwise fall back.
        let plain = TranscriptResult.splitWords(text)
        return words.count == plain.count ? words : nil
    }

    func close() {}
}

/// Offline TTS over VITS (Piper / Indic-TTS exports), Kokoro or Matcha packs.
final class SherpaTtsEngine: TtsEngine {
    let id: String
    let tier = Tier.onDevice
    private let spec: PackSpec
    private let tts: SherpaOnnxOfflineTtsWrapper
    private let sampleRate: Int
    private let voices: [String: [String: Int]] // language -> (voice name -> sid)
    private let lock = NSLock()

    init(spec: PackSpec, dir: URL, numThreads: Int) throws {
        self.spec = spec
        self.id = "sherpa:\(spec.id)"
        let threads = max(1, numThreads)
        let engine = spec.engine
        let tokens = try requirePath(spec, dir, "tokens")
        let dataDir = optionalPath(spec, dir, "dataDir")
        let lexicon = optionalPath(spec, dir, "lexicon")
        let dictDir = optionalPath(spec, dir, "dictDir")
        let ruleFsts = optionalPath(spec, dir, "ruleFsts")
        var model = ""
        var voicesFile = ""
        var acoustic = ""
        var vocoder = ""
        switch engine {
        case "vits":
            model = try requirePath(spec, dir, "model")
        case "kokoro":
            model = try requirePath(spec, dir, "model")
            voicesFile = try requirePath(spec, dir, "voices")
        case "matcha":
            acoustic = try requirePath(spec, dir, "acousticModel")
            vocoder = try requirePath(spec, dir, "vocoder")
        default:
            throw VoiceError(.CONFIG_ERROR, "Unknown TTS engine '\(spec.engine)' in pack '\(spec.id)'")
        }
        let wrapper: SherpaOnnxOfflineTtsWrapper = autoreleasepool { () -> SherpaOnnxOfflineTtsWrapper in
            let modelConfig: SherpaOnnxOfflineTtsModelConfig
            switch engine {
            case "vits":
                modelConfig = sherpaOnnxOfflineTtsModelConfig(
                    vits: sherpaOnnxOfflineTtsVitsModelConfig(model: model, lexicon: lexicon, tokens: tokens, dataDir: dataDir, dictDir: dictDir),
                    numThreads: threads, provider: "cpu")
            case "kokoro":
                modelConfig = sherpaOnnxOfflineTtsModelConfig(
                    kokoro: sherpaOnnxOfflineTtsKokoroModelConfig(model: model, voices: voicesFile, tokens: tokens, dataDir: dataDir, dictDir: dictDir, lexicon: lexicon),
                    numThreads: threads, provider: "cpu")
            default:
                modelConfig = sherpaOnnxOfflineTtsModelConfig(
                    matcha: sherpaOnnxOfflineTtsMatchaModelConfig(acousticModel: acoustic, vocoder: vocoder, lexicon: lexicon, tokens: tokens, dataDir: dataDir, dictDir: dictDir),
                    numThreads: threads, provider: "cpu")
            }
            var config = sherpaOnnxOfflineTtsConfig(model: modelConfig, ruleFsts: ruleFsts, maxNumSentences: 1)
            return withUnsafePointer(to: &config) { SherpaOnnxOfflineTtsWrapper(config: $0) }
        }
        if wrapper.tts == nil { throw VoiceError(.PACK_CORRUPT, "sherpa-onnx could not load TTS pack '\(spec.id)'") }
        self.tts = wrapper
        self.sampleRate = Int(wrapper.sampleRate)
        var v: [String: [String: Int]] = [:]
        if let raw = spec.raw["voices"] as? [String: Any] {
            for (langCode, entry) in raw {
                var named: [String: Int] = [:]
                if let m = entry as? [String: Any] {
                    for (name, sid) in m { named[name] = (sid as? NSNumber)?.intValue ?? 0 }
                }
                v[langCode] = named
            }
        }
        self.voices = v
    }

    func availability(_ language: Language) -> Availability {
        if spec.supports(language) { return Availability(available: true, reason: nil, details: ["packId": spec.id, "sampleRate": sampleRate]) }
        return .no("pack '\(spec.id)' has no \(language.code) voice")
    }

    func voices(_ language: Language?) -> [VoiceInfo] {
        let langs = language.map { [$0] } ?? spec.languages
        var out: [VoiceInfo] = []
        for l in langs where spec.supports(l) {
            let named = voices[l.code] ?? [:]
            if named.isEmpty {
                out.append(VoiceInfo(id: "pack:\(spec.id)", language: l, tier: tier, name: spec.name, gender: spec.raw.str("gender"), offline: true, quality: spec.raw.str("quality"), installed: true))
            } else {
                for n in named.keys.sorted() {
                    var gender: String? = nil
                    let chars = Array(n)
                    if chars.count > 1 {
                        if chars[1] == "f" { gender = "female" } else if chars[1] == "m" { gender = "male" }
                    }
                    out.append(VoiceInfo(id: "pack:\(spec.id)#\(n)", language: l, tier: tier, name: "\(spec.name) / \(n)", gender: gender, offline: true, quality: spec.raw.str("quality"), installed: true))
                }
            }
        }
        return out
    }

    func synthesize(_ options: TtsOptions) throws -> TtsResult {
        if !spec.supports(options.language) { throw VoiceError(.LANGUAGE_UNSUPPORTED, "pack '\(spec.id)' has no \(options.language.code) voice", tier: tier) }
        let text = options.text.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { throw VoiceError(.BAD_AUDIO, "Empty text", tier: tier) }
        // Resolve the speaker id: "pack:<id>#<voice>" or a bare voice name, else the first voice for the language.
        var voiceName: String? = nil
        if let vid = options.voiceId {
            let afterHash: String
            if let hash = vid.firstIndex(of: "#") { afterHash = String(vid[vid.index(after: hash)...]) } else { afterHash = vid }
            if !afterHash.isEmpty && !afterHash.hasPrefix("pack:") { voiceName = afterHash }
        }
        let langVoices = voices[options.language.code] ?? [:]
        var sid = 0
        if let name = voiceName {
            if let s = langVoices[name] {
                sid = s
            } else if let s = voices.values.compactMap({ $0[name] }).first {
                sid = s
            } else if let s = langVoices.values.sorted().first {
                sid = s
            }
        } else if let s = langVoices.values.sorted().first {
            sid = s
        }
        var extra: [String: Any] = [:]
        if spec.engine == "kokoro" && options.language != Language.english {
            extra["lang"] = SherpaTtsEngine.espeakVoice(options.language)
        }
        let gen = SherpaOnnxGenerationConfigSwift(speed: Float(max(0.1, min(4.0, options.speed))), sid: sid, extra: extra)
        let t0 = nowMs()
        lock.lock()
        defer { lock.unlock() }
        var samples: [Float] = []
        var rate = sampleRate
        autoreleasepool {
            let audio = tts.generateWithConfig(text: text, config: gen, callback: nil, arg: nil)
            if audio.audio != nil {
                samples = audio.samples
                rate = Int(audio.sampleRate)
            }
        }
        let latency = nowMs() - t0
        if samples.isEmpty { throw VoiceError(.ENGINE_FAILED, "TTS produced no audio (text may contain unsupported characters)", tier: tier) }
        var pcm = PcmAudio.fromFloat(samples, sampleRate: rate)
        if let target = options.sampleRate, target != pcm.sampleRate { pcm = pcm.resampled(target) }
        var voiceId = "pack:\(spec.id)"
        if let name = voiceName {
            voiceId += "#\(name)"
        } else if let entry = langVoices.first(where: { $0.value == sid }) {
            voiceId += "#\(entry.key)"
        }
        return TtsResult(audio: pcm, language: options.language, voiceId: voiceId, tier: tier, engine: id, latencyMs: latency)
    }

    /// espeak-ng voice used by Kokoro's multilingual frontend for languages that have no lexicon.
    static func espeakVoice(_ l: Language) -> String {
        switch l.code {
        case "hi": return "hi"
        case "bn": return "bn"
        case "ta": return "ta"
        case "te": return "te"
        case "mr": return "mr"
        case "gu": return "gu"
        case "kn": return "kn"
        case "ml": return "ml"
        case "pa": return "pa"
        case "ur": return "ur"
        case "ne": return "ne"
        case "as": return "as"
        case "or": return "or"
        case "sd": return "sd"
        case "kok": return "kok"
        case "ks": return "ur"
        case "mni": return "bn"
        case "en": return "en-us"
        default: return "hi" // sa, mai, doi, brx, sat: Devanagari, closest espeak voice
        }
    }

    func close() {}
}

/// Silero VAD through sherpa-onnx (window = 512 samples at 16 kHz). The C API exposes "currently inside speech"
/// rather than a per-frame probability, so the detector is fed one window at a time and its state is read back.
final class SherpaVad: Vad {
    let name = "silero"
    let frameSize = 512
    private let vad: SherpaOnnxVoiceActivityDetectorWrapper

    init(modelPath: String, threshold: Float) throws {
        if !FileManager.default.fileExists(atPath: modelPath) { throw VoiceError(.PACK_MISSING, "VAD model not found: \(modelPath)") }
        vad = autoreleasepool { () -> SherpaOnnxVoiceActivityDetectorWrapper in
            var config = sherpaOnnxVadModelConfig(
                sileroVad: sherpaOnnxSileroVadModelConfig(model: modelPath, threshold: threshold, minSilenceDuration: 0.1,
                                                          minSpeechDuration: 0.1, windowSize: 512, maxSpeechDuration: 20),
                sampleRate: 16000, numThreads: 1)
            return withUnsafePointer(to: &config) { SherpaOnnxVoiceActivityDetectorWrapper(config: $0, buffer_size_in_seconds: 30) }
        }
    }

    func isSpeech(_ frame: [Int16]) -> Bool {
        var f = [Float](repeating: 0, count: frame.count)
        for i in 0..<frame.count { f[i] = Float(frame[i]) / 32768 }
        vad.acceptWaveform(samples: f)
        let speech = vad.isSpeechDetected()
        // Drain finished segments so the detector's queue does not grow (the segmenter keeps its own audio).
        while !vad.isEmpty() { vad.pop() }
        return speech
    }

    func reset() { vad.reset() }
}

// MARK: - Live session for tiers 2/3 (VAD segmented)

final class LiveHandle: LiveSttSession {
    private let lock = NSLock()
    private var _current: LiveSttSession?

    var current: LiveSttSession? {
        get { lock.lock(); defer { lock.unlock() }; return _current }
        set { lock.lock(); _current = newValue; lock.unlock() }
    }

    var tier: Tier { return current?.tier ?? .os }
    func stop() { current?.stop() }
    func cancel() { current?.cancel() }
}

/// Microphone -> segmenter -> engine.transcribe per utterance, on a serial worker queue.
final class SegmentedLiveSession: LiveSttSession {
    let tier: Tier
    private let mic = MicrophoneSource()
    private let segmenter: Segmenter
    private let listener: LiveSttListener
    private let worker: DispatchQueue
    private let stopped = AtomicFlag()

    init(tier: Tier, engine: SttEngine, vad: Vad, live: LiveConfig, options: SttOptions, listener: LiveSttListener, worker: DispatchQueue) throws {
        self.tier = tier
        self.listener = listener
        self.worker = worker
        var opts = options
        opts.tier = tier
        self.segmenter = Segmenter(
            vad: vad, sampleRate: 16000, endpointSilenceMs: live.endpointSilenceMs, maxUtteranceMs: live.maxUtteranceMs, minSpeechMs: live.minSpeechMs,
            onSpeechStart: { listener.onSpeechStart() },
            onSegment: { seg in
                listener.onSpeechEnd()
                worker.async {
                    if seg.samples.isEmpty { return }
                    do {
                        listener.onFinal(try engine.transcribe(seg, options: opts))
                    } catch let e as VoiceError {
                        listener.onError(e)
                    } catch {
                        listener.onError(VoiceError(.ENGINE_FAILED, String(describing: error), tier: tier))
                    }
                }
            })
        listener.onTierChanged(tier, "live session on \(engine.id)")
        let seg = self.segmenter
        try mic.start(onBuffer: nil, onFrame: { frame in
            listener.onLevel(PcmAudio(samples: frame, sampleRate: 16000).rmsDb())
            seg.accept(frame)
        })
        listener.onReady()
    }

    func stop() {
        if !stopped.trySet() { return }
        mic.stop()
        segmenter.flush()
        let l = listener
        worker.async { l.onStopped() }
    }

    func cancel() {
        if !stopped.trySet() { return }
        mic.stop()
        listener.onStopped()
    }
}

// MARK: - The SDK facade

/// Port of sdk-core's IndicVoice: one instance per app, created from voice-config.json.
final class VoiceSdk {
    let config: VoiceConfig
    let packs: PackManager
    let backend: BackendSettings
    private var enabled: [Feature: Bool]
    private let hostedStt: HostedSttEngine
    private let hostedTts: HostedTtsEngine
    private let appleStt: AppleSpeechEngine
    private let appleTts: AppleTtsEngine
    private var loadedStt: [(id: String, engine: SttEngine)] = []
    private var loadedTts: [(id: String, engine: TtsEngine)] = []
    private var vad: Vad?
    private let lock = NSRecursiveLock()
    private let worker = DispatchQueue(label: "dev.indicvoice.worker", qos: .userInitiated)
    private let playback = AudioPlayback()

    init(config: VoiceConfig) {
        self.config = config
        IVLog.setLevel(config.logLevel)
        let fm = FileManager.default
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? fm.temporaryDirectory
        var storage = support.appendingPathComponent("indicvoice/packs", isDirectory: true)
        if !config.packs.storageDir.isEmpty && config.packs.storageDir.hasPrefix("/") {
            storage = URL(fileURLWithPath: config.packs.storageDir, isDirectory: true)
        }
        self.packs = PackManager(storageDir: storage, inlineCatalog: config.packs.catalogInline, catalogUrl: config.packs.catalogUrl)
        let be = BackendSettings()
        self.backend = be
        var en: [Feature: Bool] = [:]
        for f in Feature.all { en[f] = config.features.contains(f) }
        self.enabled = en
        self.hostedStt = HostedSttEngine(cfg: config.stt.tier3, backend: be)
        self.hostedTts = HostedTtsEngine(cfg: config.tts.tier3, backend: be)
        self.appleStt = AppleSpeechEngine(cfg: config.stt, defaultLanguage: config.defaultLanguage)
        self.appleTts = AppleTtsEngine(cfg: config.tts)
    }

    // MARK: switches

    func isEnabled(_ feature: Feature) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return enabled[feature] ?? false
    }

    func setFeatureEnabled(_ feature: Feature, _ on: Bool) {
        lock.lock(); enabled[feature] = on; lock.unlock()
    }

    /// Inject the backend at runtime (from the app's UI or .env). Empty URL disables tier 3.
    func setBackend(url: String?, token: String?) {
        backend.url = (url ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        backend.token = (token ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func backendUrl() -> String { return backend.resolvedUrl(config.stt.tier3.backendUrl) }

    // MARK: tiers

    func sttOrder() -> [Tier] {
        switch config.stt.tierPolicy {
        case .DEVICE_FIRST: return [.os, .onDevice, .hosted]
        case .DEVICE_ONLY: return [.os, .onDevice]
        case .HOSTED_FIRST: return [.hosted, .os, .onDevice]
        case .HOSTED_ONLY: return [.hosted]
        case .ORDER: return config.stt.tierOrder
        }
    }

    func ttsOrder() -> [Tier] {
        switch config.tts.voicePolicy {
        case .OS_FIRST: return [.os, .onDevice, .hosted]
        case .CONSISTENT: return [.onDevice, .hosted, .os]
        case .DEVICE_ONLY: return [.os, .onDevice]
        case .HOSTED_FIRST: return [.hosted, .os, .onDevice]
        case .HOSTED_ONLY: return [.hosted]
        case .ORDER: return config.tts.tierOrder
        }
    }

    /// Resolves the STT engine for a tier, or returns the reason it is unavailable. With `load` = false a tier-2
    /// pack is only checked, not loaded into memory.
    func sttEngine(_ tier: Tier, language: Language?, packId: String? = nil, load: Bool = true) -> (SttEngine?, Availability) {
        switch tier {
        case .os:
            if !config.stt.tier1Enabled { return (nil, .no("tier1 disabled in config")) }
            return (appleStt, appleStt.availability(language))
        case .onDevice:
            return onDeviceStt(language, packId, load)
        case .hosted:
            return (hostedStt, hostedStt.availability(language))
        }
    }

    private func onDeviceStt(_ language: Language?, _ packId: String?, _ load: Bool) -> (SttEngine?, Availability) {
        lock.lock(); defer { lock.unlock() }
        if !config.stt.tier2Enabled { return (nil, .no("tier2 disabled in config")) }
        guard let id = packId ?? config.stt.packFor(language) else { return (nil, .no("no pack configured for \(language?.code ?? "auto")")) }
        guard let spec = packs.spec(id) else { return (nil, .no("pack '\(id)' not in catalog")) }
        if let l = language, !spec.supports(l) {
            // Try the catalog for another installed STT pack that knows the language.
            guard let alt = packs.catalogList().first(where: { $0.kind == "stt" && $0.supports(l) && packs.isInstalled($0.id) }) else {
                return (nil, .no("pack '\(id)' does not support \(l.code)", ["packId": id]))
            }
            if load { return loadStt(alt) }
            let loaded = loadedStt.contains { $0.id == alt.id }
            return (nil, Availability(available: true, reason: nil, details: ["packId": alt.id, "engine": "sherpa:\(alt.id)", "loaded": loaded]))
        }
        if !packs.isInstalled(id) {
            return (nil, .no("pack '\(id)' not downloaded", ["packId": id, "sizeMb": spec.sizeMb, "downloadable": true]))
        }
        if !load {
            if let e = loadedStt.first(where: { $0.id == id }) {
                return (e.engine, Availability(available: true, reason: nil, details: ["packId": id, "engine": e.engine.id, "loaded": true]))
            }
            return (nil, Availability(available: true, reason: nil, details: ["packId": id, "engine": "sherpa:\(id)", "loaded": false]))
        }
        return loadStt(spec)
    }

    private func loadStt(_ spec: PackSpec) -> (SttEngine?, Availability) {
        if let e = loadedStt.first(where: { $0.id == spec.id }) {
            return (e.engine, Availability(available: true, reason: nil, details: ["packId": spec.id, "engine": spec.engine, "cached": true]))
        }
        do {
            let t0 = nowMs()
            let e = try SherpaSttEngine(spec: spec, dir: packs.dir(for: spec.id), numThreads: config.stt.tier2NumThreads)
            let ms = nowMs() - t0
            IVLog.info("loaded STT pack \(spec.id) in \(ms) ms")
            while loadedStt.count >= 2 {
                let old = loadedStt.removeFirst()
                old.engine.close()
                IVLog.info("evicted pack \(old.id)")
            }
            loadedStt.append((id: spec.id, engine: e))
            return (e, Availability(available: true, reason: nil, details: ["packId": spec.id, "engine": spec.engine, "loadMs": ms]))
        } catch {
            IVLog.error("failed to load pack \(spec.id): \(error)")
            return (nil, .no("pack '\(spec.id)' failed to load: \(VoiceError.wrap(error).message)", ["packId": spec.id]))
        }
    }

    func ttsEngine(_ tier: Tier, language: Language, packId: String? = nil, load: Bool = true) -> (TtsEngine?, Availability) {
        switch tier {
        case .os:
            if !config.tts.tier1Enabled { return (nil, .no("tier1 disabled in config")) }
            return (appleTts, appleTts.availability(language))
        case .onDevice:
            return onDeviceTts(language, packId, load)
        case .hosted:
            return (hostedTts, hostedTts.availability(language))
        }
    }

    private func onDeviceTts(_ language: Language, _ packId: String?, _ load: Bool) -> (TtsEngine?, Availability) {
        lock.lock(); defer { lock.unlock() }
        if !config.tts.tier2Enabled { return (nil, .no("tier2 disabled in config")) }
        guard let id = packId ?? config.tts.packFor(language) else { return (nil, .no("no TTS pack configured for \(language.code)")) }
        guard let spec = packs.spec(id) else { return (nil, .no("pack '\(id)' not in catalog")) }
        if !spec.supports(language) { return (nil, .no("pack '\(id)' has no \(language.code) voice")) }
        if !packs.isInstalled(id) {
            return (nil, .no("pack '\(id)' not downloaded", ["packId": id, "sizeMb": spec.sizeMb, "downloadable": true]))
        }
        if let e = loadedTts.first(where: { $0.id == id }) {
            return (e.engine, Availability(available: true, reason: nil, details: ["packId": id, "engine": e.engine.id, "loaded": true]))
        }
        if !load { return (nil, Availability(available: true, reason: nil, details: ["packId": id, "engine": "sherpa:\(id)", "loaded": false])) }
        do {
            let t0 = nowMs()
            let e = try SherpaTtsEngine(spec: spec, dir: packs.dir(for: id), numThreads: config.tts.tier2NumThreads)
            while loadedTts.count >= 2 {
                let old = loadedTts.removeFirst()
                old.engine.close()
            }
            loadedTts.append((id: id, engine: e))
            return (e, Availability(available: true, reason: nil, details: ["packId": id, "loadMs": nowMs() - t0]))
        } catch {
            return (nil, .no("pack '\(id)' failed to load: \(VoiceError.wrap(error).message)"))
        }
    }

    // MARK: capabilities

    static func deviceInfo() -> [String: Any] {
        var sys = utsname()
        uname(&sys)
        let machine = withUnsafePointer(to: &sys.machine) { ptr -> String in
            return ptr.withMemoryRebound(to: CChar.self, capacity: 256) { String(cString: $0) }
        }
        let v = ProcessInfo.processInfo.operatingSystemVersion
        var info: [String: Any] = [
            "platform": "ios",
            "model": machine,
            "release": "\(v.majorVersion).\(v.minorVersion).\(v.patchVersion)",
            "cpus": ProcessInfo.processInfo.activeProcessorCount,
            "ramMb": Int(ProcessInfo.processInfo.physicalMemory / 1048576),
            "lowPowerMode": ProcessInfo.processInfo.isLowPowerModeEnabled,
            "thermalState": ProcessInfo.processInfo.thermalState.rawValue,
        ]
        #if targetEnvironment(simulator)
        info["simulator"] = true
        #endif
        return info
    }

    private func tierStatus(_ t: Tier, engine: String?, av: Availability) -> [String: Any] {
        var engineName: Any = NSNull()
        if let e = engine { engineName = e } else if let d = av.details["engine"] { engineName = d }
        return ["tier": t.number, "label": t.label, "engine": engineName, "availability": av.toJson()]
    }

    /// Everything the UI needs to show per tier: available?, why not, which pack/engine.
    func capabilities(_ language: Language?) -> [String: Any] {
        let lang = language ?? config.defaultLanguage
        var stt: [String: Any] = [:]
        for t in Tier.all {
            let (engine, av) = sttEngine(t, language: language, load: false)
            stt["tier\(t.number)"] = tierStatus(t, engine: engine?.id, av: av)
        }
        var tts: [String: Any] = [:]
        for t in Tier.all {
            let (engine, av) = ttsEngine(t, language: lang, load: false)
            tts["tier\(t.number)"] = tierStatus(t, engine: engine?.id, av: av)
        }
        return [
            "platform": "ios",
            "device": VoiceSdk.deviceInfo(),
            "language": language?.code ?? "auto",
            "features": ["STT": isEnabled(.stt), "TTS": isEnabled(.tts)],
            "backendUrl": backendUrl(),
            "stt": ["policy": config.stt.tierPolicy.rawValue, "order": sttOrder().map { $0.number }, "tiers": stt],
            "tts": ["policy": config.tts.voicePolicy.rawValue, "order": ttsOrder().map { $0.number }, "tiers": tts],
            "packs": packs.listStatus(),
        ]
    }

    // MARK: STT (batch)

    private func withAllowed(_ options: SttOptions) -> SttOptions {
        var o = options
        if o.allowedLanguages.isEmpty { o.allowedLanguages = config.allowedLanguages }
        return o
    }

    /// Transcribes a complete utterance. Follows the tier policy unless options.tier is set.
    func transcribe(_ audio: PcmAudio, options: SttOptions) throws -> TranscriptResult {
        if !isEnabled(.stt) { throw VoiceError(.FEATURE_DISABLED, "STT is disabled") }
        let opts = withAllowed(options)
        let tiers = opts.tier.map { [$0] } ?? sttOrder()
        var reasons: [String] = []
        var lastError: VoiceError? = nil
        for t in tiers {
            let (engine, av) = sttEngine(t, language: opts.language, packId: t == .onDevice ? opts.packId : nil)
            guard let e = engine, av.available else { reasons.append("tier\(t.number): \(av.reason ?? "unavailable")"); continue }
            do {
                let r = try e.transcribe(audio, options: opts)
                if opts.tier == nil && config.stt.fallbackOnLowConfidence > 0 && (r.confidence ?? 1.0) < config.stt.fallbackOnLowConfidence && t != tiers.last {
                    reasons.append("tier\(t.number): low confidence \(r.confidence ?? 0)")
                    continue
                }
                return r
            } catch let e as VoiceError {
                lastError = e
                reasons.append("tier\(t.number): \(e.code.rawValue) \(e.message)")
                IVLog.warn("tier \(t.number) failed: \(e.message)")
                if !config.stt.fallbackOnError && opts.tier == nil { throw e }
            } catch {
                lastError = VoiceError(.ENGINE_FAILED, String(describing: error), tier: t)
                reasons.append("tier\(t.number): \(error)")
                IVLog.error("tier \(t.number) crashed: \(error)")
            }
        }
        if tiers.count == 1, let e = lastError { throw e }
        throw VoiceError(.NO_ENGINE, "No tier could transcribe (" + reasons.joined(separator: "; ") + ")")
    }

    /// Runs the same audio through every requested tier and reports side by side.
    func benchmark(_ audio: PcmAudio, options: SttOptions, tiers: [Tier], reference: String?) -> [String: Any] {
        let opts = withAllowed(options)
        var rows: [[String: Any]] = []
        for t in tiers {
            var row: [String: Any] = ["tier": t.number, "label": t.label]
            let (engine, av) = sttEngine(t, language: opts.language, packId: t == .onDevice ? opts.packId : nil)
            row["available"] = av.available
            row["reason"] = jsonOrNull(av.reason)
            row["engine"] = jsonOrNull(engine?.id)
            row["details"] = av.details
            if let e = engine, av.available {
                do {
                    var o = opts
                    o.tier = t
                    let t0 = Date()
                    let r = try e.transcribe(audio, options: o)
                    let wall = Int(Date().timeIntervalSince(t0) * 1000)
                    row["ok"] = true
                    row["result"] = r.toJson()
                    row["wallMs"] = wall
                    if let ref = reference, !ref.isEmpty {
                        row["wer"] = round3(Metrics.wer(ref, r.text))
                        row["cer"] = round3(Metrics.cer(ref, r.text))
                    }
                } catch let e as VoiceError {
                    row["ok"] = false
                    row["error"] = e.toJson()
                } catch {
                    row["ok"] = false
                    row["error"] = ["code": "ENGINE_FAILED", "message": String(describing: error)]
                }
            } else {
                row["ok"] = false
            }
            rows.append(row)
        }
        return [
            "audioMs": audio.durationMs, "sampleRate": audio.sampleRate, "rmsDb": (audio.rmsDb() * 10).rounded() / 10,
            "language": opts.language?.code ?? "auto", "reference": jsonOrNull(reference), "device": VoiceSdk.deviceInfo(), "tiers": rows,
        ]
    }

    // MARK: STT (live)

    /// Live microphone recognition. Tier 1 uses Apple's own listening session; tiers 2/3 record through
    /// MicrophoneSource, cut utterances with the VAD and transcribe each segment. The returned handle follows
    /// the session across tier fallbacks.
    func startLive(options: SttOptions, listener: LiveSttListener) throws -> LiveSttSession {
        if !isEnabled(.stt) { throw VoiceError(.FEATURE_DISABLED, "STT is disabled") }
        let handle = LiveHandle()
        try startLive(withAllowed(options), listener, into: handle)
        return handle
    }

    private func startLive(_ opts: SttOptions, _ listener: LiveSttListener, into handle: LiveHandle) throws {
        let tiers = opts.tier.map { [$0] } ?? sttOrder()
        var reasons: [String] = []
        for (i, t) in tiers.enumerated() {
            let (engine, av) = sttEngine(t, language: opts.language, packId: t == .onDevice ? opts.packId : nil)
            guard let e = engine, av.available else { reasons.append("tier\(t.number): \(av.reason ?? "unavailable")"); continue }
            if let liveEngine = e as? LiveCapable {
                let remaining = Array(tiers.dropFirst(i + 1))
                let session = try liveEngine.startLive(options: opts, listener: fallbackListener(listener, opts, remaining, handle))
                handle.current = session
                return
            }
            guard AppleSpeechEngine.ensureMicrophoneAuthorized() else { throw VoiceError(.PERMISSION_DENIED, "Microphone permission not granted", tier: t) }
            let session = try SegmentedLiveSession(tier: t, engine: e, vad: vadInstance(), live: config.stt.live, options: opts, listener: listener, worker: worker)
            handle.current = session
            return
        }
        throw VoiceError(.NO_ENGINE, "No tier can listen (" + reasons.joined(separator: "; ") + ")")
    }

    /// Wraps a tier-1 live session so an OS-engine failure falls through to the next tier. The failing session's
    /// own onStopped is swallowed while falling back, so the app sees one continuous session (onTierChanged,
    /// then the next tier's onReady).
    private func fallbackListener(_ inner: LiveSttListener, _ opts: SttOptions, _ remaining: [Tier], _ handle: LiveHandle) -> LiveSttListener {
        let l = LiveSttListener()
        let fallingBack = AtomicFlag()
        l.onReady = inner.onReady
        l.onSpeechStart = inner.onSpeechStart
        l.onSpeechEnd = inner.onSpeechEnd
        l.onLevel = inner.onLevel
        l.onPartial = inner.onPartial
        l.onFinal = inner.onFinal
        l.onTierChanged = inner.onTierChanged
        l.onStopped = { if !fallingBack.isSet { inner.onStopped() } }
        l.onError = { [weak self] error in
            guard let self = self else { inner.onError(error); return }
            let noFallback = !self.config.stt.fallbackOnError || remaining.isEmpty || opts.tier != nil
                || error.code == .PERMISSION_DENIED || error.code == .CANCELLED || error.code == .BAD_AUDIO
            if noFallback { inner.onError(error); return }
            _ = fallingBack.trySet()
            IVLog.warn("tier \(error.tier?.number ?? 1) live failed (\(error.message)); falling back")
            let next = remaining[0]
            inner.onTierChanged(next, error.message)
            var o = opts
            o.tier = next
            // Off the failing session's callback queue: starting the next tier must not block it.
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    try self.startLive(o, inner, into: handle)
                } catch {
                    inner.onError(VoiceError.wrap(error))
                    inner.onStopped()
                }
            }
        }
        return l
    }

    private func vadInstance() -> Vad {
        lock.lock(); defer { lock.unlock() }
        if let v = vad { v.reset(); return v }
        var v: Vad = EnergyVad()
        if let spec = packs.spec(config.stt.tier2VadPack), packs.isInstalled(spec.id) {
            let model = packs.dir(for: spec.id).appendingPathComponent(spec.model.str("model") ?? "silero_vad.onnx").path
            do {
                v = try SherpaVad(modelPath: model, threshold: Float(config.stt.live.vadThreshold))
            } catch {
                IVLog.warn("VAD pack failed (\(error)); using energy VAD")
            }
        }
        vad = v
        return v
    }

    /// Records one VAD-endpointed utterance from the microphone (energy VAD, like the Android module).
    func recordUtterance(maxSeconds: Int, onReady: @escaping () -> Void, onSpeechStart: @escaping () -> Void,
                         onLevel: @escaping (Double) -> Void, onSpeechEnd: @escaping () -> Void) throws -> PcmAudio {
        guard AppleSpeechEngine.ensureMicrophoneAuthorized() else { throw VoiceError(.PERMISSION_DENIED, "Microphone permission not granted") }
        let live = config.stt.live
        let sem = DispatchSemaphore(value: 0)
        let box = ResultBox<PcmAudio?>(nil)
        let boxLock = NSLock()
        let seg = Segmenter(vad: EnergyVad(), sampleRate: 16000, endpointSilenceMs: live.endpointSilenceMs, maxUtteranceMs: maxSeconds * 1000,
                            minSpeechMs: live.minSpeechMs, onSpeechStart: onSpeechStart, onSegment: { s in
            boxLock.lock()
            let first = box.value == nil
            if first { box.value = s }
            boxLock.unlock()
            if first { sem.signal() }
        })
        let mic = MicrophoneSource()
        onReady()
        try mic.start(onBuffer: nil, onFrame: { frame in
            onLevel(PcmAudio(samples: frame, sampleRate: 16000).rmsDb())
            seg.accept(frame)
        })
        _ = sem.wait(timeout: .now() + .seconds(maxSeconds + 2))
        mic.stop()
        seg.flush()
        onSpeechEnd()
        boxLock.lock()
        let audio = box.value
        boxLock.unlock()
        guard let a = audio else { throw VoiceError(.BAD_AUDIO, "No speech detected") }
        return a
    }

    // MARK: TTS

    func voices(_ language: Language?) -> [VoiceInfo] {
        var out: [VoiceInfo] = []
        out.append(contentsOf: appleTts.voices(language))
        for spec in packs.catalogList() where spec.kind == "tts" && (language == nil || spec.supports(language)) {
            let langs = language.map { [$0] } ?? spec.languages
            for l in langs {
                out.append(VoiceInfo(id: "pack:\(spec.id)", language: l, tier: .onDevice, name: spec.name, gender: nil, offline: true,
                                     quality: spec.raw.str("quality"), installed: packs.isInstalled(spec.id)))
            }
        }
        if !backendUrl().isEmpty { out.append(contentsOf: hostedTts.voices(language)) }
        return out
    }

    /// Synthesizes speech following the voice policy unless options.tier is set.
    func synthesize(_ options: TtsOptions) throws -> TtsResult {
        if !isEnabled(.tts) { throw VoiceError(.FEATURE_DISABLED, "TTS is disabled") }
        var opts = options
        // "pack:<id>" / "os:<voice>" / "hosted:<id>" voice ids select the tier implicitly.
        if let v = opts.voiceId {
            if v.hasPrefix("pack:") {
                opts.tier = .onDevice
                let rest = String(v.dropFirst(5))
                if let hash = rest.firstIndex(of: "#") {
                    opts.packId = String(rest[..<hash])
                    opts.voiceId = String(rest[rest.index(after: hash)...])
                } else {
                    opts.packId = rest
                    opts.voiceId = nil
                }
            } else if v.hasPrefix("os:") {
                opts.tier = .os
                opts.voiceId = String(v.dropFirst(3))
            } else if v.hasPrefix("hosted:") {
                opts.tier = .hosted
                opts.voiceId = String(v.dropFirst(7))
            }
        }
        if opts.voiceId == nil && opts.tier == nil, let pref = config.tts.preferredVoices[opts.language.code], !pref.isEmpty {
            var o = opts
            o.voiceId = pref
            return try synthesize(o)
        }
        let tiers = opts.tier.map { [$0] } ?? ttsOrder()
        var reasons: [String] = []
        var last: VoiceError? = nil
        for t in tiers {
            let (engine, av) = ttsEngine(t, language: opts.language, packId: t == .onDevice ? opts.packId : nil)
            guard let e = engine, av.available else { reasons.append("tier\(t.number): \(av.reason ?? "unavailable")"); continue }
            do {
                var o = opts
                o.tier = t
                return try e.synthesize(o)
            } catch let err as VoiceError {
                last = err
                reasons.append("tier\(t.number): \(err.message)")
                if !config.tts.fallbackOnError && opts.tier == nil { throw err }
            } catch {
                last = VoiceError(.ENGINE_FAILED, String(describing: error), tier: t)
                reasons.append("tier\(t.number): \(error)")
            }
        }
        if tiers.count == 1, let e = last { throw e }
        throw VoiceError(.NO_ENGINE, "No tier could synthesize (" + reasons.joined(separator: "; ") + ")")
    }

    func play(_ audio: PcmAudio, onDone: @escaping () -> Void) throws {
        try playback.play(audio, onDone: onDone)
    }

    func stopSpeaking() { playback.stop() }

    /// Benchmark TTS tiers for one sentence.
    func benchmarkTts(_ options: TtsOptions, tiers: [Tier] = Tier.all) -> [String: Any] {
        var rows: [[String: Any]] = []
        for t in tiers {
            var row: [String: Any] = ["tier": t.number, "label": t.label]
            let (engine, av) = ttsEngine(t, language: options.language, packId: t == .onDevice ? options.packId : nil)
            row["available"] = av.available
            row["reason"] = jsonOrNull(av.reason)
            row["engine"] = jsonOrNull(engine?.id)
            if let e = engine, av.available {
                do {
                    var o = options
                    o.tier = t
                    let r = try e.synthesize(o)
                    row["ok"] = true
                    row["result"] = r.toJson()
                } catch let err as VoiceError {
                    row["ok"] = false
                    row["error"] = err.toJson()
                } catch {
                    row["ok"] = false
                    row["error"] = ["message": String(describing: error)]
                }
            } else {
                row["ok"] = false
            }
            rows.append(row)
        }
        return ["text": options.text, "language": options.language.code, "tiers": rows]
    }

    func close() {
        lock.lock()
        for e in loadedStt { e.engine.close() }
        for e in loadedTts { e.engine.close() }
        loadedStt = []
        loadedTts = []
        vad = nil
        lock.unlock()
        playback.stop()
    }
}
