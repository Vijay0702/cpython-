import Foundation

/// Looks up a product name from its barcode using the free Open Food Facts database.
enum ProductLookup {
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
        guard
            barcode.allSatisfy(\.isNumber),
            let url = URL(string: "https://world.openfoodfacts.org/api/v2/product/\(barcode).json?fields=product_name,brands")
        else { return nil }

        var request = URLRequest(url: url, timeoutInterval: 8)
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
