import AVFoundation
import AudioToolbox
import CoreAudio
import Foundation

/// Records one microphone to a 16 kHz wav file. Pass `--list` to print the input devices. An optional device id after the wav path chooses that microphone instead of the system default.
@main
struct MicrophoneRecorderMain {
    static func main() {
        if CommandLine.arguments.contains("--list") {
            emitDevices()
            return
        }
        guard CommandLine.arguments.count >= 2 else {
            emit(["type": "error", "message": "Missing the recording path."])
            return
        }
        let recordingURL = URL(fileURLWithPath: CommandLine.arguments[1])
        let deviceIdentifier = CommandLine.arguments.count >= 3 ? CommandLine.arguments[2] : ""
        if !waitUntilMicrophoneIsAllowed() {
            emit([
                "type": "error",
                "message": "The microphone is not available. macOS does not let you add an app on that screen. Press the shortcut again and allow Rvoice if a prompt appears.",
            ])
            return
        }
        do {
            try recordUntilStop(to: recordingURL, deviceIdentifier: deviceIdentifier)
        } catch {
            emit([
                "type": "error",
                "message": "The microphone could not record. \(error.localizedDescription)",
            ])
        }
    }
}

private let emitLock = NSLock()
private let sampleLock = NSLock()
private var capturedSamples = [Int16]()

private func emit(_ object: [String: Any]) {
    guard let data = try? JSONSerialization.data(withJSONObject: object) else {
        return
    }
    emitLock.lock()
    FileHandle.standardOutput.write(data)
    FileHandle.standardOutput.write(Data([0x0A]))
    emitLock.unlock()
}

private func emitDevices() {
    let devices = inputDevices().map { device in
        ["id": device.identifier, "name": device.name]
    }
    emit(["type": "devices", "devices": devices])
}

private func waitUntilMicrophoneIsAllowed() -> Bool {
    if AVAudioApplication.shared.recordPermission == .granted {
        return true
    }
    if AVAudioApplication.shared.recordPermission == .denied {
        return false
    }
    var isAllowed = false
    var isFinished = false
    AVAudioApplication.requestRecordPermission { granted in
        isAllowed = granted
        isFinished = true
    }
    let deadline = Date().addingTimeInterval(120)
    while !isFinished && Date() < deadline {
        RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.1))
    }
    return isAllowed
}

private func recordUntilStop(to recordingURL: URL, deviceIdentifier: String) throws {
    try FileManager.default.createDirectory(
        at: recordingURL.deletingLastPathComponent(),
        withIntermediateDirectories: true,
    )
    let engine = AVAudioEngine()
    let input = engine.inputNode
    if !deviceIdentifier.isEmpty {
        guard let deviceIdentifierNumber = audioDeviceIdentifier(matching: deviceIdentifier) else {
            emit([
                "type": "error",
                "message": "That microphone is not connected. Choose another one.",
            ])
            return
        }
        guard let audioUnit = input.audioUnit else {
            emit(["type": "error", "message": "The microphone could not be selected."])
            return
        }
        var selectedDevice = deviceIdentifierNumber
        let status = AudioUnitSetProperty(
            audioUnit,
            kAudioOutputUnitProperty_CurrentDevice,
            kAudioUnitScope_Global,
            0,
            &selectedDevice,
            UInt32(MemoryLayout<AudioDeviceID>.size),
        )
        if status != noErr {
            emit(["type": "error", "message": "That microphone could not be opened."])
            return
        }
    }
    let inputFormat = input.outputFormat(forBus: 0)
    guard let sixteenKilohertzFormat = AVAudioFormat(
        commonFormat: .pcmFormatInt16,
        sampleRate: 16_000,
        channels: 1,
        interleaved: true,
    ), let converter = AVAudioConverter(from: inputFormat, to: sixteenKilohertzFormat) else {
        emit(["type": "error", "message": "The microphone format is not supported."])
        return
    }
    input.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { buffer, _ in
        appendConvertedSamples(buffer, converter: converter, targetFormat: sixteenKilohertzFormat)
    }
    engine.prepare()
    try engine.start()
    emit(["type": "started"])
    DispatchQueue.global(qos: .userInitiated).async {
        while engine.isRunning {
            sampleLock.lock()
            let recent = capturedSamples.suffix(1600)
            sampleLock.unlock()
            let level = microphoneLevel(from: Array(recent))
            emit(["type": "level", "level": level])
            Thread.sleep(forTimeInterval: 0.12)
        }
    }
    while let line = readLine(strippingNewline: true) {
        if line == "snapshot" {
            sampleLock.lock()
            let samples = capturedSamples
            sampleLock.unlock()
            try? writeWav(samples, to: recordingURL)
            emit(["type": "snapshot", "samples": samples.count])
            continue
        }
        if line == "stop" {
            break
        }
    }
    engine.stop()
    input.removeTap(onBus: 0)
    sampleLock.lock()
    let samples = capturedSamples
    sampleLock.unlock()
    try writeWav(samples, to: recordingURL)
    let peak = samples.map { sample in abs(Int(sample)) }.max() ?? 0
    let peakDecibels = peak == 0 ? -160.0 : 20.0 * log10(Double(peak) / 32767.0)
    emit(["type": "stopped", "peakDecibels": peakDecibels])
}

