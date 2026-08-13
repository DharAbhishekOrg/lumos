/// Lumos - ios-app/shell / Profile model, policy, preview equivalence, lifecycle.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-PRO-001, SRS-PRO-002, SRS-PRO-003, SRS-PRO-007, SRS-PRO-008, SRS-PRO-009, SRS-PRO-010, SRS-PRO-011
/// ADRs: ADR-002
/// Threading: any
/// Notes: The equivalence tests range over ProfileField.allCases and
/// Audience.allCases rather than naming examples, so a field or audience added
/// later is covered without anyone remembering to extend the matrix. That is
/// the difference between a matrix test and a handful of cases, and it is what
/// makes "the preview equals the policy" a property rather than a sample.

import Foundation
import XCTest
@testable import LumosAppShell

/// Profile model and derived-preview behaviour.
final class ProfileTests: XCTestCase {

    /// A profile with every field stated and one media object.
    private func fullyStatedProfile() -> LocalProfile {
        var profile = LocalProfile.empty
        for field in ProfileField.allCases {
            profile = profile.with(field, .statement("value of \(field.rawValue)"))
        }
        return profile.withMedia([ProfileMedia(objectId: "object-1", contentType: "image/jpeg",
                                               widthPixels: 640, heightPixels: 480, byteCount: 1024)])
    }

    // MARK: preview equivalence

    func testEveryAudienceSeesExactlyWhatThePolicyAdmits() {
        let profile = fullyStatedProfile()
        let policy = ProfileFieldPolicy.privateByDefault
            .with(.alias, .nearby)
            .with(.pronouns, .nearby)
            .with(.ageBand, .requester)
            .with(.interests, .venue)
            .with(.media, .requester)

        for audience in ProfileFieldPolicy.Audience.allCases {
            let preview = ProfilePreview.derive(profile: profile, policy: policy, audience: audience)
            let admitted = Set(policy.visibleFields(to: audience)).subtracting([.media])
            XCTAssertEqual(Set(preview.visibleValues.keys), admitted,
                           "\(audience): the preview's fields must equal the policy's visible set")
            XCTAssertEqual(policy.isVisible(.media, to: audience),
                           preview.primaryMediaObjectId != nil,
                           "\(audience): media presence must follow the policy")
        }
    }

    func testNoPreviewEverExposesAFieldThePolicyHides() {
        let profile = fullyStatedProfile()
        let policy = ProfileFieldPolicy.privateByDefault
        for audience in ProfileFieldPolicy.Audience.allCases {
            let preview = ProfilePreview.derive(profile: profile, policy: policy, audience: audience)
            for exposed in preview.visibleValues.keys {
                XCTAssertTrue(policy.isVisible(exposed, to: audience),
                              "\(audience) was shown \(exposed), which the policy hides")
            }
        }
    }

    func testTheDefaultPolicyShowsANearbyStrangerNothing() {
        let preview = ProfilePreview.derive(profile: fullyStatedProfile(),
                                            policy: .privateByDefault, audience: .nearby)
        XCTAssertTrue(preview.isEmpty,
                      "a field with no explicit policy must default to the most private audience")
    }

    func testTighteningThePolicyChangesTheNextPreviewImmediately() {
        let profile = fullyStatedProfile()
        let open = ProfileFieldPolicy.privateByDefault.with(.bio, .nearby)
        XCTAssertNotNil(ProfilePreview.derive(profile: profile, policy: open, audience: .nearby)
            .visibleValues[.bio])

        let tightened = open.with(.bio, .match)
        XCTAssertNil(ProfilePreview.derive(profile: profile, policy: tightened, audience: .nearby)
            .visibleValues[.bio],
            "a derived preview cannot lag the policy; a stored one would")
    }

    func testEditingTheProfileChangesTheNextPreviewImmediately() {
        let policy = ProfileFieldPolicy.privateByDefault.with(.alias, .nearby)
        let before = fullyStatedProfile()
        let after = before.with(.alias, .statement("renamed"))
        XCTAssertNotEqual(
            ProfilePreview.derive(profile: before, policy: policy, audience: .nearby).visibleValues[.alias],
            ProfilePreview.derive(profile: after, policy: policy, audience: .nearby).visibleValues[.alias],
            "a derived preview cannot lag the profile either")
    }

    func testUnsetFieldsNeverAppearButDeclinedOnesDo() {
        let policy = ProfileFieldPolicy.privateByDefault
            .with(.orientation, .nearby)
            .with(.genderIdentity, .nearby)
        let profile = LocalProfile.empty.with(.orientation, .preferNotToSay)

        let preview = ProfilePreview.derive(profile: profile, policy: policy, audience: .nearby)
        XCTAssertEqual(preview.visibleValues[.orientation], .preferNotToSay,
                       "an explicit decline is an answer (SRS-PRO-002) and must be shown as one")
        XCTAssertNil(preview.visibleValues[.genderIdentity],
                     "an unanswered field must not advertise its own absence")
    }

