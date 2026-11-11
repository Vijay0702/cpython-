import SwiftUI
import VisionKit

/// Live camera scanner for either barcodes or printed text.
struct ScannerView: UIViewControllerRepresentable {
    enum Mode {
        case barcode
        case text
    }

    let mode: Mode
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
            qualityLevel: .balanced,
            recognizesMultipleItems: mode == .text,
            isHighFrameRateTrackingEnabled: false,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
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
