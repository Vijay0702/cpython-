import AVFoundation
import SwiftData
import SwiftUI

/// Three steps: scan the barcode, scan the printed expiry date, confirm and save.
/// Every step can be skipped, so the app still works without a camera.
struct AddItemView: View {
    private enum Step {
        case checkingCamera
        case barcode
        case date
        case confirm
    }

    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    @State private var step: Step = .checkingCamera
    @State private var cameraUsable = false
    @State private var barcode: String?
    @State private var name = ""
    @State private var expiryDate = Calendar.current.date(byAdding: .day, value: 7, to: Date()) ?? Date()
    @State private var detected: ParsedExpiry?
    @State private var scannerHandle = ScannerHandle()
    @State private var isEnhancing = false
    @State private var enhanceFailed = false
    @State private var isLookingUp = false
    @State private var torchOn = false

    var body: some View {
        NavigationStack {
            Group {
                switch step {
                case .checkingCamera: ProgressView()
                case .barcode: barcodeStep
                case .date: dateStep
                case .confirm: confirmStep
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onChange(of: step) {
                // Each step starts its own camera session, so start with the light off.
                torchOn = false
                Torch.set(false)
            }
            .onDisappear { Torch.set(false) }
            .task {
                // The scanner reports itself unavailable until camera access has
                // been granted, so ask first and fall back to typing if refused.
                let granted = await AVCaptureDevice.requestAccess(for: .video)
                cameraUsable = granted && ScannerView.isUsable
                if step == .checkingCamera {
                    step = cameraUsable ? .barcode : .confirm
                }
            }
        }
    }

    private var title: String {
        switch step {
        case .checkingCamera: return "Add item"
        case .barcode: return "Scan barcode"
        case .date: return "Scan expiry date"
        case .confirm: return "Confirm item"
        }
    }

    // MARK: - Step 1: barcode

    private var barcodeStep: some View {
        ScannerView(mode: .barcode, onBarcode: handleBarcode)
            .ignoresSafeArea(edges: .bottom)
            .overlay(alignment: .topTrailing) { torchButton }
            .overlay(alignment: .bottom) {
                VStack(spacing: 12) {
                    if isLookingUp {
                        ProgressView("Looking up product…")
                    } else {
                        Text("Point the camera at the product's barcode")
                    }
                    Button("No barcode? Skip") { step = .date }
                        .buttonStyle(.bordered)
                }
                .scannerPanel()
            }
    }

    private func handleBarcode(_ code: String) {
        // The scanner reports the same code on every frame; act on the first only.
        guard barcode == nil else { return }
        barcode = code
        isLookingUp = true
        Task {
            // A name the user gave this product before beats the database's.
            if let remembered = rememberedName(for: code) {
                name = remembered
            } else if let found = await ProductLookup.name(forBarcode: code) {
                name = found
            }
            isLookingUp = false
            if step == .barcode {
                step = .date
            }
        }
    }

    private func rememberedName(for code: String) -> String? {
        let all = (try? modelContext.fetch(FetchDescriptor<GroceryItem>())) ?? []
        let previous = all.filter { $0.barcode == code }.max { $0.addedAt < $1.addedAt }
        return previous?.name
    }

    private var torchButton: some View {
        Button {
            torchOn.toggle()
            Torch.set(torchOn)
        } label: {
            Image(systemName: torchOn ? "flashlight.on.fill" : "flashlight.off.fill")
                .font(.title2)
                .frame(width: 52, height: 52)
                .background(.regularMaterial, in: Circle())
        }
        .accessibilityLabel(torchOn ? "Turn light off" : "Turn light on")
        .padding()
    }

    // MARK: - Step 2: expiry date

    private var dateStep: some View {
        ScannerView(mode: .text, handle: scannerHandle, onText: handleText)
            .ignoresSafeArea(edges: .bottom)
            .overlay(alignment: .topTrailing) { torchButton }
            .overlay(alignment: .bottom) {
                VStack(spacing: 12) {
                    if !name.isEmpty {
                        Text(name).font(.headline)
                    }
                    if let detected {
                        Text("Found: \(detected.date.formatted(date: .long, time: .omitted))")
                        if let note = detected.note {
                            Text("Calculated: \(note)")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                        Button("Use this date") {
                            expiryDate = detected.date
                            step = .confirm
                        }
                        .buttonStyle(.borderedProminent)
                    } else if isEnhancing {
                        ProgressView("Reading the photo…")
                    } else {
                        Text(enhanceFailed
                             ? "No date found in the photo. Try the light at an angle, or enter the date manually."
                             : "Point the camera at the printed expiry date")
                        Button("Dotted or faint print? Capture", action: enhancedCapture)
                            .buttonStyle(.bordered)
                    }
                    Button("Enter date manually") { step = .confirm }
                        .buttonStyle(.bordered)
                }
                .scannerPanel()
            }
    }

    private func handleText(_ texts: [String]) {
        if let parsed = ExpiryDateParser.parseDetailed(texts.joined(separator: "\n")), parsed != detected {
            detected = parsed
        }
    }

    /// Takes a still photo and reads it several ways; see `EnhancedTextReader`.
    private func enhancedCapture() {
        isEnhancing = true
        enhanceFailed = false
        Task {
            var found: ParsedExpiry?
            if let photo = await scannerHandle.capturePhoto() {
                for text in await EnhancedTextReader.readVariants(of: photo) {
                    if let parsed = ExpiryDateParser.parseDetailed(text) {
                        found = parsed
                        break
                    }
                }
            }
            isEnhancing = false
            if let found {
                detected = found
            } else {
                enhanceFailed = true
            }
        }
    }

    // MARK: - Step 3: confirm

    private var confirmStep: some View {
        Form {
            Section("Product") {
                TextField("Name", text: $name)
                if let barcode {
                    LabeledContent("Barcode", value: barcode)
                }
            }
            Section("Expiry") {
                DatePicker("Expires on", selection: $expiryDate, displayedComponents: .date)
                HStack {
                    quickDate("3 days", .day, 3)
                    quickDate("1 week", .day, 7)
                    quickDate("2 weeks", .day, 14)
                    quickDate("1 month", .month, 1)
                }
                .buttonStyle(.bordered)
                .font(.subheadline)
            }
            if cameraUsable {
                Section {
                    Button("Scan again") {
                        barcode = nil
                        detected = nil
                        enhanceFailed = false
                        step = .barcode
                    }
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Save", action: save)
                    .disabled(trimmedName.isEmpty)
            }
        }
    }

    /// One-tap expiry for when the printed date cannot be scanned.
    private func quickDate(_ label: String, _ unit: Calendar.Component, _ amount: Int) -> some View {
        Button(label) {
            expiryDate = Calendar.current.date(byAdding: unit, value: amount, to: Date()) ?? expiryDate
        }
        .lineLimit(1)
    }

    private var trimmedName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func save() {
        modelContext.insert(GroceryItem(name: trimmedName, barcode: barcode, expiryDate: expiryDate))
        try? modelContext.save()
        let context = modelContext
        Task {
            await ReminderScheduler.shared.requestPermission()
            await ReminderScheduler.shared.refresh(context: context)
        }
        dismiss()
    }
}

private extension View {
    func scannerPanel() -> some View {
        self
            .multilineTextAlignment(.center)
            .padding()
            .frame(maxWidth: .infinity)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
            .padding()
    }
}
