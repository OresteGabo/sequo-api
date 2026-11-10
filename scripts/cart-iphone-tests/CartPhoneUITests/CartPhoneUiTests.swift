import XCTest

/// Explicit physical-phone UI actions. No direct HTTP, session injection, payments or app reset helpers.
final class CartPhoneUiTests: XCTestCase {
    private var app: XCUIApplication!
    private var frames: [String: CGRect] = [:]
    private struct Action: Decodable {
        let kind: String
        var label: String? = nil
        var value: String? = nil
        var index: Int? = nil
        var x: Double? = nil
        var y: Double? = nil
    }
    private enum Failure: Error { case notAuthorized, missingControl }
    override func setUpWithError() throws {
        continueAfterFailure = false
        #if targetEnvironment(simulator)
        XCTFail("A physical iPhone is required"); throw Failure.notAuthorized
        #endif
        guard ProcessInfo.processInfo.environment["SEQUO_CART_PHONE_CONFIG"] == "inspect-authorized-20261110" else {
            XCTFail("Explicit physical cart-test authorization configuration is required."); throw Failure.notAuthorized
        }
        let bundle = ProcessInfo.processInfo.environment["SEQUO_PHONE_APP"] ?? "dev.orestegabo.sequo.Sequo"
        guard ["dev.orestegabo.sequo.Sequo", "dev.orestegabo.sequovendor.SequoVendor"].contains(bundle) else {
            throw Failure.notAuthorized
        }
        app = XCUIApplication(bundleIdentifier: bundle)
    }
    override func tearDownWithError() throws {
        if testRun?.hasSucceeded == false && app != nil { capture("failure-state") }
    }
    private func matching(_ label: String) -> [XCUIElement] {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@ OR identifier == %@", label, label)).allElementsBoundByIndex.filter { $0.exists && $0.isHittable }
    }
    private func waitFor(_ label: String, timeout: TimeInterval = 20) -> Bool {
        XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !self.matching(label).isEmpty }, object: nil)], timeout: timeout) == .completed
    }
    private func capture(_ name: String) {
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = name + "-screen"; screenshot.lifetime = .keepAlways; add(screenshot)
        let tree = XCTAttachment(string: app.debugDescription); tree.name = name + "-tree"; tree.lifetime = .keepAlways; add(tree)
    }
    func testAuthorizedCartActions() throws {
        app.activate()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        _ = XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            !self.app.staticTexts["Marketplace, ready."].exists && self.app.buttons.count > 0
        }, object: nil)], timeout: 40)
        let environment = ProcessInfo.processInfo.environment
        let data = Data(base64Encoded: environment["SEQUO_CART_PHONE_ACTIONS"] ?? "W10=")!
        let actions = try JSONDecoder().decode([Action].self, from: data)
        for action in actions {
            switch action.kind {
            case "tap":
                let label = action.label!
                guard !["Proceed to checkout", "Passer la commande", "Request account deletion", "Demander la suppression du compte"].contains(label) else {
                    XCTFail("Only explicitly labelled no-money checkout is authorized in this suite"); throw Failure.notAuthorized
                }
                guard waitFor(label), let element = matching(label).dropFirst(action.index ?? 0).first else {
                    capture("missing-control"); XCTFail("Expected UI control was not available: " + label); throw Failure.missingControl
                }
                let frame = element.frame
                app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: frame.midX - app.frame.minX, dy: frame.midY - app.frame.minY)).tap()
            case "wait": XCTAssertTrue(waitFor(action.label!), "Expected UI label: " + action.label!)
            case "swipeUp": app.swipeUp()
            case "swipeDown": app.swipeDown()
            case "scrollDownSmall": app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35)).press(forDuration: 0.2, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.52)))
            case "scrollUpSmall": app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.62)).press(forDuration: 0.2, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.40)))
            case "tapPoint": app.coordinate(withNormalizedOffset: CGVector(dx: action.x!, dy: action.y!)).tap()
            case "type":
                let fields = app.textFields.allElementsBoundByIndex + app.secureTextFields.allElementsBoundByIndex + app.textViews.allElementsBoundByIndex
                guard fields.indices.contains(action.index ?? 0) else { capture("missing-input"); XCTFail("Expected input field"); throw Failure.missingControl }
                let field = action.label.flatMap { label in fields.first { $0.label == label || $0.identifier == label || $0.label.hasPrefix(label + ",") } } ?? fields[action.index ?? 0]
                let frame = field.frame
                app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: frame.midX - app.frame.minX, dy: frame.midY - app.frame.minY)).tap()
                Thread.sleep(forTimeInterval: 0.7); field.typeText(action.value!)
            case "assertEnabled":
                let controls = matching(action.label!)
                XCTAssertFalse(controls.isEmpty)
                XCTAssertEqual(controls.first?.isEnabled, action.value == "true")
            case "assertAbsent": XCTAssertTrue(matching(action.label!).isEmpty)
            case "waitEnabled":
                XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in self.matching(action.label!).first?.isEnabled == true }, object: nil)], timeout: 20), .completed)
            case "rememberFrame": frames[action.label!] = matching(action.label!).first?.frame
            case "assertSameFrame":
                guard let old = frames[action.label!], let now = matching(action.label!).first?.frame else { XCTFail("Missing row frame"); throw Failure.missingControl }
                XCTAssertEqual(old.minY, now.minY, accuracy: 1); XCTAssertEqual(old.minX, now.minX, accuracy: 1)
            case "assertQuantity":
                let expected = action.value!
                XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                    guard let control = self.matching(action.label!).first else { return false }
                    return self.app.staticTexts.matching(NSPredicate(format: "label == %@", expected)).allElementsBoundByIndex.contains { text in
                        text.exists && abs(text.frame.midY - control.frame.midY) < 8 && text.frame.minX >= control.frame.minX - 40 && text.frame.maxX <= control.frame.minX
                    }
                }, object: nil)], timeout: 20), .completed)
            case "scrollUntil":
                for _ in 0..<9 {
                    if !matching(action.label!).isEmpty { break }
                    let keyboardTop = app.keyboards.firstMatch.exists ? app.keyboards.firstMatch.frame.minY : app.frame.maxY
                    let lower = min(app.frame.maxY - 140, keyboardTop - 80)
                    let upper = max(app.frame.minY + 180, lower - 280)
                    let start = action.value == "down" ? upper : lower
                    let end = action.value == "down" ? lower : upper
                    app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: app.frame.midX, dy: start)).press(forDuration: 0.2, thenDragTo: app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: app.frame.midX, dy: end)))
                    Thread.sleep(forTimeInterval: 0.8)
                }
                XCTAssertFalse(matching(action.label!).isEmpty, "Expected scroll target: " + action.label!)

            case "allowLocationOnce":
                // Restrict permission handling to Sequo's location prompt and temporary access.
                let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
                var handled = false
                for _ in 0..<20 {
                    let alerts = app.alerts.allElementsBoundByIndex + springboard.alerts.allElementsBoundByIndex
                    for alert in alerts where alert.exists && alert.label.localizedCaseInsensitiveContains("Sequo") {
                        for title in ["Allow Once", "Autoriser une fois"] {
                            let button = alert.buttons[title]
                            if button.exists && button.isHittable { button.tap(); handled = true; break }
                        }
                        if handled { break }
                    }
                    if handled { break }
                    Thread.sleep(forTimeInterval: 0.5)
                }
            case "capture": capture(action.label ?? "cart-phone")
            default: XCTFail("Unknown authorized UI action"); throw Failure.notAuthorized
            }
            // Compose scroll/transition animations are not tracked by UIKit's idle detector.
            Thread.sleep(forTimeInterval: 0.8)
        }
        capture("cart-phone-final")
    }
}
