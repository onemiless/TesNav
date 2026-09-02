import CryptoKit
import XCTest
@testable import TesNavIOS

final class NavAssistTests: XCTestCase {
  func testP256IdentityMatchesC3WireEncoding() throws {
    let privateKey = P256.Signing.PrivateKey()
    let publicDER = privateKey.publicKey.derRepresentation
    XCTAssertEqual(publicDER.count, 91)
    let publicText = publicDER.base64URLEncodedString()
    XCTAssertEqual(publicText.count, 122)
    let material = Data("navassist-test".utf8)
    let signature = try privateKey.signature(for: material).derRepresentation.base64URLEncodedString()
    XCTAssertTrue(NavAssistIdentity.verify(publicKeyText: publicText, data: material, signatureText: signature))
    XCTAssertFalse(NavAssistIdentity.verify(publicKeyText: publicText, data: Data("changed".utf8), signatureText: signature))
    XCTAssertEqual(NavAssistIdentity.keyID(for: publicText)?.count, 32)
  }

  func testRealtimeSnapshotUsesIOSAndRequiresFreshMatchedData() throws {
    var state = NavigationObservation()
    state.mode = "realtime"
    state.routeActive = true
    state.routeMatched = true
    state.gpsWeak = false
    state.latitude = 31.2
    state.longitude = 121.4
    state.accuracyM = 4
    state.bearingDeg = 90
    state.speedKph = 25
    state.locationObservedAtMs = 1_000
    state.guidanceObservedAtMs = 1_000
    state.currentStepIndex = 2
    state.maneuver = "turn_right"
    state.maneuverDistanceM = 120
    state.routeRevision = 3

    let snapshot = NavAssistSession().nextSnapshot(from: state, nowMs: 1_100)
    XCTAssertEqual(snapshot.sourcePlatform, "ios")
    XCTAssertEqual(snapshot.coordinateSystem, "gcj02")
    XCTAssertTrue(snapshot.routeActive)
    XCTAssertNotEqual(snapshot.maneuverEventId, 0)
    XCTAssertEqual(snapshot.guidance?.maneuver, "turn_right")
    XCTAssertEqual(snapshot.validForMs, 1_200)
  }

  func testAutomaticReroutePreservesRealtimeNavigationMode() {
    let store = NavigationStateStore.shared
    defer { store.stop() }
    store.stop()
    store.routeCalculated()
    store.startRealtime()

    let revisionBeforeReroute = store.read().routeRevision
    store.routeCalculated()
    let rerouted = store.read()

    XCTAssertEqual(rerouted.mode, "realtime")
    XCTAssertFalse(rerouted.routeActive)
    XCTAssertEqual(rerouted.routeRevision, revisionBeforeReroute + 1)
  }

  func testPublishCadenceDoesNotAddHTTPLatencyToTheInterval() {
    XCTAssertEqual(NavAssistPublishCadence.remainingDelay(after: 0.05), 0.15, accuracy: 0.0001)
    XCTAssertEqual(NavAssistPublishCadence.remainingDelay(after: 0.25), 0, accuracy: 0.0001)
  }

  func testSnapshotFailsClosedWithoutGuidance() {
    var state = NavigationObservation()
    state.mode = "realtime"
    state.routeActive = true
    state.routeMatched = true
    state.gpsWeak = false
    state.latitude = 31.2
    state.longitude = 121.4
    state.accuracyM = 4
    state.bearingDeg = 90
    state.speedKph = 25
    state.locationObservedAtMs = 1_000

    let snapshot = NavAssistSession().nextSnapshot(from: state, nowMs: 1_100)
    XCTAssertFalse(snapshot.routeActive)
    XCTAssertNil(snapshot.guidance)
    XCTAssertEqual(snapshot.maneuverEventId, 0)
  }

