import Foundation

/// Finds the most likely expiry date in text read off a package.
///
/// Handles numeric dates (12/01/2027, 12-01-27, 2027-01-12, 01/2027) and
/// month-name dates (12 JAN 2027, JAN 2027, JAN 12, 2027). When several dates
/// are printed, one that follows "EXP", "BEST BEFORE" or "USE BY" wins, one
/// that follows "MFG" or "PKD" loses, and otherwise the latest date wins.
enum ExpiryDateParser {

    static func parse(
        _ text: String,
        now: Date = Date(),
        calendar: Calendar = .current,
        dayFirst: Bool = ExpiryDateParser.localeIsDayFirst
    ) -> Date? {
        let upper = text.uppercased()
        let thisYear = calendar.component(.year, from: now)
        var candidates: [(date: Date, score: Int, range: NSRange, hasDay: Bool)] = []

        for pattern in patterns {
            let range = NSRange(upper.startIndex..., in: upper)
            for match in pattern.regex.matches(in: upper, range: range) {
                var groups: [String] = []
                for index in 1..<match.numberOfRanges {
                    if let r = Range(match.range(at: index), in: upper) {
                        groups.append(String(upper[r]))
                    } else {
                        groups.append("")
                    }
                }
                guard
                    let parts = pattern.build(groups, dayFirst),
                    let date = makeDate(parts, calendar: calendar),
                    (thisYear - 2...thisYear + 15).contains(parts.year)
                else { continue }

                let context = preceding(upper, match.range, length: 24)
                candidates.append((date, score(for: context), match.range, parts.day != nil))
            }
        }

        // "12 01 2027" also contains "01 2027". Where a month-only reading overlaps
        // a full date, the full date is the right one.
        let fullDates = candidates.filter { $0.hasDay }
        candidates.removeAll { candidate in
            !candidate.hasDay && fullDates.contains { NSIntersectionRange($0.range, candidate.range).length > 0 }
        }

        return candidates.max { a, b in
            a.score != b.score ? a.score < b.score : a.date < b.date
        }?.date
    }

    /// True when the user's region writes the day before the month.
    static var localeIsDayFirst: Bool {
        let format = DateFormatter.dateFormat(fromTemplate: "dMy", options: 0, locale: .current) ?? "dMy"
        guard let d = format.firstIndex(of: "d"), let m = format.firstIndex(of: "M") else { return true }
        return d < m
    }

    // MARK: - Internals

    private struct Parts {
        var year: Int
        var month: Int
        /// nil means "end of the month" (packages printed as MM/YYYY).
        var day: Int?
    }

    private struct Pattern {
        let regex: NSRegularExpression
        let build: ([String], Bool) -> Parts?

        init(_ pattern: String, _ build: @escaping ([String], Bool) -> Parts?) {
            // Patterns are compile-time constants, so a failure is a programmer error.
            self.regex = try! NSRegularExpression(pattern: pattern)
            self.build = build
        }
    }

    private static let monthPrefixes = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"]
    // Full names are spelled out so that words like "DECAF" or "MARKET" are not read as months.
    private static let monthNames = "JAN(?:UARY)?|FEB(?:RUARY)?|MAR(?:CH)?|APR(?:IL)?|MAY|JUNE?|JULY?|AUG(?:UST)?"
        + "|SEP(?:T(?:EMBER)?)?|OCT(?:OBER)?|NOV(?:EMBER)?|DEC(?:EMBER)?"
    private static let sep = "[\\s./-]"
    // Lookarounds stop a pattern matching inside a longer run of digits or letters.
    private static let lead = "(?<![0-9A-Z])"
    private static let trail = "(?![0-9A-Z])"

