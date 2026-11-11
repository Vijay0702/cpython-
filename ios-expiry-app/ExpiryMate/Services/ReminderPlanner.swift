import Foundation

struct ReminderSettings {
    var digestHour: Int
    var digestMinute: Int
    /// Hourly reminders on the day before expiry run from this hour...
    var hourlyStartHour: Int
    /// ...to this hour, inclusive.
    var hourlyEndHour: Int

    enum Key {
        static let digestHour = "digestHour"
        static let digestMinute = "digestMinute"
        static let hourlyStartHour = "hourlyStartHour"
        static let hourlyEndHour = "hourlyEndHour"
    }

    static let defaults = ReminderSettings(digestHour: 9, digestMinute: 0, hourlyStartHour: 8, hourlyEndHour: 21)

    static func load(from store: UserDefaults = .standard) -> ReminderSettings {
        func value(_ key: String, _ fallback: Int) -> Int {
            store.object(forKey: key) == nil ? fallback : store.integer(forKey: key)
        }
        return ReminderSettings(
            digestHour: value(Key.digestHour, defaults.digestHour),
            digestMinute: value(Key.digestMinute, defaults.digestMinute),
            hourlyStartHour: value(Key.hourlyStartHour, defaults.hourlyStartHour),
            hourlyEndHour: value(Key.hourlyEndHour, defaults.hourlyEndHour)
        )
    }
}

/// A plain copy of the fields the planner needs, so planning does not touch SwiftData.
struct ItemSnapshot {
    var id: UUID
    var name: String
    var expiryDate: Date
    var snoozedUntil: Date?
}

struct PlannedReminder {
    enum Kind {
        case digest
        case hourly(itemID: UUID)
    }

    var identifier: String
    var fireDate: Date
    var title: String
    var body: String
    var kind: Kind
}

/// Works out which notifications should be pending right now.
///
/// - Every day: one digest at the chosen time listing what is expiring.
/// - The day before an item expires: one reminder per hour for that item,
///   until the user marks it as used or thrown away.
///
/// iOS keeps at most 64 pending local notifications per app, so the plan is
/// sorted by time and cut to the soonest `limit`. It is rebuilt every time the
/// app opens or an item changes, which keeps the window rolling forward.
enum ReminderPlanner {
    static let limit = 60
    static let horizonDays = 30

    static func plan(
        items: [ItemSnapshot],
        settings: ReminderSettings,
        now: Date = Date(),
        calendar: Calendar = .current
    ) -> [PlannedReminder] {
        guard !items.isEmpty else { return [] }

        let today = calendar.startOfDay(for: now)
        let sorted = items.sorted { $0.expiryDate < $1.expiryDate }
        var reminders: [PlannedReminder] = []

        for offset in 0..<horizonDays {
            guard let day = calendar.date(byAdding: .day, value: offset, to: today) else { continue }
            let stamp = dayStamp(day, calendar: calendar)

            if let fire = calendar.date(bySettingHour: settings.digestHour, minute: settings.digestMinute, second: 0, of: day),
               fire > now {
                reminders.append(digest(for: sorted, on: day, fireDate: fire, stamp: stamp, calendar: calendar))
            }

            let expiringTomorrow = sorted.filter { ExpiryText.daysBetween(day, $0.expiryDate, calendar: calendar) == 1 }
            guard !expiringTomorrow.isEmpty, settings.hourlyStartHour <= settings.hourlyEndHour else { continue }

            for hour in settings.hourlyStartHour...settings.hourlyEndHour {
                guard let fire = calendar.date(bySettingHour: hour, minute: 0, second: 0, of: day), fire > now else { continue }
                for item in expiringTomorrow {
                    if let snoozedUntil = item.snoozedUntil, fire < snoozedUntil { continue }
                    reminders.append(PlannedReminder(
                        identifier: "hourly-\(item.id.uuidString)-\(stamp)-\(hour)",
                        fireDate: fire,
                        title: "\(item.name) expires tomorrow",
                        body: "Use it today. Mark it as used or thrown away to stop these reminders.",
                        kind: .hourly(itemID: item.id)
                    ))
                }
            }
        }

        return Array(reminders.sorted { $0.fireDate < $1.fireDate }.prefix(limit))
    }

    private static func digest(
        for sorted: [ItemSnapshot],
        on day: Date,
        fireDate: Date,
        stamp: String,
        calendar: Calendar
    ) -> PlannedReminder {
        let lines = sorted.prefix(4).map { item in
            let days = ExpiryText.daysBetween(day, item.expiryDate, calendar: calendar)
            return "\(item.name) \(ExpiryText.describe(daysLeft: days))"
        }
        let extra = sorted.count - lines.count
        let body = lines.joined(separator: "\n") + (extra > 0 ? "\n+ \(extra) more" : "")
        let title = sorted.count == 1 ? "1 item to keep an eye on" : "\(sorted.count) items to keep an eye on"

        return PlannedReminder(identifier: "digest-\(stamp)", fireDate: fireDate, title: title, body: body, kind: .digest)
    }

    private static func dayStamp(_ day: Date, calendar: Calendar) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: day)
        return String(format: "%04d%02d%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }
}
