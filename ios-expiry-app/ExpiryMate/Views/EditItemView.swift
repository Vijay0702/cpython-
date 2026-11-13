import SwiftData
import SwiftUI

/// Corrects the name or expiry date of a saved item.
struct EditItemView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    let item: GroceryItem
    @State private var name: String
    @State private var expiryDate: Date

    init(item: GroceryItem) {
        self.item = item
        _name = State(initialValue: item.name)
        _expiryDate = State(initialValue: item.expiryDate)
    }

    private var trimmedName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Product") {
                    TextField("Name", text: $name)
                    if let barcode = item.barcode {
                        LabeledContent("Barcode", value: barcode)
                    }
                }
                Section("Expiry") {
                    DatePicker("Expires on", selection: $expiryDate, displayedComponents: .date)
                }
            }
            .navigationTitle("Edit item")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save)
                        .disabled(trimmedName.isEmpty)
                }
            }
        }
    }

    private func save() {
        item.name = trimmedName
        item.expiryDate = Calendar.current.startOfDay(for: expiryDate)
        // A corrected date starts with a clean slate of reminders.
        item.snoozedUntil = nil
        try? modelContext.save()
        let context = modelContext
        Task { await ReminderScheduler.shared.refresh(context: context) }
        dismiss()
    }
}