    private static let patterns: [Pattern] = [
        // 2027-01-12
        Pattern("\(lead)(20\\d{2})\(sep)(\\d{1,2})\(sep)(\\d{1,2})\(trail)") { g, _ in
            guard let y = Int(g[0]), let m = Int(g[1]), let d = Int(g[2]) else { return nil }
            return Parts(year: y, month: m, day: d)
        },
        // 12/01/2027, 12-01-27
        Pattern("\(lead)(\\d{1,2})\(sep)(\\d{1,2})\(sep)(\\d{4}|\\d{2})\(trail)") { g, dayFirst in
            guard let a = Int(g[0]), let b = Int(g[1]), let y = Int(g[2]) else { return nil }
            let day: Int, month: Int
            if a > 12 { day = a; month = b }
            else if b > 12 { day = b; month = a }
            else if dayFirst { day = a; month = b }
            else { day = b; month = a }
            return Parts(year: fullYear(y), month: month, day: day)
        },
        // 01/2027, 01-27 (no day: valid to the end of the month)
        Pattern("(?<![0-9A-Z./-])(\\d{1,2})[./-](\\d{4}|\\d{2})(?![0-9./-])") { g, _ in
            guard let m = Int(g[0]), let y = Int(g[1]) else { return nil }
            return Parts(year: fullYear(y), month: m, day: nil)
        },
        // 11 2027 (month and four-digit year separated only by a space)
        Pattern("\(lead)(0?[1-9]|1[0-2])\\s+(20\\d{2})\(trail)") { g, _ in
            guard let m = Int(g[0]), let y = Int(g[1]) else { return nil }
            return Parts(year: y, month: m, day: nil)
        },
        // 12 JAN 2027, 12-JAN-27, 12JAN2027
        Pattern("\(lead)(\\d{1,2})\(sep)?(\(monthNames))\(sep)?(\\d{4}|\\d{2})\(trail)") { g, _ in
            guard let d = Int(g[0]), let m = monthNumber(g[1]), let y = Int(g[2]) else { return nil }
            return Parts(year: fullYear(y), month: m, day: d)
        },
        // JAN 12, 2027
        Pattern("\(lead)(\(monthNames))\(sep)?(\\d{1,2}),?\\s+(\\d{4})\(trail)") { g, _ in
            guard let m = monthNumber(g[0]), let d = Int(g[1]), let y = Int(g[2]) else { return nil }
            return Parts(year: y, month: m, day: d)
        },
        // JAN 2027, JAN-27 (no day)
        Pattern("\(lead)(?<![0-9]\(sep))(\(monthNames))\(sep)?(\\d{4}|\\d{2})\(trail)(?!,?\\s+[0-9]{4})") { g, _ in
            guard let m = monthNumber(g[0]), let y = Int(g[1]) else { return nil }
            return Parts(year: fullYear(y), month: m, day: nil)
        },
    ]

    private static func fullYear(_ year: Int) -> Int {
        year < 100 ? 2000 + year : year
    }

    private static func monthNumber(_ name: String) -> Int? {
        monthPrefixes.firstIndex(of: String(name.prefix(3))).map { $0 + 1 }
    }

    private static func makeDate(_ parts: Parts, calendar: Calendar) -> Date? {
        guard (1...12).contains(parts.month) else { return nil }
        var components = DateComponents(year: parts.year, month: parts.month, day: 1)
        guard let firstOfMonth = calendar.date(from: components),
              let daysInMonth = calendar.range(of: .day, in: .month, for: firstOfMonth)?.count
        else { return nil }

        let day = parts.day ?? daysInMonth
        guard (1...daysInMonth).contains(day) else { return nil }
        components.day = day
        return calendar.date(from: components)
    }

    private static func preceding(_ text: String, _ range: NSRange, length: Int) -> String {
        let start = max(0, range.location - length)
        let contextRange = NSRange(location: start, length: range.location - start)
        guard let r = Range(contextRange, in: text) else { return "" }
        return String(text[r])
    }

    private static let expiryWords = ["EXP", "BEST", "BB", "USE BY", "USE-BY", "BEFORE", "E:"]
    private static let manufactureWords = ["MFG", "MFD", "PKD", "PACK", "PROD", "MANUF", "M:"]

    /// Only the keyword closest to the date counts, so "MFG 01/2026 EXP 01/2027"
    /// scores each date by its own label.
    private static func score(for context: String) -> Int {
        func lastPosition(of words: [String]) -> Int {
            words.compactMap { word in
                context.range(of: word, options: .backwards)
                    .map { context.distance(from: context.startIndex, to: $0.lowerBound) }
            }.max() ?? -1
        }
        let expiry = lastPosition(of: expiryWords)
        let manufacture = lastPosition(of: manufactureWords)
        if expiry < 0 && manufacture < 0 { return 0 }
        return expiry > manufacture ? 1 : -1
    }
}