private func appendConvertedSamples(
    _ buffer: AVAudioPCMBuffer,
    converter: AVAudioConverter,
    targetFormat: AVAudioFormat,
) {
    let ratio = targetFormat.sampleRate / buffer.format.sampleRate
    let frameCapacity = AVAudioFrameCount(ceil(Double(buffer.frameLength) * ratio)) + 32
    guard let converted = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: frameCapacity) else {
        return
    }
    var didProvideInput = false
    let status = converter.convert(to: converted, error: nil) { _, inputStatus in
        if didProvideInput {
            inputStatus.pointee = .noDataNow
            return nil
        }
        didProvideInput = true
        inputStatus.pointee = .haveData
        return buffer
    }
    guard status != .error, let channel = converted.int16ChannelData else {
        return
    }
    let frames = UnsafeBufferPointer(start: channel[0], count: Int(converted.frameLength))
    sampleLock.lock()
    capturedSamples.append(contentsOf: frames)
    sampleLock.unlock()
}

private func microphoneLevel(from samples: [Int16]) -> Double {
    if samples.isEmpty {
        return 0
    }
    let peak = samples.map { sample in abs(Int(sample)) }.max() ?? 0
    return min(1.0, Double(peak) / 8000.0)
}

private func writeWav(_ samples: [Int16], to destination: URL) throws {
    let dataSize = samples.count * 2
    var header = Data()
    header.append(contentsOf: [0x52, 0x49, 0x46, 0x46])
    header.append(littleEndian: UInt32(36 + dataSize))
    header.append(contentsOf: [0x57, 0x41, 0x56, 0x45])
    header.append(contentsOf: [0x66, 0x6D, 0x74, 0x20])
    header.append(littleEndian: UInt32(16))
    header.append(littleEndian: UInt16(1))
    header.append(littleEndian: UInt16(1))
    header.append(littleEndian: UInt32(16_000))
    header.append(littleEndian: UInt32(32_000))
    header.append(littleEndian: UInt16(2))
    header.append(littleEndian: UInt16(16))
    header.append(contentsOf: [0x64, 0x61, 0x74, 0x61])
    header.append(littleEndian: UInt32(dataSize))
    var body = Data(capacity: dataSize)
    for sample in samples {
        body.append(littleEndian: UInt16(bitPattern: sample))
    }
    try (header + body).write(to: destination)
}

private struct InputDevice {
    let identifier: String
    let name: String
    let deviceIdentifier: AudioDeviceID
}

private func inputDevices() -> [InputDevice] {
    var address = AudioObjectPropertyAddress(
        mSelector: kAudioHardwarePropertyDevices,
        mScope: kAudioObjectPropertyScopeGlobal,
        mElement: kAudioObjectPropertyElementMain,
    )
    var dataSize: UInt32 = 0
    let system = AudioObjectID(kAudioObjectSystemObject)
    guard AudioObjectGetPropertyDataSize(system, &address, 0, nil, &dataSize) == noErr, dataSize > 0 else {
        return []
    }
    let deviceCount = Int(dataSize) / MemoryLayout<AudioDeviceID>.size
    var deviceIdentifiers = [AudioDeviceID](repeating: 0, count: deviceCount)
    guard AudioObjectGetPropertyData(system, &address, 0, nil, &dataSize, &deviceIdentifiers) == noErr else {
        return []
    }
    return deviceIdentifiers.compactMap { deviceIdentifier in
        guard inputChannelCount(deviceIdentifier) > 0 else {
            return nil
        }
        return InputDevice(
            identifier: stringProperty(kAudioDevicePropertyDeviceUID, device: deviceIdentifier),
            name: stringProperty(kAudioDevicePropertyDeviceNameCFString, device: deviceIdentifier),
            deviceIdentifier: deviceIdentifier,
        )
    }
}

private func audioDeviceIdentifier(matching uniqueIdentifier: String) -> AudioDeviceID? {
    return inputDevices().first { device in
        device.identifier == uniqueIdentifier
    }?.deviceIdentifier
}

private func inputChannelCount(_ device: AudioDeviceID) -> Int {
    var address = AudioObjectPropertyAddress(
        mSelector: kAudioDevicePropertyStreamConfiguration,
        mScope: kAudioDevicePropertyScopeInput,
        mElement: kAudioObjectPropertyElementMain,
    )
    var size: UInt32 = 0
    guard AudioObjectGetPropertyDataSize(device, &address, 0, nil, &size) == noErr, size > 0 else {
        return 0
    }
    let rawBuffer = UnsafeMutableRawPointer.allocate(byteCount: Int(size), alignment: MemoryLayout<AudioBufferList>.alignment)
    defer { rawBuffer.deallocate() }
    guard AudioObjectGetPropertyData(device, &address, 0, nil, &size, rawBuffer) == noErr else {
        return 0
    }
    let audioBuffers = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer<AudioBufferList>(OpaquePointer(rawBuffer)))
    return audioBuffers.reduce(0) { total, buffer in
        total + Int(buffer.mNumberChannels)
    }
}

private func stringProperty(_ selector: AudioObjectPropertySelector, device: AudioDeviceID) -> String {
    var address = AudioObjectPropertyAddress(
        mSelector: selector,
        mScope: kAudioObjectPropertyScopeGlobal,
        mElement: kAudioObjectPropertyElementMain,
    )
    var value: CFString = "" as CFString
    var size = UInt32(MemoryLayout<CFString>.size)
    let status = AudioObjectGetPropertyData(device, &address, 0, nil, &size, &value)
    if status != noErr {
        return ""
    }
    return value as String
}

private extension Data {
    mutating func append(littleEndian value: UInt32) {
        var little = value.littleEndian
        append(Data(bytes: &little, count: 4))
    }

    mutating func append(littleEndian value: UInt16) {
        var little = value.littleEndian
        append(Data(bytes: &little, count: 2))
    }
}
