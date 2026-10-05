import SwiftUI
import UIKit
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

/// The door a background transfer comes back through (stratus-app#20).
///
/// A background `URLSession` finishes with the app suspended or killed and
/// relaunches it to report. The system gives a completion handler that has to
/// be called once the reporting is done, and will complain if it is not — so
/// this holds it, wakes the session by touching the container, and lets go
/// when the session says it has finished talking.
///
/// Everything it does is bookkeeping. What any of it *means* is decided in
/// shared Kotlin, which is the rule the whole backup is built on.
class AppDelegate: NSObject, UIApplicationDelegate {

    /// Registering has to happen before launching finishes: the system calls
    /// the handler immediately for a task it was already holding, and refuses
    /// one registered late.
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        BackupTasks.shared.register()
        BackupTasks.shared.schedule()
        return true
    }

    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        // Building the container is what creates the session with this
        // identifier, which is what makes the system deliver what it has been
        // holding.
        let session = AppContainerFactoryKt.uploadSession
        session?.onEventsDelivered = { completionHandler() }
        if session == nil {
            completionHandler()
        }
    }
}

@main
struct StratusApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate

    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
        }
    }
}
