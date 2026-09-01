import CoreLocation
import UIKit

final class SearchViewController: UIViewController {
  private let searchAPI = AMapSearchAPI()!
  private let locationManager = CLLocationManager()
  private let searchBar = UISearchBar()
  private let currentAddressLabel = UILabel()
  private let connectionLabel = UILabel()
  private let tableView = UITableView(frame: .zero, style: .insetGrouped)
  private var debounceWork: DispatchWorkItem?
  private var candidates: [Destination] = []
  private var latestLocation: CLLocation?
  private var lastReverseGeocodeLocation: CLLocation?

  override func viewDidLoad() {
    super.viewDidLoad()
    title = "TesNav"
    view.backgroundColor = .systemBackground
    searchAPI.delegate = self
    locationManager.delegate = self
    locationManager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
    locationManager.distanceFilter = 10
    configureUI()
    navigationItem.rightBarButtonItem = UIBarButtonItem(
      title: "设置",
      style: .plain,
      target: self,
      action: #selector(showSettings)
    )
    observeConnection()
    requestLocation()

    if (Bundle.main.object(forInfoDictionaryKey: "AMapAPIKey") as? String)?.isEmpty != false {
      currentAddressLabel.text = "未配置高德 iOS Key（Bundle ID: com.garan.tesnav.ios）"
      currentAddressLabel.textColor = .systemRed
    }
  }

  deinit {
    NotificationCenter.default.removeObserver(self)
    debounceWork?.cancel()
    searchAPI.cancelAllRequests()
  }

  private func configureUI() {
    searchBar.translatesAutoresizingMaskIntoConstraints = false
    searchBar.delegate = self
    searchBar.placeholder = "输入目的地，例如：上海虹桥站"
    searchBar.autocapitalizationType = .none
    searchBar.autocorrectionType = .no

    currentAddressLabel.translatesAutoresizingMaskIntoConstraints = false
    currentAddressLabel.font = .preferredFont(forTextStyle: .subheadline)
    currentAddressLabel.numberOfLines = 2
    currentAddressLabel.text = "正在获取当前位置…"

    connectionLabel.translatesAutoresizingMaskIntoConstraints = false
    connectionLabel.font = .preferredFont(forTextStyle: .caption1)
    connectionLabel.textColor = .secondaryLabel
    connectionLabel.numberOfLines = 2

    tableView.translatesAutoresizingMaskIntoConstraints = false
    tableView.dataSource = self
    tableView.delegate = self
    tableView.keyboardDismissMode = .onDrag
    tableView.register(UITableViewCell.self, forCellReuseIdentifier: "candidate")

    let header = UIStackView(arrangedSubviews: [searchBar, currentAddressLabel, connectionLabel])
    header.translatesAutoresizingMaskIntoConstraints = false
    header.axis = .vertical
    header.spacing = 8
    header.isLayoutMarginsRelativeArrangement = true
    header.layoutMargins = UIEdgeInsets(top: 8, left: 12, bottom: 4, right: 12)

    view.addSubview(header)
    view.addSubview(tableView)
    NSLayoutConstraint.activate([
      header.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
      header.leadingAnchor.constraint(equalTo: view.leadingAnchor),
      header.trailingAnchor.constraint(equalTo: view.trailingAnchor),
      tableView.topAnchor.constraint(equalTo: header.bottomAnchor),
      tableView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
      tableView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
      tableView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
    ])
  }

  private func observeConnection() {
    updateConnection(NavAssistClient.shared.status())
    NotificationCenter.default.addObserver(
      forName: .navAssistStatusChanged,
      object: nil,
      queue: .main
    ) { [weak self] notification in
      guard let status = notification.object as? NavAssistClientStatus else { return }
      self?.updateConnection(status)
    }
  }

  private func updateConnection(_ status: NavAssistClientStatus) {
    let device = status.deviceID.map { String($0.prefix(8)) + "…" } ?? "未发现"
    connectionLabel.text = "C3XL：\(status.detail) · 设备 \(device) · App \(status.appKeyID.prefix(8))…"
    connectionLabel.textColor = status.connection == .online ? .systemGreen : .secondaryLabel
  }