    func testThePreviewCarriesAnObjectIdentifierAndNeverImageBytes() {
        let policy = ProfileFieldPolicy.privateByDefault.with(.media, .nearby)
        let preview = ProfilePreview.derive(profile: fullyStatedProfile(),
                                            policy: policy, audience: .nearby)
        XCTAssertEqual(preview.primaryMediaObjectId, "object-1")
        XCTAssertNil(preview.visibleValues[.media],
                     "media must never be a preview VALUE; only an identifier (SRS-PRO-007)")
    }

    func testEveryFieldIsExpressibleInThePolicy() {
        // Guards the tests above: a field unreachable by any audience would
        // make the equivalence assertions pass vacuously for it.
        var policy = ProfileFieldPolicy.privateByDefault
        for field in ProfileField.allCases {
            policy = policy.with(field, .nearby)
        }
        XCTAssertEqual(Set(policy.visibleFields(to: .nearby)), Set(ProfileField.allCases))
    }

    // MARK: inclusivity

    func testTheThreeAnswerStatesAreDistinct() {
        XCTAssertFalse(FieldValue.unset.isAnswered)
        XCTAssertTrue(FieldValue.preferNotToSay.isAnswered,
                      "a decline is a deliberate answer, not an absence")
        XCTAssertTrue(FieldValue.statement("agender").isAnswered)
        XCTAssertNotEqual(FieldValue.unset, FieldValue.preferNotToSay)
    }

    func testBlankTextCollapsesToUnsetRatherThanAnEmptyStatement() {
        XCTAssertEqual(FieldValue.statement("   "), .unset)
        XCTAssertEqual(FieldValue.statement(""), .unset)
    }

    func testSelfDescriptionIsFreeTextNotAFixedOption() {
        // SRS-PRO-002 requires self-description, which a fixed vocabulary
        // cannot express.
        let selfDescribed = "genderqueer / femme-leaning, ask me"
        XCTAssertEqual(FieldValue.statement(selfDescribed).text, selfDescribed)
    }

    func testTheArticleNineFieldsAreMarkedAsSuch() {
        XCTAssertTrue(ProfileField.orientation.isSpecialCategory)
        XCTAssertTrue(ProfileField.genderIdentity.isSpecialCategory)
        XCTAssertTrue(ProfileField.bio.isSpecialCategory, "free text: the user decides what goes in it")
        XCTAssertTrue(ProfileField.media.isSpecialCategory, "an image: same reason")
        XCTAssertFalse(ProfileField.alias.isSpecialCategory)
    }

    // MARK: completeness and lifecycle

    func testAnEmptyProfileIsNotShowable() {
        let empty = LocalProfile.empty
        XCTAssertFalse(empty.isShowable, "SRS-PRO-010: an empty profile may not go Visible Nearby")
        XCTAssertEqual(empty.missingRequiredFields, [.alias, .ageBand])
    }

    func testDecliningSensitiveFieldsNeverMakesAProfileIncomplete() {
        var profile = LocalProfile.empty
            .with(.alias, .statement("Sam"))
            .with(.ageBand, .statement("30-34"))
        for field in ProfileField.allCases where field.isSpecialCategory {
            profile = profile.with(field, .preferNotToSay)
        }
        XCTAssertTrue(profile.isShowable,
                      "requiring Article 9 data to use the product is what prefer-not-to-say prevents")
    }

    func testPausingAndHidingSuppressVisibilityWithoutLosingData() {
        let complete = LocalProfile.empty
            .with(.alias, .statement("Sam"))
            .with(.ageBand, .statement("30-34"))
        XCTAssertTrue(complete.isShowable)

        for state in [LocalProfile.Lifecycle.paused, .hidden] {
            let suppressed = complete.withLifecycle(state)
            XCTAssertFalse(suppressed.isShowable, "\(state) must suppress visibility")
            XCTAssertEqual(suppressed.value(.alias).text, "Sam",
                           "\(state) must retain the profile intact (SRS-PRO-011)")
        }
    }

    func testMediaOrderIsPreservedAndTheFirstEntryIsPrimary() {
        let first = ProfileMedia(objectId: "obj-1", contentType: "image/jpeg",
                                 widthPixels: 640, heightPixels: 480, byteCount: 2048)
        let second = ProfileMedia(objectId: "obj-2", contentType: "image/png",
                                  widthPixels: 320, heightPixels: 240, byteCount: 1024)
        XCTAssertEqual(LocalProfile.empty.withMedia([first, second]).primaryMedia, first)
        // SRS-PRO-009: choosing a primary photo IS reordering.
        XCTAssertEqual(LocalProfile.empty.withMedia([second, first]).primaryMedia, second)
    }
}
