import UIKit

enum NavigationStartMode {
  case realtime
  case simulation
}

final class NavigationViewController: UIViewController {
  private let destination: Destination
  private let startMode: NavigationStartMode
  private let manager = AMapNaviDriveManager.sharedInstance()
  private let driveView = AMapNaviDriveView()
  private let speechButton = UIButton(type: .system)
  private var telemetry: NavigationTelemetry?
  private var started = false
  private var speechPaused = false
  private var simulationPaused = false
  private let simulationButton = UIButton(type: .system)

  init(destination: Destination, startMode: NavigationStartMode = .realtime) {
    self.destination = destination
    self.startMode = startMode
    super.init(nibName: nil, bundle: nil)
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

  override func viewDidLoad() {
    super.viewDidLoad()
    title = destination.name
    view.backgroundColor = .black
    navigationItem.hidesBackButton = true
    configureUI()
    configureNavigation()
  }

  override func viewDidAppear(_ animated: Bool) {
    super.viewDidAppear(animated)
    guard !started else { return }
    started = true
    let accepted = startMode == .realtime ? manager.startGPSNavi() : manager.startEmulatorNavi()
    if !accepted {
      NavigationStateStore.shared.navigationStartFailed()
      showStartFailure()
    }
  }

  deinit {
    manager.removeDataRepresentative(driveView)
    manager.removeEventListener(self)
  }

  private func configureNavigation() {
    manager.isUseInternalTTS = true
    manager.isUseTextPlay = true
    manager.resumeNaviSpeech()
    manager.allowsBackgroundLocationUpdates = true
    manager.pausesLocationUpdatesAutomatically = false
    manager.addDataRepresentative(driveView)
    manager.addEventListener(self)
    telemetry = NavigationTelemetry(manager: manager)
    driveView.delegate = self
    driveView.autoZoomMapLevel = true
    driveView.showGreyAfterPass = true
    driveView.trackingMode = .carNorth
  }

  private func configureUI() {
    driveView.translatesAutoresizingMaskIntoConstraints = false

    let stopButton = UIButton(type: .system)
    stopButton.translatesAutoresizingMaskIntoConstraints = false
    var stopConfiguration = UIButton.Configuration.filled()
    stopConfiguration.title = "结束导航"
    stopConfiguration.baseBackgroundColor = .systemRed
    stopButton.configuration = stopConfiguration
    stopButton.addTarget(self, action: #selector(stopNavigation), for: .touchUpInside)

    speechButton.translatesAutoresizingMaskIntoConstraints = false
    var speechConfiguration = UIButton.Configuration.filled()
    speechConfiguration.title = "静音"
    speechButton.configuration = speechConfiguration
    speechButton.addTarget(self, action: #selector(toggleSpeech), for: .touchUpInside)

    simulationButton.translatesAutoresizingMaskIntoConstraints = false
    var simulationConfiguration = UIButton.Configuration.filled()
    simulationConfiguration.title = "暂停"
    simulationConfiguration.baseBackgroundColor = .systemIndigo
    simulationButton.configuration = simulationConfiguration
    simulationButton.addTarget(self, action: #selector(toggleSimulation), for: .touchUpInside)
    simulationButton.isHidden = startMode != .simulation

    let controls = UIStackView(arrangedSubviews: [speechButton, simulationButton, stopButton])
    controls.translatesAutoresizingMaskIntoConstraints = false
    controls.axis = .horizontal
    controls.spacing = 12
    controls.distribution = .fillEqually

    view.addSubview(driveView)
    view.addSubview(controls)
    NSLayoutConstraint.activate([
      driveView.topAnchor.constraint(equalTo: view.topAnchor),
      driveView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
      driveView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
      driveView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
      controls.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
      controls.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
      controls.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -12),
      controls.heightAnchor.constraint(equalToConstant: 48),
    ])
  }

  @objc private func toggleSpeech() {
    speechPaused.toggle()
    if speechPaused {
      manager.pauseNaviSpeech()
      speechButton.configuration?.title = "恢复语音"
    } else {
      manager.resumeNaviSpeech()
      speechButton.configuration?.title = "静音"
    }
  }

  @objc private func toggleSimulation() {
    guard startMode == .simulation else { return }
    simulationPaused.toggle()
    if simulationPaused {
      manager.pauseNavi()
      simulationButton.configuration?.title = "继续"
    } else {
      manager.resumeNavi()
      simulationButton.configuration?.title = "暂停"
    }
  }

  @objc private func stopNavigation() {
    finishNavigation()
  }

  private func finishNavigation() {
    _ = manager.stopNavi()
    NavigationStateStore.shared.stop()
    navigationController?.popToRootViewController(animated: true)
  }

  private func showNavigationSettings() {
    guard presentedViewController == nil else { return }
    let sheet = UIAlertController(title: "导航设置", message: nil, preferredStyle: .actionSheet)
    sheet.addAction(UIAlertAction(title: speechPaused ? "恢复语音" : "静音", style: .default) { [weak self] _ in
      self?.toggleSpeech()
    })
    sheet.addAction(UIAlertAction(
      title: driveView.autoZoomMapLevel ? "关闭自动缩放" : "开启自动缩放",
      style: .default
    ) { [weak self] _ in
      guard let self else { return }
      self.driveView.autoZoomMapLevel.toggle()
    })
    sheet.addAction(UIAlertAction(title: "取消", style: .cancel))
    sheet.popoverPresentationController?.sourceView = driveView
    sheet.popoverPresentationController?.sourceRect = CGRect(x: driveView.bounds.midX, y: driveView.bounds.midY, width: 1, height: 1)
    present(sheet, animated: true)
  }

  private func showStartFailure() {
    let alert = UIAlertController(title: "无法开始导航", message: "高德导航没有接受当前路线，请返回重新规划。", preferredStyle: .alert)
    alert.addAction(UIAlertAction(title: "返回", style: .default) { [weak self] _ in
      self?.navigationController?.popViewController(animated: true)
    })
    present(alert, animated: true)
  }
}

extension NavigationViewController: AMapNaviDriveManagerDelegate {
  func driveManager(onArrivedDestination driveManager: AMapNaviDriveManager) {
    title = "已到达目的地"
    speechButton.isEnabled = false
  }

  func driveManager(_ driveManager: AMapNaviDriveManager, onCalculateRouteFailure error: Error) {
    title = "路线重算失败"
  }
}

extension NavigationViewController: AMapNaviDriveViewDelegate {
  func driveViewCloseButtonClicked(_ driveView: AMapNaviDriveView) {
    finishNavigation()
  }

  func driveViewMoreButtonClicked(_ driveView: AMapNaviDriveView) {
    showNavigationSettings()
  }
}
