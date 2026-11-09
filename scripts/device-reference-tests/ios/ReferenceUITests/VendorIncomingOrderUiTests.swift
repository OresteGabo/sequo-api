import XCTest

/// Explicitly selected synthetic merchant actions through the installed app; no HTTP or payment helpers.
final class VendorIncomingOrderUiTests: XCTestCase {
    private struct Configuration: Decodable {
        let enabled: Bool
        let environment: String
        let simulatorUDID: String
        let apiBaseURL: String
        let releaseID: String
        let bundle: String
        let sourceHead: String
        let payloadSha256: String
        let action: String
        let expectedReference: String
        let expectedAmountCfa: Int
        let expectedQuantity: Int
    }
    private var config: Configuration!
    private var app: XCUIApplication!
    private let titles = ["New paid order", "Nouvelle commande payée"]
    private enum Failure: Error { case guardMismatch, missingControl }

    override func setUpWithError() throws {
        continueAfterFailure = false
        #if !targetEnvironment(simulator)
        XCTFail("Only the approved local simulator is allowed"); throw Failure.guardMismatch
        #endif
        let env = ProcessInfo.processInfo.environment
        guard let encoded = env["SEQUO_REFERENCE_CONFIG_BASE64"], let data = Data(base64Encoded: encoded) else {
            XCTFail("Use run-incoming.py with an identified prepared bundle"); throw Failure.guardMismatch
        }
        config = try JSONDecoder().decode(Configuration.self, from: data)
        guard config.enabled, config.environment == "LOCAL_FIXTURE",
              config.simulatorUDID == "59D90F12-C368-449B-8752-6BD86241E8DB",
              env["SIMULATOR_UDID"] == config.simulatorUDID,
              config.apiBaseURL == "http://127.0.0.1:55840",
              config.bundle == "dev.orestegabo.sequovendor.SequoVendor.fixture",
              config.releaseID == "sequo-vendor-incoming-20261109-02",
              config.sourceHead == "79ed43ce097e92acfe5f4f0e69737e71cff72c74",
              config.payloadSha256.count == 64, config.expectedQuantity == 2,
              (1...1_000_000).contains(config.expectedAmountCfa),
              ["accept", "refuse", "wait", "inspect-waiting"].contains(config.action), validReference(config.expectedReference) else {
            XCTFail("The fixture, simulator, scene or identified artifact guard did not match"); throw Failure.guardMismatch
        }
        app = XCUIApplication(bundleIdentifier: config.bundle)
    }

    override func tearDownWithError() throws {
        if app?.state == .runningForeground {
            if testRun?.hasSucceeded == false {
                logFixedPublicState()
                // Local xcresult only. Never attach editable credentials or an unknown six-digit code.
                let hasEditable = app.textFields.count + app.secureTextFields.count + app.textViews.count > 0
                let hasPossibleCredential = labels().contains { $0.range(of: "^[0-9]{6}$", options: .regularExpression) != nil }
                if !hasEditable && !hasPossibleCredential {
                    let attachment = XCTAttachment(screenshot: app.screenshot())
                    attachment.name = "Vendor-incoming-failure-" + config.action
                    attachment.lifetime = .keepAlways
                    add(attachment)
                }
            }
            XCUIDevice.shared.press(.home)
        }
    }

