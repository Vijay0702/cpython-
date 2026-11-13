import AVFoundation
import SwiftUI
import VisionKit

/// The phone's flashlight, for reading dates on dark or shiny packaging.
enum Torch {
    static func set(_ on: Bool) {
        guard
            let device = AVCaptureDevice.default(for: .video),
            device.hasTorch,
            (try? device.lockForConfiguration()) != nil
        else { return }
        device.torchMode = on ? .on : .off
        device.unlockForConfiguration()
    }
}

/// Lets the screen that shows a scanner ask it for a full-resolution still photo.
@MainActor
final class ScannerHandle {
    fileprivate weak var scanner: DataScannerViewController?

    func capturePhoto() async -> UIImage? {
        guard let scanner else { return nil }
        return try? await scanner.capturePhoto()
    }
}

/// Live camera scanner for either barcodes or printed text.
struct ScannerView: UIViewControllerRepresentable {
    enum Mode {
        case barcode
        case text
    }

    let mode: Mode
    var handle: ScannerHandle?
    /// Called with the barcode digits (barcode mode).
    var onBarcode: (String) -> Void = { _ in }
    /// Called with every piece of text currently in view (text mode).
    var onText: ([String]) -> Void = { _ in }

    /// False on the simulator, on unsupported devices, or when camera access is denied.
    @MainActor
    static var isUsable: Bool {
        DataScannerViewController.isSupported && DataScannerViewController.isAvailable
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: mode == .barcode ? [.barcode()] : [.text()],
            // Small printed dates need the slower, sharper mode; barcodes do not.
            qualityLevel: mode == .text ? .accurate : .balanced,
            recognizesMultipleItems: mode == .text,
            isHighFrameRateTrackingEnabled: false,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        handle?.scanner = scanner
        return scanner
    }

    func updateUIViewController(_ scanner: DataScannerViewController, context: Context) {
        context.coordinator.parent = self
        if !scanner.isScanning {
            try? scanner.startScanning()
        }
    }

    static func dismantleUIViewController(_ scanner: DataScannerViewController, coordinator: Coordinator) {
        scanner.stopScanning()
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        var parent: ScannerView

        init(parent: ScannerView) {
            self.parent = parent
        }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            report(allItems)
        }

        func dataScanner(_ dataScanner: DataScannerViewController, didUpdate updatedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            report(allItems)
        }

        private func report(_ items: [RecognizedItem]) {
            switch parent.mode {
            case .barcode:
                for item in items {
                    if case .barcode(let barcode) = item, let payload = barcode.payloadStringValue {
                        parent.onBarcode(payload)
                        return
                    }
                }
            case .text:
                let texts = items.compactMap { item -> String? in
                    if case .text(let text) = item { return text.transcript }
                    return nil
                }
                if !texts.isEmpty {
                    parent.onText(texts)
                }
            }
        }
    }
}
