import SwiftUI
import ContactsUI

struct ContactPickerView: UIViewControllerRepresentable {
    var onSelectPhone: (String, String) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onSelectPhone: onSelectPhone)
    }

    func makeUIViewController(context: Context) -> CNContactPickerViewController {
        let picker = CNContactPickerViewController()
        picker.delegate = context.coordinator
        picker.displayedPropertyKeys = [CNContactPhoneNumbersKey]
        return picker
    }

    func updateUIViewController(_ uiViewController: CNContactPickerViewController, context: Context) {}

    final class Coordinator: NSObject, CNContactPickerDelegate {
        let onSelectPhone: (String, String) -> Void

        init(onSelectPhone: @escaping (String, String) -> Void) {
            self.onSelectPhone = onSelectPhone
        }

        func contactPicker(_ picker: CNContactPickerViewController, didSelect contactProperty: CNContactProperty) {
            if let num = (contactProperty.value as? CNPhoneNumber)?.stringValue {
                let name = CNContactFormatter.string(from: contactProperty.contact, style: .fullName) ?? num
                onSelectPhone(num, name)
            }
        }

        func contactPicker(_ picker: CNContactPickerViewController, didSelect contact: CNContact) {
            if let first = contact.phoneNumbers.first?.value.stringValue {
                let name = CNContactFormatter.string(from: contact, style: .fullName) ?? first
                onSelectPhone(first, name)
            }
        }
    }
}