  private func requestLocation() {
    switch locationManager.authorizationStatus {
    case .notDetermined:
      locationManager.requestAlwaysAuthorization()
    case .authorizedAlways, .authorizedWhenInUse:
      locationManager.startUpdatingLocation()
    case .denied, .restricted:
      currentAddressLabel.text = "定位权限未开启，请到系统设置允许 TesNav 使用定位"
    @unknown default:
      currentAddressLabel.text = "无法读取定位权限状态"
    }
  }

  private func searchTips(keyword: String) {
    guard !keyword.isEmpty else {
      candidates = []
      tableView.reloadData()
      return
    }
    let request = AMapInputTipsSearchRequest()
    request.keywords = keyword
    request.cityLimit = false
    if let location = latestLocation {
      let coordinate = amapCoordinate(location)
      request.location = String(format: "%.6f,%.6f", coordinate.longitude, coordinate.latitude)
    }
    searchAPI.aMapInputTipsSearch(request)
  }

  private func resolvePOI(keyword: String) {
    guard !keyword.isEmpty else { return }
    let request = AMapPOIKeywordsSearchRequest()
    request.keywords = keyword
    request.cityLimit = false
    request.offset = 8
    request.page = 1
    request.sortrule = latestLocation == nil ? 1 : 0
    if let location = latestLocation {
      let coordinate = amapCoordinate(location)
      request.location = AMapGeoPoint.location(withLatitude: coordinate.latitude, longitude: coordinate.longitude)
    }
    searchAPI.aMapPOIKeywordsSearch(request)
  }

  private func reverseGeocode(_ location: CLLocation) {
    if let previous = lastReverseGeocodeLocation, location.distance(from: previous) < 30 { return }
    lastReverseGeocodeLocation = location
    let request = AMapReGeocodeSearchRequest()
    let coordinate = amapCoordinate(location)
    request.location = AMapGeoPoint.location(withLatitude: coordinate.latitude, longitude: coordinate.longitude)
    request.requireExtension = true
    searchAPI.aMapReGoecodeSearch(request)
  }

  private func destination(from tip: AMapTip) -> Destination? {
    guard let point = tip.location else { return nil }
    return Destination(
      name: tip.name.nonEmpty ?? "未命名地点",
      address: [tip.district.nonEmpty, tip.address.nonEmpty].compactMap { $0 }.joined(separator: " "),
      poiID: tip.uid.nonEmpty,
      latitude: Double(point.latitude),
      longitude: Double(point.longitude)
    )
  }

  private func destination(from poi: AMapPOI) -> Destination? {
    let point = poi.enterLocation ?? poi.location
    guard let point else { return nil }
    return Destination(
      name: poi.name.nonEmpty ?? "未命名地点",
      address: [poi.district.nonEmpty, poi.address.nonEmpty].compactMap { $0 }.joined(separator: " "),
      poiID: poi.uid.nonEmpty,
      latitude: Double(point.latitude),
      longitude: Double(point.longitude)
    )
  }

  private func amapCoordinate(_ location: CLLocation) -> CLLocationCoordinate2D {
    AMapCoordinateConvert(location.coordinate, .GPS)
  }

  @objc private func showSettings() {
    let status = NavAssistClient.shared.status()
    let message = "连接：\(status.detail)\n设备：\(status.deviceID.map { String($0.prefix(8)) + "…" } ?? "未发现")\nApp：\(status.appKeyID.prefix(8))…"
    let sheet = UIAlertController(title: "NavAssist 设置", message: message, preferredStyle: .actionSheet)
    let forget = UIAlertAction(title: "忘记已配对设备", style: .destructive) { _ in
      NavAssistClient.shared.clearPairing()
    }
    forget.isEnabled = NavAssistClient.shared.hasPairing()
    sheet.addAction(forget)
    sheet.addAction(UIAlertAction(title: "取消", style: .cancel))
    sheet.popoverPresentationController?.barButtonItem = navigationItem.rightBarButtonItem
    present(sheet, animated: true)
  }
}

extension SearchViewController: UISearchBarDelegate {
  func searchBar(_ searchBar: UISearchBar, textDidChange searchText: String) {
    debounceWork?.cancel()
    let keyword = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
    let work = DispatchWorkItem { [weak self] in self?.searchTips(keyword: keyword) }
    debounceWork = work
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3, execute: work)
  }

  func searchBarSearchButtonClicked(_ searchBar: UISearchBar) {
    searchBar.resignFirstResponder()
    resolvePOI(keyword: searchBar.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "")
  }
}

