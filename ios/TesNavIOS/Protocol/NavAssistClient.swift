import Foundation

extension Notification.Name {
  static let navAssistStatusChanged = Notification.Name("NavAssistStatusChanged")
}

struct NavAssistClientStatus {
  enum Connection: String {
    case stopped
    case scanning
    case discovered
    case online
    case error
  }

  let connection: Connection
  let endpoint: String?
  let deviceID: String?
  let appKeyID: String
  let detail: String
}

final class NavAssistClient {
  static let shared = NavAssistClient()

  private let queue = DispatchQueue(label: "com.garan.tesnav.navassist", qos: .utility)
  private let identity = NavAssistIdentity.shared
  private let session = NavAssistSession()
  private let stateStore = NavigationStateStore.shared
  private let discovery: NavAssistDiscovery
  private let lock = NSLock()
  private var running = false
  private var discoveryResetRequested = false
  private var currentStatus: NavAssistClientStatus

  private init() {
    discovery = NavAssistDiscovery(identity: identity)
    currentStatus = NavAssistClientStatus(
      connection: .stopped,
      endpoint: nil,
      deviceID: nil,
      appKeyID: identity.keyID,
      detail: "等待启动"
    )
  }

  func start() {
    lock.lock()
    guard !running else { lock.unlock(); return }
    running = true
    lock.unlock()
    queue.async { [weak self] in self?.runLoop() }
  }

  func stop() {
    lock.lock()
    running = false
    lock.unlock()
    publish(.stopped, endpoint: nil, deviceID: nil, detail: "已停止")
  }

  func status() -> NavAssistClientStatus {
    lock.lock()
    defer { lock.unlock() }
    return currentStatus
  }

  func clearPairing() {
    discovery.clearPairing()
    lock.lock()
    discoveryResetRequested = true
    lock.unlock()
    publish(.scanning, endpoint: nil, deviceID: nil, detail: "已忘记配对，正在重新扫描")
  }

  func hasPairing() -> Bool { discovery.hasPairing() }

  private func runLoop() {
    var endpoint: NavAssistEndpoint?
    var failures = 0
    while isRunning {
      if consumeDiscoveryReset() {
        endpoint = nil
        failures = 0
      }
      if endpoint == nil {
        publish(.scanning, endpoint: nil, deviceID: nil, detail: "正在扫描同一局域网内的 C3XL")
        do {
          endpoint = try discovery.discover()
          failures = 0
          if let endpoint {
            publish(.discovered, endpoint: endpoint.url.absoluteString, deviceID: endpoint.deviceID, detail: "已认证，正在连接")
          }
        } catch {
          publish(.scanning, endpoint: nil, deviceID: nil, detail: error.localizedDescription)
          Thread.sleep(forTimeInterval: 0.25)
          continue
        }
      }

      guard let activeEndpoint = endpoint else { continue }
      do {
        try post(to: activeEndpoint)
        failures = 0
        publish(
          .online,
          endpoint: activeEndpoint.url.absoluteString,
          deviceID: activeEndpoint.deviceID,
          detail: "C3XL 在线，无需 Token"
        )
      } catch {
        failures += 1
        publish(.error, endpoint: activeEndpoint.url.absoluteString, deviceID: activeEndpoint.deviceID, detail: "发送失败：\(error.localizedDescription)")
        if failures >= 3 { endpoint = nil }
      }
      Thread.sleep(forTimeInterval: 0.2)
    }
  }

  private func post(to endpoint: NavAssistEndpoint) throws {
    let nowMs = UInt64(Date().timeIntervalSince1970 * 1_000)
    let snapshot = session.nextSnapshot(from: stateStore.read(), nowMs: nowMs)
    let body = try CanonicalJSON.encode(snapshot)
    let prefix = "navassist_snapshot\n3\nPOST\n/v3/snapshot\n\(endpoint.deviceID)\n\(identity.keyID)\n\(body.count)\n"
    let signature = try identity.sign(Data(prefix.utf8) + body)
    var request = URLRequest(url: endpoint.url, timeoutInterval: 1.5)
    request.httpMethod = "POST"
    request.httpBody = body
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.setValue(identity.keyID, forHTTPHeaderField: "X-NavAssist-Key-Id")
    request.setValue(signature, forHTTPHeaderField: "X-NavAssist-Signature")

    let semaphore = DispatchSemaphore(value: 0)
    var result: Result<Void, Error> = .failure(URLError(.unknown))
    URLSession.shared.dataTask(with: request) { _, response, error in
      if let error {
        result = .failure(error)
      } else if let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) {
        result = .success(())
      } else {
        result = .failure(URLError(.badServerResponse))
      }
      semaphore.signal()
    }.resume()
    if semaphore.wait(timeout: .now() + 2) == .timedOut { throw URLError(.timedOut) }
    try result.get()
  }

  private var isRunning: Bool {
    lock.lock()
    defer { lock.unlock() }
    return running
  }

  private func consumeDiscoveryReset() -> Bool {
    lock.lock()
    defer { lock.unlock() }
    let requested = discoveryResetRequested
    discoveryResetRequested = false
    return requested
  }

  private func publish(
    _ connection: NavAssistClientStatus.Connection,
    endpoint: String?,
    deviceID: String?,
    detail: String
  ) {
    let status = NavAssistClientStatus(
      connection: connection,
      endpoint: endpoint,
      deviceID: deviceID,
      appKeyID: identity.keyID,
      detail: detail
    )
    lock.lock()
    currentStatus = status
    lock.unlock()
    DispatchQueue.main.async {
      NotificationCenter.default.post(name: .navAssistStatusChanged, object: status)
    }
  }
}
