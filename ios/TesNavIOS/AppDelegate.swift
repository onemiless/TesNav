import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool { true }

  func application(
    _ application: UIApplication,
    configurationForConnecting connectingSceneSession: UISceneSession,
    options: UIScene.ConnectionOptions
  ) -> UISceneConfiguration {
    let configuration = UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
    configuration.delegateClass = SceneDelegate.self
    return configuration
  }
}

enum AppBootstrap {
  static let privacyKey = "tesnav.amap.privacy-consent.v1"

  static func configureAMap() {
    AMapSearchAPI.updatePrivacyShow(.didShow, privacyInfo: .didContain)
    AMapSearchAPI.updatePrivacyAgree(.didAgree)
    AMapNaviManagerConfig.shared().updatePrivacyShow(.didShow, privacyInfo: .didContain)
    AMapNaviManagerConfig.shared().updatePrivacyAgree(.didAgree)
    MAMapView.updatePrivacyShow(.didShow, privacyInfo: .didContain)
    MAMapView.updatePrivacyAgree(.didAgree)
    AMapServices.shared().enableHTTPS = true
    AMapServices.shared().apiKey = Bundle.main.object(forInfoDictionaryKey: "AMapAPIKey") as? String ?? ""
    NavAssistClient.shared.start()
  }

  static func mainController() -> UIViewController {
    UINavigationController(rootViewController: SearchViewController())
  }
}
