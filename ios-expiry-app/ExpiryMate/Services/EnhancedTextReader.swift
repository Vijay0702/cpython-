import CoreImage
import CoreImage.CIFilterBuiltins
import ImageIO
import UIKit
import Vision

/// Reads text from a still photo after treating it in several ways, for print
/// the live scanner cannot read: dot-matrix, embossed or faint dates.
///
/// Dot-matrix characters are separate dots, which text recognition tends to see
/// as specks. Spreading the dark (or light) areas joins the dots into strokes.
/// No single setting suits every label, so each variant is read separately and
/// the caller uses the first one that yields a date.
enum EnhancedTextReader {

    /// Text recognised in each variant of the photo, plainest treatment first.
    static func readVariants(of image: UIImage) async -> [String] {
        await Task.detached(priority: .userInitiated) {
            variants(of: image).map(recognise)
        }.value
    }

    private static func variants(of image: UIImage) -> [CGImage] {
        guard let source = image.cgImage else { return [] }

        var base = CIImage(cgImage: source).oriented(CGImagePropertyOrientation(image.imageOrientation))
        // A fixed working size keeps the spreading radii meaningful across cameras.
        let scale = min(1, 2200 / max(base.extent.width, base.extent.height))
        base = base.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

        var images: [CIImage] = [base]
        for radius in [2, 4] as [Float] {
            // Dark print on a light background: grow the dark dots.
            let darken = CIFilter.morphologyMinimum()
            darken.inputImage = base
            darken.radius = radius
            if let output = darken.outputImage { images.append(output) }

            // Light or embossed print on a dark background: grow the light dots.
            let lighten = CIFilter.morphologyMaximum()
            lighten.inputImage = base
            lighten.radius = radius
            if let output = lighten.outputImage { images.append(output) }
        }

        let context = CIContext()
        return images.compactMap { variant in
            let contrast = CIFilter.colorControls()
            contrast.inputImage = variant
            contrast.saturation = 0
            contrast.contrast = 1.5
            guard let output = contrast.outputImage else { return nil }
            return context.createCGImage(output, from: base.extent)
        }
    }

    private static func recognise(_ image: CGImage) -> String {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        // Dates and codes are not words; correction would "fix" them into nonsense.
        request.usesLanguageCorrection = false
        try? VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
        return (request.results ?? [])
            .compactMap { $0.topCandidates(1).first?.string }
            .joined(separator: "\n")
    }
}

private extension CGImagePropertyOrientation {
    init(_ orientation: UIImage.Orientation) {
        switch orientation {
        case .up: self = .up
        case .down: self = .down
        case .left: self = .left
        case .right: self = .right
        case .upMirrored: self = .upMirrored
        case .downMirrored: self = .downMirrored
        case .leftMirrored: self = .leftMirrored
        case .rightMirrored: self = .rightMirrored
        @unknown default: self = .up
        }
    }
}
