import Foundation
import SwiftData
import UserNotifications

/// Owns everything that talks to UNUserNotificationCenter.
@MainActor
final class ReminderScheduler: NSObject, UNUserNotificationCenterDelegate {
    static let shared = ReminderScheduler()

    private enum Category {
        static let item = "ITEM_REMINDER"
    }

    private enum Action {
        static let used = "MARK_USED"
        static let discarded = "MARK_DISCARDED"
        static let snooze = "SNOOZE"
    }

    private enum Key {
        static let itemID = "itemID"
    }

    private static let snoozeHours = 3

    private let center = UNUserNotificationCenter.current()

    /// Call once at launch, before the app finishes launching, so that actions
    /// tapped while the app was not running are still delivered.
    func configure() {
        center.delegate = self
        let actions = [
            UNNotificationAction(identifier: Action.used, title: "Used it"),
            UNNotificationAction(identifier: Action.discarded, title: "Threw it away", options: [.destructive]),
            UNNotificationAction(identifier: Action.snooze, title: "Snooze \(Self.snoozeHours) hours"),
        ]
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Category.item, actions: actions, intentIdentifiers: [])
        ])
    }

    @discardableResult
    func requestPermission() async -> Bool {
        (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    func isDenied() async -> Bool {
        await center.notificationSettings().authorizationStatus == .denied
    }

    /// Replaces all pending notifications with a fresh plan built from the stored items.
    func refresh(context: ModelContext) async {
        let items = ((try? context.fetch(FetchDescriptor<GroceryItem>())) ?? []).filter(\.isActive)
        let snapshots = items.map {
            ItemSnapshot(id: $0.id, name: $0.name, expiryDate: $0.expiryDate, snoozedUntil: $0.snoozedUntil)
        }
        let plan = ReminderPlanner.plan(items: snapshots, settings: .load())

        center.removeAllPendingNotificationRequests()
        for reminder in plan {
            let content = UNMutableNotificationContent()
            content.title = reminder.title
            content.body = reminder.body
            content.sound = .default
            if case .hourly(let itemID) = reminder.kind {
                content.categoryIdentifier = Category.item
                content.threadIdentifier = itemID.uuidString
                content.userInfo = [Key.itemID: itemID.uuidString]
            }

            let components = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: reminder.fireDate)
            let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
            try? await center.add(UNNotificationRequest(identifier: reminder.identifier, content: content, trigger: trigger))
        }
    }

    /// Clears already-delivered hourly reminders for an item the user has dealt with.
    func clearDelivered(for itemID: UUID) async {
        let delivered = await center.deliveredNotifications()
        let ids = delivered
            .filter { $0.request.content.userInfo[Key.itemID] as? String == itemID.uuidString }
            .map(\.request.identifier)
        center.removeDeliveredNotifications(withIdentifiers: ids)
    }

    // MARK: - UNUserNotificationCenterDelegate

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let action = response.actionIdentifier
        let rawID = response.notification.request.content.userInfo[Key.itemID] as? String
        guard let rawID, let itemID = UUID(uuidString: rawID) else { return }
        await handle(action: action, itemID: itemID)
    }

    private func handle(action: String, itemID: UUID) async {
        let context = Store.container.mainContext
        let descriptor = FetchDescriptor<GroceryItem>(predicate: #Predicate { $0.id == itemID })
        guard let item = try? context.fetch(descriptor).first else { return }

        switch action {
        case Action.used:
            item.status = .used
        case Action.discarded:
            item.status = .discarded
        case Action.snooze:
            item.snoozedUntil = Calendar.current.date(byAdding: .hour, value: Self.snoozeHours, to: Date())
        default:
            return // A plain tap just opens the app.
        }

        try? context.save()
        if !item.isActive {
            await clearDelivered(for: itemID)
        }
        await refresh(context: context)
    }
}
