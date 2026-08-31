import UIKit

final class NavigationViewController: UIViewController {
  private let destination: Destination
  private let manager = AMapNaviDriveManager.sharedInstance()
  private let driveView = AMapNaviDriveView()
  private let statusLabel = UILabel()
  private let speechButton = UIButton(type: .system)
  private var telemetry: NavigationTelemetry?
  private var started = false
  private var speechPaused = false

  init(destination: Destination) {
    self.destination = destination
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
    if manager.startGPSNavi() {
      statusLabel.text = "实时导航中 · 语音播报已开启"
    } else {
      statusLabel.text = "实时导航启动失败，请重新规划路线"
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
    manager.allowsBackgroundLocationUpdates = true
    manager.pausesLocationUpdatesAutomatically = false
    manager.addDataRepresentative(driveView)
    manager.addEventListener(self)
    telemetry = NavigationTelemetry(manager: manager)
    driveView.autoZoomMapLevel = true
    driveView.showGreyAfterPass = true
    driveView.trackingMode = .carNorth
  }

  private func configureUI() {
    driveView.translatesAutoresizingMaskIntoConstraints = false
    statusLabel.translatesAutoresizingMaskIntoConstraints = false
    statusLabel.backgroundColor = UIColor.black.withAlphaComponent(0.62)
    statusLabel.textColor = .white
    statusLabel.font = .preferredFont(forTextStyle: .subheadline)
    statusLabel.textAlignment = .center
    statusLabel.numberOfLines = 2
    statusLabel.text = "正在启动实时导航…"

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

    let controls = UIStackView(arrangedSubviews: [speechButton, stopButton])
    controls.translatesAutoresizingMaskIntoConstraints = false
    controls.axis = .horizontal
    controls.spacing = 12
    controls.distribution = .fillEqually

    view.addSubview(driveView)
    view.addSubview(statusLabel)
    view.addSubview(controls)
    NSLayoutConstraint.activate([
      driveView.topAnchor.constraint(equalTo: view.topAnchor),
      driveView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
      driveView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
      driveView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
      statusLabel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
      statusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
      statusLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
      statusLabel.heightAnchor.constraint(greaterThanOrEqualToConstant: 42),
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
      statusLabel.text = "实时导航中 · 语音已暂停"
    } else {
      manager.resumeNaviSpeech()
      speechButton.configuration?.title = "静音"
      statusLabel.text = "实时导航中 · 语音播报已开启"
    }
  }

  @objc private func stopNavigation() {
    _ = manager.stopNavi()
    NavigationStateStore.shared.stop()
    navigationController?.popToRootViewController(animated: true)
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
    statusLabel.text = "已到达目的地"
    speechButton.isEnabled = false
  }

  func driveManager(_ driveManager: AMapNaviDriveManager, onCalculateRouteFailure error: Error) {
    statusLabel.text = "重新规划路线失败：\(error.localizedDescription)"
  }
}