extension SearchViewController: UITableViewDataSource, UITableViewDelegate {
  func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { candidates.count }

  func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
    let cell = tableView.dequeueReusableCell(withIdentifier: "candidate", for: indexPath)
    let destination = candidates[indexPath.row]
    var configuration = cell.defaultContentConfiguration()
    configuration.text = destination.name
    configuration.secondaryText = destination.address
    configuration.secondaryTextProperties.numberOfLines = 2
    cell.contentConfiguration = configuration
    cell.accessoryType = .disclosureIndicator
    return cell
  }

  func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
    tableView.deselectRow(at: indexPath, animated: true)
    navigationController?.pushViewController(RoutePlanningViewController(destination: candidates[indexPath.row]), animated: true)
  }
}

extension SearchViewController: CLLocationManagerDelegate {
  func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) { requestLocation() }

  func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
    guard let location = locations.last, location.horizontalAccuracy >= 0 else { return }
    latestLocation = location
    let coordinate = amapCoordinate(location)
    currentAddressLabel.text = String(
      format: "当前位置：%.6f, %.6f（精度 %.0f 米）",
      coordinate.latitude,
      coordinate.longitude,
      location.horizontalAccuracy
    )
    reverseGeocode(location)
  }

  func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
    currentAddressLabel.text = "定位失败：\(error.localizedDescription)"
  }
}

extension SearchViewController: AMapSearchDelegate {
  func onInputTipsSearchDone(_ request: AMapInputTipsSearchRequest!, response: AMapInputTipsSearchResponse!) {
    let tips = response?.tips ?? []
    candidates = tips.compactMap(destination(from:))
    if candidates.isEmpty, let keyword = request?.keywords.nonEmpty { resolvePOI(keyword: keyword) }
    tableView.reloadData()
  }

  func onPOISearchDone(_ request: AMapPOISearchBaseRequest!, response: AMapPOISearchResponse!) {
    candidates = (response?.pois ?? []).prefix(8).compactMap(destination(from:))
    tableView.reloadData()
  }

  func onReGeocodeSearchDone(_ request: AMapReGeocodeSearchRequest!, response: AMapReGeocodeSearchResponse!) {
    if let address = response?.regeocode.formattedAddress.nonEmpty {
      currentAddressLabel.text = "当前位置：\(address)"
    }
  }

  func aMapSearchRequest(_ request: Any!, didFailWithError error: Error!) {
    let details = error as NSError
    if request is AMapReGeocodeSearchRequest {
      currentAddressLabel.text = AMapSearchFailureMessage.currentAddress(details)
    } else {
      candidates = []
      currentAddressLabel.text = AMapSearchFailureMessage.search(details)
      tableView.reloadData()
    }
  }
}

enum AMapSearchFailureMessage {
  static func currentAddress(_ error: NSError) -> String {
    "当前位置已获取，\(reason(error))"
  }

  static func search(_ error: NSError) -> String {
    "目的地搜索失败：\(reason(error))"
  }

  private static func reason(_ error: NSError) -> String {
    switch error.code {
    case 1002:
      "高德 Key 非法或已过期（1002）"
    case 1003:
      "高德 Key 没有开通搜索服务（1003）"
    case 1004:
      "高德 Key 今日调用量已用完（1004）"
    case 1005:
      "高德请求过于频繁，请稍后重试（1005）"
    case 1008:
      "高德 iOS Key 与 Bundle ID com.garan.tesnav.ios 不匹配（1008）"
    case 1009:
      "高德 Key 绑定的平台不是 iOS（1009）"
    case 1102, 1103, 1802:
      "连接高德服务超时（\(error.code)）"
    case 1804, 1805, 1806:
      "无法连接高德服务，请检查手机网络（\(error.code)）"
    default:
      "高德服务错误 \(error.domain)/\(error.code)：\(error.localizedDescription)"
    }
  }
}

private extension String {
  var nonEmpty: String? { isEmpty ? nil : self }
}