    func testIncomingOrderUsesExistingMerchantAction() throws {
        guard config.action != "inspect-waiting" else { XCTFail("Select the separate read-only method"); throw Failure.guardMismatch }
        try openReadyVendor()
        XCTAssertTrue(eventually(30) { self.visible(self.titles) }, "Actual incoming dialog required")
        XCTAssertTrue(visible([config.expectedReference]), "Exact fresh scoped M reference required before any action")
        XCTAssertTrue(eventually(3) { self.labels().contains { $0.range(of: "^2\\s*×\\s*.+", options: .regularExpression) != nil } }, "Actual quantity-two product line required")
        XCTAssertTrue(eventually(3) { self.labels().contains { String($0.filter { !$0.isWhitespace }) == "\(self.config.expectedAmountCfa)CFA" } }, "Actual server subtotal required")
        if config.action == "wait" {
            try awaitOverlayGone(63)
            if visible(["Back to orders", "Retour aux commandes"]) { try tap(["Back to orders", "Retour aux commandes"]) }
            try navigation(["Orders", "Commandes"])
            try tap(["New", "Nouvelles"], filter: true)
            XCTAssertTrue(eventually(8) { self.visible([
                "Ringing has stopped. You can still accept waiting orders before their deadline.",
                "La sonnerie est arrêtée. Vous pouvez encore accepter les commandes en attente avant leur échéance."]) })
            try assertScopedStatus(["Waiting for your response", "En attente de votre réponse"], holdQueue: true)
        } else {
            try tap(config.action == "accept" ? ["Accept order", "Accepter la commande"] : ["Refuse order", "Refuser la commande"])
            try awaitOverlayGone(30)
            XCTAssertTrue(eventually(30) { self.visible(["Order detail", "Detail commande"]) })
            if config.action == "accept" {
                XCTAssertTrue(eventually(30) { self.visible(["Start preparation", "Commencer la preparation"]) })
                try assertScopedStatus(["Accepted", "Acceptee"])
            } else {
                try assertScopedStatus(["Rejected", "Refusee"])
                XCTAssertFalse(visible(["Start preparation", "Commencer la preparation"]))
            }
            XCTAssertFalse(visible(["The order action could not be completed. Try again.", "L'action sur la commande n'a pas abouti. Reessayez."]))
        }
    }

    /// No fresh scene or order action: inspect the already-canonical waiting record.
    func testExistingWaitingQueueIsReadable() throws {
        guard config.action == "inspect-waiting" else { XCTFail("Read-only inspection requires inspect-waiting"); throw Failure.guardMismatch }
        try openReadyVendor()
        logFixedPublicState()
        XCTAssertFalse(visible(titles), "Use the existing waiting part, not a newly ringing scene")
        if visible(["Back to orders", "Retour aux commandes"]) { try tap(["Back to orders", "Retour aux commandes"]) }
        try navigation(["Orders", "Commandes"])
        try tap(["New", "Nouvelles"], filter: true)
        XCTAssertTrue(eventually(15) { self.visible([self.config.expectedReference]) }, "The existing canonical part must be present")
        XCTAssertTrue(eventually(8) { self.visible(["Waiting for your response", "En attente de votre réponse"]) })
        XCTAssertTrue(eventually(3) { self.labels().contains { String($0.filter { !$0.isWhitespace }) == "\(self.config.expectedAmountCfa)CFA" } })
    }

