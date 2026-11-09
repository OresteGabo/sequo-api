import XCTest

/// No HTTP helpers: assertions are made against the actual installed apps' accessibility UI.
final class ReferenceDeviceTests: XCTestCase {
    struct Configuration: Decodable {
        let enabled: Bool
        let simulatorUDID: String
        let environment: String
        let apiBaseURL: String
        let releaseID: String
        let bundles: [String: String]
        let customerOrderReference: String
        let customerRefundOrderReference: String
        let merchantPartReference: String
        let missionReference: String
        let ledgerReference: String
        let parcelReferences: [String]
        let hubReturnReference: String
        let hubTypoReference: String
        let includeHubConfirmation: Bool
    }
    private var configuration: Configuration!

    override func setUpWithError() throws {
        continueAfterFailure = false
        #if !targetEnvironment(simulator)
        XCTFail("These tests are limited to the approved local simulator."); throw HarnessError.guardMismatch
        #endif
        let environment = ProcessInfo.processInfo.environment
        guard let encoded = environment["SEQUO_REFERENCE_CONFIG_BASE64"],
              let data = Data(base64Encoded: encoded) else {
            XCTFail("Use the guarded run.py launcher; no fixture configuration was supplied."); throw HarnessError.guardMismatch
        }
        configuration = try JSONDecoder().decode(Configuration.self, from: data)
        guard configuration.enabled, configuration.environment == "LOCAL_FIXTURE",
              configuration.apiBaseURL == "http://127.0.0.1:55840",
              configuration.simulatorUDID == "59D90F12-C368-449B-8752-6BD86241E8DB",
              environment["SIMULATOR_UDID"] == configuration.simulatorUDID,
              configuration.bundles == [
                "customer": "dev.orestegabo.sequo.Sequo.fixture",
                "vendor": "dev.orestegabo.sequovendor.SequoVendor.fixture",
                "rider": "dev.orestegabo.sequorider.SequoRider.fixture",
                "hub": "dev.orestegabo.sequohub.SequoHub.fixture"
              ] else {
            XCTFail("The simulator, origin or exact fixture-bundle guard did not match."); throw HarnessError.guardMismatch
        }
    }

    func test01CustomerStoredOrderReferences() throws {
        let app = try open("customer", account: "Ama", navigation: ["Orders", "Suivi"])
        try show(configuration.customerRefundOrderReference, in: app)
        capture("Customer-refund-order", app)
        try scrollToTop(app)
        try show(configuration.customerOrderReference, in: app)
        capture("Customer-delivered-order", app)
        // Do not expand customer pickup credentials or request/renew a return.
    }

    func test02VendorPartAndParentOrderReferences() throws {
        let app = try open("vendor", account: "Yawa", navigation: ["Orders", "Commandes"])
        let backLabels = ["Back to orders", "Retour aux commandes"]
        if text(backLabels, in: app).exists { try tap(backLabels, in: app) }
        try tapFilter(["Ready", "Pretes", "Prêtes"], in: app)
        try show(configuration.merchantPartReference, in: app)
        capture("Vendor-merchant-part", app)
        try tapNearestButton(below: configuration.merchantPartReference,
                             labels: ["View detail", "Voir le detail", "Voir le détail"], in: app)
        try show(configuration.customerOrderReference, in: app)
        capture("Vendor-parent-order", app)
        // No preparation, handoff, rejection or catalog mutation is tapped.
    }

    func test03RiderMissionAndRecordedPayoutReferences() throws {
        let app = try open("rider", account: "Senam", navigation: ["History", "Archives"])
        try show(configuration.missionReference, in: app)
        capture("Rider-completed-mission-reference", app)
        try tapNavigation(["Payout", "Pay", "Paiement"], in: app)
        let payoutHeading = text(["Recorded earnings", "Gains enregistrés"], in: app, contains: true)
        XCTAssertTrue(payoutHeading.waitForExistence(timeout: 15), "Payout-specific content must replace History before its reference can count as evidence.")
        try show(configuration.missionReference, in: app)
        capture("Rider-recorded-payout-mission-reference", app)
        // Completed history cards have no detail action. Payout intentionally shows the mission D reference,
        // not its ledger W identity. Parent order/W correspondence is server evidence, not asserted as visible UI.
    }

