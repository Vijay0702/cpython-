import Foundation

/// Looks up a product name from its barcode in the free, volunteer-run
/// Open Food Facts family of databases.
enum ProductLookup {
    // In order of preference when a barcode is listed in more than one.
    private static let hosts = [
        "world.openfoodfacts.org",      // food and drink
        "world.openbeautyfacts.org",    // cosmetics and toiletries
        "world.openproductsfacts.org",  // everything else
    ]

    private struct Response: Decodable {
        struct Product: Decodable {
            let product_name: String?
            let brands: String?
        }
        let product: Product?
    }

    /// Returns nil when the product is unknown or the network is unavailable;
    /// the user then types the name themselves.
    static func name(forBarcode barcode: String) async -> String? {
        guard !barcode.isEmpty, barcode.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }

        async let food = fetch(host: hosts[0], barcode: barcode)
        async let beauty = fetch(host: hosts[1], barcode: barcode)
        async let other = fetch(host: hosts[2], barcode: barcode)
        let results = await [food, beauty, other]
        return results.compactMap { $0 }.first
    }

    private static func fetch(host: String, barcode: String) async -> String? {
        guard let url = URL(string: "https://\(host)/api/v2/product/\(barcode).json?fields=product_name,brands") else {
            return nil
        }
        var request = URLRequest(url: url, timeoutInterval: 6)
        request.setValue("ExpiryMate/1.0 (iOS)", forHTTPHeaderField: "User-Agent")

        guard
            let (data, _) = try? await URLSession.shared.data(for: request),
            let product = (try? JSONDecoder().decode(Response.self, from: data))?.product
        else { return nil }

        let name = (product.product_name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let brand = (product.brands ?? "")
            .split(separator: ",").first
            .map { $0.trimmingCharacters(in: .whitespaces) } ?? ""

        if name.isEmpty { return brand.isEmpty ? nil : brand }
        if brand.isEmpty || name.localizedCaseInsensitiveContains(brand) { return name }
        return "\(brand) \(name)"
    }
}
