import SwiftData
import SwiftUI
import UIKit

struct ItemListView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.scenePhase) private var scenePhase
    @Query(sort: \GroceryItem.expiryDate) private var allItems: [GroceryItem]

    @State private var isAdding = false
    @State private var isShowingSettings = false
    @State private var notificationsDenied = false

    private var items: [GroceryItem] { allItems.filter(\.isActive) }
    private var expired: [GroceryItem] { items.filter { $0.daysLeft() < 0 } }
    private var soon: [GroceryItem] { items.filter { (0...3).contains($0.daysLeft()) } }
    private var later: [GroceryItem] { items.filter { $0.daysLeft() > 3 } }

    var body: some View {
        NavigationStack {
            List {
                if notificationsDenied {
                    Section {
                        Label("Notifications are turned off for this app. Turn them on in Settings to get reminders.",
                              systemImage: "bell.slash")
                            .foregroundStyle(.secondary)
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            Link("Open Settings", destination: url)
                        }
                    }
                }
                section("Expired", expired)
                section("Expiring soon", soon)
                section("Fresh", later)
            }
            .overlay {
                if items.isEmpty {
                    ContentUnavailableView(
                        "No items yet",
                        systemImage: "barcode.viewfinder",
                        description: Text("Scan a grocery item to start tracking its expiry date.")
                    )
                }
            }
            .navigationTitle("ExpiryMate")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { isShowingSettings = true } label: {
                        Label("Reminder settings", systemImage: "gearshape")
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button { isAdding = true } label: {
                        Label("Scan item", systemImage: "barcode.viewfinder")
                    }
                }
            }
            .sheet(isPresented: $isAdding) { AddItemView() }
            .sheet(isPresented: $isShowingSettings, onDismiss: refreshReminders) { SettingsView() }
        }
        .onChange(of: scenePhase, initial: true) { _, phase in
            if phase == .active {
                refreshReminders()
            }
        }
    }

    @ViewBuilder
    private func section(_ title: String, _ sectionItems: [GroceryItem]) -> some View {
        if !sectionItems.isEmpty {
            Section(title) {
                ForEach(sectionItems) { item in
                    ItemRow(item: item)
                        .swipeActions(edge: .trailing) {
                            Button("Thrown away") { resolve(item, as: .discarded) }
                                .tint(.red)
                            Button("Used") { resolve(item, as: .used) }
                                .tint(.green)
                        }
                }
            }
        }
    }

    private func resolve(_ item: GroceryItem, as status: ItemStatus) {
        let itemID = item.id
        item.status = status
        try? modelContext.save()
        Task { await ReminderScheduler.shared.clearDelivered(for: itemID) }
        refreshReminders()
    }

    private func refreshReminders() {
        let context = modelContext
        Task {
            await ReminderScheduler.shared.refresh(context: context)
            notificationsDenied = await ReminderScheduler.shared.isDenied()
        }
    }
}

private struct ItemRow: View {
    let item: GroceryItem

    var body: some View {
        let days = item.daysLeft()
        HStack(spacing: 12) {
            Circle()
                .fill(color(for: days))
                .frame(width: 12, height: 12)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name)
                    .font(.headline)
                Text(ExpiryText.describe(daysLeft: days).capitalizedFirst)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Text(item.expiryDate.formatted(date: .abbreviated, time: .omitted))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }

    private func color(for days: Int) -> Color {
        if days < 0 { return .red }
        if days <= 3 { return .orange }
        return .green
    }
}

private extension String {
    var capitalizedFirst: String {
        prefix(1).uppercased() + dropFirst()
    }
}