  func testGPSWeakFlagIsDiagnosticAndDoesNotDeactivateMatchedRealtimeRoute() {
    var state = NavigationObservation()
    state.mode = "realtime"
    state.routeActive = true
    state.routeMatched = true
    state.gpsWeak = true
    state.latitude = 31.2
    state.longitude = 121.4
    state.accuracyM = 18
    state.bearingDeg = 90
    state.speedKph = 25
    state.locationObservedAtMs = 1_000
    state.guidanceObservedAtMs = 1_000
    state.currentStepIndex = 2
    state.maneuver = "turn_right"
    state.maneuverDistanceM = 120

    let snapshot = NavAssistSession().nextSnapshot(from: state, nowMs: 1_100)
    XCTAssertTrue(snapshot.gpsWeak)
    XCTAssertTrue(snapshot.routeActive)
    XCTAssertNotEqual(snapshot.maneuverEventId, 0)
  }

  func testCanonicalJSONIsStableAndOmitsNil() throws {
    var state = NavigationObservation()
    state.routeRevision = 4
    let session = NavAssistSession()
    let first = try CanonicalJSON.encode(session.nextSnapshot(from: state, nowMs: 2_000))
    let text = try XCTUnwrap(String(data: first, encoding: .utf8))
    XCTAssertTrue(text.hasPrefix("{\"coordinateSystem\":"))
    XCTAssertFalse(text.contains("\"guidance\":null"))
    XCTAssertTrue(text.contains("\"sourcePlatform\":\"ios\""))
  }

  func testStableEventIDIsPositiveAndDeterministic() {
    let first = StableEventID.make("session:3:2:turn_right")
    XCTAssertEqual(first, StableEventID.make("session:3:2:turn_right"))
    XCTAssertNotEqual(first, StableEventID.make("session:3:3:turn_right"))
    XCTAssertGreaterThan(first, 0)
    XCTAssertLessThanOrEqual(first, UInt64(Int64.max))
  }

  func testAMapLaneActionsUseC3ProtocolUppercaseVocabulary() {
    XCTAssertEqual(AMapLaneActionMapper.actions(0), ["STRAIGHT"])
    XCTAssertEqual(AMapLaneActionMapper.actions(7), ["STRAIGHT", "LEFT", "RIGHT"])
    XCTAssertEqual(AMapLaneActionMapper.actions(21), ["BUS"])
    XCTAssertEqual(AMapLaneActionMapper.actions(255), [])
  }

  func testAMapLaneActionsMatchAndroidCompositeAndInvalidVocabulary() {
    XCTAssertEqual(AMapLaneActionMapper.actions(13), ["STRAIGHT"])
    XCTAssertEqual(AMapLaneActionMapper.actions(14), ["LEFT", "LEFT_U_TURN"])
    XCTAssertEqual(AMapLaneActionMapper.actions(18), ["LEFT", "RIGHT", "LEFT_U_TURN"])
    XCTAssertEqual(AMapLaneActionMapper.actions(24), ["DEDICATED"])
    XCTAssertEqual(AMapLaneActionMapper.actions(25), ["TIDAL"])
    XCTAssertEqual(AMapLaneActionMapper.actions(15), [])
    XCTAssertEqual(AMapLaneActionMapper.actions(22), [])
    XCTAssertEqual(AMapLaneActionMapper.actions(255), [])
    XCTAssertFalse(AMapLaneActionMapper.isRecommended(15))
    XCTAssertFalse(AMapLaneActionMapper.isRecommended(22))
    XCTAssertFalse(AMapLaneActionMapper.isRecommended(255))
    XCTAssertTrue(AMapLaneActionMapper.isRecommended(3))
  }

  func testAMapManeuversMatchAndroidRampExitAndMergeVocabulary() {
    XCTAssertEqual(AMapManeuverMapper.wireValue(icon: .left, formWay: .ramp), "ramp_left")
    XCTAssertEqual(AMapManeuverMapper.wireValue(icon: .right, formWay: .exit), "exit_right")
    XCTAssertEqual(AMapManeuverMapper.wireValue(icon: .mergeLeft, formWay: nil), "merge_left")
    XCTAssertEqual(AMapManeuverMapper.wireValue(icon: .mergeRight, formWay: nil), "merge_right")
  }