    func test04HubScopedParcelsHistoryAndCancelledReturn() throws {
        let app = try open("hub", account: "Esso", navigation: ["Hub", "Relais"])
        for reference in configuration.parcelReferences {
            try scrollToTop(app)
            try show(reference, in: app)
            capture("Hub-Esso-parcel-" + reference, app)
        }
        try tapNavigation(["History", "Historique"], in: app)
        try show(configuration.parcelReferences[0], in: app)
        capture("Hub-Esso-stored-history", app)
        try signOut(app, family: "hub")
        try chooseFixture("Dédé", in: app)
        try tapNavigation(["Returns", "Retours"], in: app)
        capture("Hub-Dede-return-screen", app)
        guard configuration.includeHubConfirmation else {
            XCTFail("The Hub test requires the explicit checksum-and-cancel scenario; it cannot silently pass without it.")
            throw HarnessError.guardMismatch
        }
        let referenceField = try formField(index: 0, in: app)
        try replace(referenceField, with: configuration.hubTypoReference, in: app)
        let typo = text(["Check the reference", "Vérifiez la référence"], in: app, contains: true)
        XCTAssertTrue(typo.waitForExistence(timeout: 5), "A mistyped checksum must fail locally.")
        capture("Hub-local-checksum-error", app)
        try replace(referenceField, with: configuration.hubReturnReference, in: app)
        let pin = try formField(index: 1, in: app)
        try replace(pin, with: "000000", in: app) // Deliberately synthetic. Never a real pickup/return PIN.
        try tap(["Confirm physical intake", "Confirmer la réception physique"], in: app)
        let confirmation = text(["Confirm physical operation", "Confirmer l'opération physique"], in: app)
        XCTAssertTrue(confirmation.waitForExistence(timeout: 20), "The guarded reference lookup should open the review dialog.")
        // The confirm button is NEVER tapped. This is the explicit operation safety boundary.
        try tap(["Cancel", "Annuler"], in: app)
        XCTAssertFalse(confirmation.exists, "The physical-operation dialog must be dismissed.")
        // Clear the synthetic input after cancellation; do not send a custody action.
        try replace(try formField(index: 1, in: app), with: "", in: app)
        capture("Hub-return-confirmation-cancelled", app)
    }

