import SwiftUI

/// Reminder times. The list screen rebuilds the notification schedule when this sheet closes.
struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss

    @AppStorage(ReminderSettings.Key.digestHour) private var digestHour = ReminderSettings.defaults.digestHour
    @AppStorage(ReminderSettings.Key.digestMinute) private var digestMinute = ReminderSettings.defaults.digestMinute
    @AppStorage(ReminderSettings.Key.hourlyStartHour) private var hourlyStartHour = ReminderSettings.defaults.hourlyStartHour
    @AppStorage(ReminderSettings.Key.hourlyEndHour) private var hourlyEndHour = ReminderSettings.defaults.hourlyEndHour

    private var digestTime: Binding<Date> {
        Binding(
            get: {
                Calendar.current.date(bySettingHour: digestHour, minute: digestMinute, second: 0, of: Date()) ?? Date()
            },
            set: { newValue in
                let components = Calendar.current.dateComponents([.hour, .minute], from: newValue)
                digestHour = components.hour ?? digestHour
                digestMinute = components.minute ?? digestMinute
            }
        )
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker("Time", selection: digestTime, displayedComponents: .hourAndMinute)
                } header: {
                    Text("Daily summary")
                } footer: {
                    Text("One notification every day listing the items closest to expiry.")
                }

                Section {
                    Picker("From", selection: $hourlyStartHour) {
                        ForEach(0..<24, id: \.self) { Text(hourLabel($0)).tag($0) }
                    }
                    Picker("Until", selection: $hourlyEndHour) {
                        ForEach(0..<24, id: \.self) { Text(hourLabel($0)).tag($0) }
                    }
                } header: {
                    Text("Day before expiry")
                } footer: {
                    Text("A reminder every hour between these times, until you mark the item as used or thrown away.")
                }
            }
            .navigationTitle("Reminders")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onChange(of: hourlyStartHour) { _, start in
                if start > hourlyEndHour { hourlyEndHour = start }
            }
            .onChange(of: hourlyEndHour) { _, end in
                if end < hourlyStartHour { hourlyStartHour = end }
            }
        }
    }

    private func hourLabel(_ hour: Int) -> String {
        let date = Calendar.current.date(bySettingHour: hour, minute: 0, second: 0, of: Date()) ?? Date()
        return date.formatted(date: .omitted, time: .shortened)
    }
}
