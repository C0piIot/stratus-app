import SwiftUI
import StratusUI

// The whole Swift side of the app, and it should stay close to this size. The
// interface is Compose Multiplatform and the decisions are in shared Kotlin;
// what lives here is the bridge and nothing else.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ viewController: UIViewController, context: Context) {}
}

@main
struct StratusApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
        }
    }
}