  func testSimulationModeNeverBecomesControlActiveSnapshot() {
    var state = NavigationObservation()
    state.mode = "simulation"
    state.routeActive = true
    state.routeMatched = true
    state.latitude = 31.2
    state.longitude = 121.4
    state.accuracyM = 4
    state.bearingDeg = 90
    state.speedKph = 25
    state.locationObservedAtMs = 1_000
    state.guidanceObservedAtMs = 1_000
    state.currentStepIndex = 2
    state.maneuver = "turn_right"

    let snapshot = NavAssistSession().nextSnapshot(from: state, nowMs: 1_100)

    XCTAssertEqual(snapshot.navigationMode, "simulation")
    XCTAssertFalse(snapshot.routeActive)
    XCTAssertEqual(snapshot.maneuverEventId, 0)
  }

  func testIOSObservationClockUsesCallbackReceiptTimeLikeAndroid() {
    XCTAssertEqual(
      NavigationObservationClock.milliseconds(Date(timeIntervalSince1970: 1.25)),
      1_250
    )
  }

  func testPairingStoreCanForgetPinnedDeviceLikeAndroidSettings() throws {
    let suite = "NavAssistTests.\(UUID().uuidString)"
    let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
    defer { defaults.removePersistentDomain(forName: suite) }
    let store = NavAssistPairingStore(defaults: defaults)
    let publicText = P256.Signing.PrivateKey().publicKey.derRepresentation.base64URLEncodedString()
    let device = PinnedNavAssistDevice(
      deviceID: try XCTUnwrap(NavAssistIdentity.keyID(for: publicText)),
      publicKey: publicText
    )

    XCTAssertTrue(store.pin(device))
    XCTAssertEqual(store.load(), device)
    store.clear()
    XCTAssertNil(store.load())
  }

  func testAMapSCodeMismatchExplainsRequiredIOSBundleBinding() {
    let message = AMapSearchFailureMessage.currentAddress(
      NSError(domain: "AMapSearchErrorDomain", code: 1008)
    )
    XCTAssertTrue(message.contains("com.garan.tesnav.ios"))
    XCTAssertTrue(message.contains("1008"))
  }

  func testAMapNetworkFailureHasActionableMessage() {
    let message = AMapSearchFailureMessage.search(
      NSError(domain: "AMapSearchErrorDomain", code: 1806)
    )
    XCTAssertTrue(message.contains("检查手机网络"))
    XCTAssertTrue(message.contains("1806"))
  }

  func testDirectedBroadcastAddressUsesIPv4SubnetMask() {
    XCTAssertEqual(
      IPv4DirectedBroadcast.address(ip: 0xC0A866DF, netmask: 0xFFFFFF00),
      0xC0A866FF
    )
    XCTAssertEqual(
      IPv4DirectedBroadcast.address(ip: 0x0A140102, netmask: 0xFFFF0000),
      0x0A14FFFF
    )
  }

  func testNavigationViewHandlesAMapCloseAndSettingsButtons() {
    let controller = NavigationViewController(
      destination: Destination(name: "Test", address: "", poiID: nil, latitude: 31.2, longitude: 121.4)
    )
    XCTAssertTrue(controller.responds(to: NSSelectorFromString("driveViewCloseButtonClicked:")))
    XCTAssertTrue(controller.responds(to: NSSelectorFromString("driveViewMoreButtonClicked:")))
  }

  func testNavigationViewHasNoFullWidthStatusOverlay() {
    let controller = NavigationViewController(
      destination: Destination(name: "Test", address: "", poiID: nil, latitude: 31.2, longitude: 121.4)
    )
    XCTAssertNil(Mirror(reflecting: controller).children.first { $0.label == "statusLabel" })
  }

  func testNavigationStartCanForceImmediateC3Rediscovery() {
    NavAssistClient.shared.requestRediscovery()
    XCTAssertEqual(NavAssistClient.shared.status().connection, .scanning)
  }
}