    private func openReadyVendor() throws {
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 25))
        let chooser = ["Local test merchant accounts", "Comptes commerçants de test local"]
        let yawa = ["[TEST] Yawa Adjei"]
        guard eventually(25, { self.visible(self.titles + ["Orders", "Commandes"] + chooser + yawa) }) else {
            logFixedPublicState()
            XCTFail("No actual signed-in content, incoming dialog or ordinary fixture chooser became accessible within 25 seconds")
            throw Failure.missingControl
        }
        if visible(titles) || visible(["Orders", "Commandes"]) { return }
        if !visible(yawa) { try tap(chooser) }
        try tap(yawa)
        XCTAssertTrue(eventually(25) { self.visible(self.titles + ["Orders", "Commandes"]) }, "Normal Yawa login must reach real workspace content")
    }

    private func logFixedPublicState() {
        for title in ["Orders", "Commandes", "New paid order", "Nouvelle commande payée", "Order detail", "Detail commande",
                      "Local test merchant accounts", "Comptes commerçants de test local", "Back to orders", "Retour aux commandes"] {
            let query = predicate([title])
            let texts = app.staticTexts.matching(query)
            let all = app.descendants(matching: .any).matching(query)
            print("VendorIncomingUI fixedLabel=\(title) staticCount=\(texts.count) anyCount=\(all.count)")
            for element in all.allElementsBoundByIndex.prefix(4) where element.exists {
                print("VendorIncomingUI fixedLabel=\(title) frame=\(element.frame)")
            }
        }
        print("VendorIncomingUI state=\(app.state.rawValue)")
    }

    private func predicate(_ labels: [String], filter: Bool = false) -> NSPredicate {
        NSCompoundPredicate(orPredicateWithSubpredicates: labels.flatMap { value in
            filter ? [NSPredicate(format: "label == %@", value), NSPredicate(format: "label BEGINSWITH %@", value + " (")] : [NSPredicate(format: "label == %@", value)]
        })
    }
    private func inFrame(_ element: XCUIElement) -> Bool {
        guard element.exists else { return false }
        let frame = element.frame
        return !frame.isEmpty && frame.minX.isFinite && frame.minY.isFinite && frame.width.isFinite && frame.height.isFinite && app.frame.insetBy(dx: 1, dy: 3).contains(frame)
    }
    private func text(_ labels: [String]) -> XCUIElement {
        // Match the proven ReferenceDeviceTests helper: prefer the actual static text,
        // then broader Compose semantics. Existence is not a tap/hittability assertion.
        let query = predicate(labels)
        let value = app.staticTexts.matching(query).firstMatch
        if value.exists { return value }
        return app.descendants(matching: .any).matching(query).firstMatch
    }
    private func matches(_ labels: [String], filter: Bool = false) -> [XCUIElement] {
        let query = predicate(labels, filter: filter)
        return (app.buttons.matching(query).allElementsBoundByIndex + app.staticTexts.matching(query).allElementsBoundByIndex +
                app.descendants(matching: .any).matching(query).allElementsBoundByIndex).filter(inFrame)
    }
    private func visible(_ labels: [String]) -> Bool { text(labels).exists }
    private func labels() -> [String] { app.staticTexts.allElementsBoundByIndex.filter { $0.exists }.map { $0.label } }
    private func eventually(_ seconds: TimeInterval, _ condition: () -> Bool) -> Bool {
        let end = Date().addingTimeInterval(seconds)
        repeat { if condition() { return true }; Thread.sleep(forTimeInterval: 0.15) } while Date() < end
        return false
    }
    private func tap(_ labels: [String], filter: Bool = false) throws {
        var target: XCUIElement?
        guard eventually(8, { target = self.matches(labels, filter: filter).first; return target != nil }) else {
            XCTFail("Required normal UI control is unavailable: \(labels)"); throw Failure.missingControl
        }
        let element = try XCTUnwrap(target)
        // Filter activation geometry is checked above; Compose may not expose a valid AX activation point.
        if filter { app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: element.frame.midX - app.frame.minX, dy: element.frame.midY - app.frame.minY)).tap() }
        else { XCTAssertTrue(element.isHittable); element.tap() }
    }
    private func navigation(_ labels: [String]) throws {
        let target = try XCTUnwrap(matches(labels).max { $0.frame.midY < $1.frame.midY })
        XCTAssertTrue(target.isHittable); target.tap()
    }
    private func awaitOverlayGone(_ timeout: TimeInterval) throws {
        var absent: Date?
        XCTAssertTrue(eventually(timeout) {
            if self.visible(self.titles) { absent = nil; return false }
            if absent == nil { absent = Date() }
            return Date().timeIntervalSince(absent!) >= 0.5
        }, "The actual overlay must stay closed for at least half a second")
    }
    private func assertScopedStatus(_ states: [String], holdQueue: Bool = false) throws {
        let end = Date().addingTimeInterval(30)
        var foundReferenceAt: Date?
        repeat {
            let referenceExists = visible([config.expectedReference])
            // Presence uses the same staticText-first lookup as the existing proven harness.
            // Frame/hittability assertions apply to taps, not Compose's informational text.
            if referenceExists && visible(states) { return }
            if holdQueue && referenceExists && foundReferenceAt == nil { foundReferenceAt = Date() }
            if let seen = foundReferenceAt {
                if Date().timeIntervalSince(seen) > 7.5 { break }
            } else { app.swipeUp() }
            Thread.sleep(forTimeInterval: 0.25)
        } while Date() < end
        XCTFail("The expected merchant part and canonical state were not visible"); throw Failure.missingControl
    }
    private func validReference(_ value: String) -> Bool {
        let alphabet = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
        let symbols = Array(value.replacingOccurrences(of: "-", with: ""))
        guard value.count == 11, symbols.count == 9, symbols.first == "M", symbols.allSatisfy({ alphabet.contains($0) }) else { return false }
        var crc = 0
        for symbol in symbols.prefix(7) {
            let code = alphabet.firstIndex(of: symbol)!
            for bit in stride(from: 4, through: 0, by: -1) { let feedback = ((crc >> 9) ^ (code >> bit)) & 1; crc = (crc << 1) & 0x3ff; if feedback != 0 { crc ^= 0x233 } }
        }
        return symbols[7] == alphabet[crc >> 5] && symbols[8] == alphabet[crc & 31]
    }
}
