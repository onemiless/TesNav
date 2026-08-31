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
    XCTAssertEqual(AMapLaneActionMapper.actions(255), ["UNKNOWN"])
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
}
