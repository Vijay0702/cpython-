import Foundation
import SwiftData

enum ItemStatus: String {
    case active
    case used
    case discarded
}

@Model
final class GroceryItem {
    var id: UUID
    var name: String
    var barcode: String?
    /// Always stored as the start of the expiry day.
    var expiryDate: Date
    var addedAt: Date
    var statusRaw: String
    /// Hourly reminders are suppressed until this moment.
    var snoozedUntil: Date?

    init(name: String, barcode: String?, expiryDate: Date) {
        self.id = UUID()
        self.name = name
        self.barcode = barcode
        self.expiryDate = Calendar.current.startOfDay(for: expiryDate)
        self.addedAt = Date()
        self.statusRaw = ItemStatus.active.rawValue
        self.snoozedUntil = nil
    }

    var status: ItemStatus {
        get { ItemStatus(rawValue: statusRaw) ?? .active }
        set { statusRaw = newValue.rawValue }
    }

    var isActive: Bool { status == .active }

    /// Whole days from today until the expiry day. Negative once expired.
    func daysLeft(from now: Date = Date(), calendar: Calendar = .current) -> Int {
        ExpiryText.daysBetween(now, expiryDate, calendar: calendar)
    }
}

enum ExpiryText {
    static func daysBetween(_ from: Date, _ to: Date, calendar: Calendar = .current) -> Int {
        let start = calendar.startOfDay(for: from)
        let end = calendar.startOfDay(for: to)
        return calendar.dateComponents([.day], from: start, to: end).day ?? 0
    }

    static func describe(daysLeft: Int) -> String {
        switch daysLeft {
        case ..<(-1): return "expired \(-daysLeft) days ago"
        case -1: return "expired yesterday"
        case 0: return "expires today"
        case 1: return "expires tomorrow"
        default: return "expires in \(daysLeft) days"
        }
    }
}