    private func open(_ family: String, account: String, navigation: [String]) throws -> XCUIApplication {
        let identifier = try XCTUnwrap(configuration.bundles[family])
        XCTAssertTrue(identifier.hasSuffix(".fixture"))
        let app = XCUIApplication(bundleIdentifier: identifier)
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 25), "The preflight-verified fixture app must launch.")
        // Reuse an already protected session; expected role-owned references below must still match.
        // Hub has two explicit site checks, so select Esso first before switching to Dédé through normal UI.
        let accountNavigation = text(family == "hub" ? ["Settings", "Réglages"] : ["Account", "Compte"], in: app)
        if fixtureButton(account, in: app).waitForExistence(timeout: 3) {
            try chooseFixture(account, in: app)
        } else if accountNavigation.exists {
            if family == "hub" {
                try signOut(app, family: family)
                try chooseFixture(account, in: app)
            }
        } else {
            try chooseFixture(account, in: app)
        }
        try tapNavigation(navigation, in: app)
        return app
    }

    private func chooseFixture(_ account: String, in app: XCUIApplication) throws {
        let chooser = button(["Local test merchant accounts", "Comptes commerçants de test local"], in: app)
        if !fixtureButton(account, in: app).exists && chooser.waitForExistence(timeout: 3) && chooser.isHittable { chooser.tap() }
        let button = fixtureButton(account, in: app)
        if !button.waitForExistence(timeout: 10) {
            for _ in 0..<6 where !button.isHittable { app.swipeUp() }
        }
        if !button.exists || !button.isHittable { capture("Missing-fixture-account-" + account, app) }
        XCTAssertTrue(button.exists && button.isHittable, "The guarded fixture account chooser is required: " + account)
        button.tap()
        // The following navigation/reference assertion proves login success; a tap alone is not evidence.
    }

    private func signOut(_ app: XCUIApplication, family: String) throws {
        let labels = ["Sign out", "Se deconnecter", "Se déconnecter", "Deconnexion", "Déconnexion", "Logout"]
        if !button(labels, in: app).isHittable {
            try tapNavigation(family == "hub" ? ["Settings", "Réglages"] : ["Account", "Compte"], in: app)
        }
        try tap(labels, in: app, maxSwipes: 14)
    }

    private func fixtureButton(_ account: String, in app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@ AND label CONTAINS %@", account, "[TEST]")).firstMatch
    }
    private func predicate(_ values: [String], contains: Bool) -> NSPredicate {
        NSCompoundPredicate(orPredicateWithSubpredicates: values.map {
            NSPredicate(format: contains ? "label CONTAINS[c] %@" : "label ==[c] %@", $0)
        })
    }
    private func text(_ labels: [String], in app: XCUIApplication, contains: Bool = false) -> XCUIElement {
        let query = predicate(labels, contains: contains)
        let staticText = app.staticTexts.matching(query).firstMatch
        if staticText.exists { return staticText }
        return app.descendants(matching: .any).matching(query).firstMatch
    }
    private func button(_ labels: [String], in app: XCUIApplication, contains: Bool = false) -> XCUIElement {
        app.buttons.matching(predicate(labels, contains: contains)).firstMatch
    }
    private func safelyHittable(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        guard element.exists else { return false }
        let frame = element.frame
        let safeBounds = app.frame.insetBy(dx: 2, dy: 8)
        // XCTest can throw an activation-point error for offscreen Compose horizontal-tab nodes.
        // Reject invalid/out-of-bounds geometry BEFORE asking the accessibility server for hittability.
        guard !frame.isEmpty, frame.origin.x.isFinite, frame.origin.y.isFinite,
              frame.width.isFinite, frame.height.isFinite, safeBounds.contains(frame) else { return false }
        return element.isHittable
    }
    private func visibleMatches(_ query: NSPredicate, in app: XCUIApplication) -> [XCUIElement] {
        app.descendants(matching: .any).matching(query).allElementsBoundByIndex
            .filter { safelyHittable($0, in: app) }
    }
    private func tapNavigation(_ labels: [String], in app: XCUIApplication) throws {
        // Compose role=Tab is not always exposed as XCUIElementTypeButton. The lowest exact label is
        // the fixed bottom navigation, rather than a duplicate page title or card heading.
        for _ in 0..<5 {
            let candidates = visibleMatches(predicate(labels, contains: false), in: app)
                .sorted { $0.frame.midY > $1.frame.midY }
            if let target = candidates.first { target.tap(); return }
            _ = app.wait(for: .runningForeground, timeout: 1)
        }
        capture("Missing-navigation-" + labels.joined(separator: "-"), app)
        XCTFail("Visible bottom navigation not found: " + labels.joined(separator: " / "))
        throw HarnessError.controlNotFound
    }
    private func tapFilter(_ labels: [String], in app: XCUIApplication) throws {
        // Compose filter labels can have valid frames but broken AX activation points. Do not query
        // isHittable for these labels: constrain their observed geometry, then use a native coordinate tap.
        func framedMatches(_ query: NSPredicate) -> [XCUIElement] {
            let safeBounds = app.frame.insetBy(dx: 2, dy: 8)
            return app.descendants(matching: .any).matching(query).allElementsBoundByIndex.filter { element in
                guard element.exists else { return false }
                let frame = element.frame
                return !frame.isEmpty && frame.origin.x.isFinite && frame.origin.y.isFinite &&
                    frame.width.isFinite && frame.height.isFinite && safeBounds.contains(frame)
            }
        }
        // Match only the filter title plus its optional count, never e.g. a 'Mark ready' business action.
        let query = NSCompoundPredicate(orPredicateWithSubpredicates: labels.flatMap {
            [NSPredicate(format: "label ==[c] %@", $0), NSPredicate(format: "label BEGINSWITH[c] %@", $0 + " (")]
        })
        for _ in 0..<5 {
            if let target = framedMatches(query).first {
                app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
                    .withOffset(CGVector(dx: target.frame.midX - app.frame.minX, dy: target.frame.midY - app.frame.minY)).tap()
                return
            }
            let siblingLabels = ["New", "Nouvelles", "Accepted", "Acceptees", "Preparing", "Preparation", "Ready", "Pretes", "Completed", "Terminees"]
            let siblings = NSCompoundPredicate(orPredicateWithSubpredicates: siblingLabels.map {
                NSPredicate(format: "label BEGINSWITH[c] %@", $0 + " (")
            })
            if let visibleChip = framedMatches(siblings).first {
                // Swipe across the actual filter band, rather than the tiny text node's own bounds.
                let origin = app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
                let y = visibleChip.frame.midY - app.frame.minY
                let start = origin.withOffset(CGVector(dx: app.frame.width * 0.88, dy: y))
                let end = origin.withOffset(CGVector(dx: app.frame.width * 0.15, dy: y))
                start.press(forDuration: 0.05, thenDragTo: end)
            } else { app.swipeDown() }
        }
        capture("Missing-filter-" + labels.joined(separator: "-"), app)
        XCTFail("The expected read-only order filter is unavailable.")
        throw HarnessError.controlNotFound
    }
    private func tap(_ labels: [String], in app: XCUIApplication, contains: Bool = false, maxSwipes: Int = 3) throws {
        for _ in 0..<maxSwipes {
            let target = button(labels, in: app, contains: contains)
            if target.waitForExistence(timeout: 2) && safelyHittable(target, in: app) { target.tap(); return }
            let label = text(labels, in: app, contains: contains)
            if safelyHittable(label, in: app) { label.tap(); return }
            app.swipeUp()
        }
        capture("Missing-control-" + labels.joined(separator: "-"), app)
        XCTFail("Visible actionable control not found: " + labels.joined(separator: " / "))
        throw HarnessError.controlNotFound
    }
    private func show(_ reference: String, in app: XCUIApplication, maxSwipes: Int = 24) throws {
        for _ in 0..<maxSwipes {
            let target = text([reference], in: app, contains: true)
            if target.waitForExistence(timeout: 1) && safelyHittable(target, in: app) { return }
            app.swipeUp()
        }
        capture("Missing-reference-" + reference, app)
        XCTFail("The real UI did not expose expected reference " + reference)
        throw HarnessError.referenceNotVisible
    }
    private func scrollToTop(_ app: XCUIApplication) throws {
        for _ in 0..<12 { app.swipeDown() }
    }
    private func tapNearestButton(below reference: String, labels: [String], in app: XCUIApplication) throws {
        for _ in 0..<4 {
            let anchor = text([reference], in: app, contains: true)
            let candidates = app.buttons.matching(predicate(labels, contains: false)).allElementsBoundByIndex
                .filter { safelyHittable($0, in: app) && $0.frame.minY >= anchor.frame.minY }
                .sorted { $0.frame.minY < $1.frame.minY }
            if let candidate = candidates.first { candidate.tap(); return }
            app.swipeUp()
        }
        capture("Missing-detail-control-" + reference, app)
        XCTFail("Detail action was not reachable for " + reference)
        throw HarnessError.controlNotFound
    }
    private func formField(index: Int, in app: XCUIApplication) throws -> XCUIElement {
        // Compose's multiline OutlinedTextField is a UIKit TextView on iOS. Sort all editable types
        // by their actual layout, rather than concatenating types (which could select the condition note as PIN).
        let fields = (app.textFields.allElementsBoundByIndex + app.secureTextFields.allElementsBoundByIndex + app.textViews.allElementsBoundByIndex)
            .filter { $0.exists && !$0.frame.isEmpty }
            .sorted { $0.frame.minY < $1.frame.minY }
        let labels = [["Return reference", "Référence du retour"], ["Server return PIN", "PIN de retour du serveur"],
                      ["Visible package condition", "État visible du colis"]]
        if labels.indices.contains(index), let labelled = fields.first(where: { field in
            labels[index].contains { field.label.localizedCaseInsensitiveContains($0) || (field.placeholderValue ?? "").localizedCaseInsensitiveContains($0) }
        }) { return labelled }
        guard fields.count >= 3 && fields.indices.contains(index) else {
            capture("Missing-return-form-field-" + String(index), app)
            XCTFail("Expected three editable return-intake fields are unavailable.")
            throw HarnessError.controlNotFound
        }
        return fields[index]
    }
    private func replace(_ field: XCUIElement, with value: String, in app: XCUIApplication) throws {
        XCTAssertTrue(safelyHittable(field, in: app))
        field.tap()
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 128) + value)
        // Use the product's actual Done action. Never insert a newline into a reference/PIN.
        let doneLabels = ["Done", "Terminé", "Termine"]
        let done = app.keyboards.buttons.matching(predicate(doneLabels, contains: false)).firstMatch
        if done.waitForExistence(timeout: 1) && safelyHittable(done, in: app) {
            done.tap()
        } else if let inlineDone = app.buttons.matching(predicate(doneLabels, contains: false)).allElementsBoundByIndex
                    .first(where: { safelyHittable($0, in: app) }) {
            // NumberPad has no return key: Hub provides this explicit localized focus-only control.
            inlineDone.tap()
        }
    }
    private func capture(_ name: String, _ app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
    private enum HarnessError: Error { case controlNotFound, referenceNotVisible, guardMismatch }
}
