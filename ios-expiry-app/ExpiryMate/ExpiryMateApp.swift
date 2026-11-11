import SwiftData
import SwiftUI
import UIKit

/// One shared container, so notification actions handled in the background
/// write to the same store the screens read from.
enum Store {
    @MainActor
    static let container: ModelContainer = {
        do {
            return try ModelContainer(for: GroceryItem.self)
        } catch {
            fatalError("Could not open the item store: \(error)")
        }
    }()
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        ReminderScheduler.shared.configure()
        return true
    }
}

@main
struct ExpiryMateApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ItemListView()
        }
        .modelContainer(Store.container)
    }
}
