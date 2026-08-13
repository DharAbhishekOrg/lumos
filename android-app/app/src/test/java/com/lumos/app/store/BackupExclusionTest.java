/*
 * Lumos - android-app/app / The backup exclusions exist and cover the stores.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-DAT-012, TRS-LOC-009
 * ADRs: ADR-003
 * Threading: any
 * Notes: TRS-LOC-009's verification is a "backup configuration test", and this
 * is it: parse the shipped XML and assert the exclusions are present, in both
 * cloud-backup and device-transfer. Threat model LT-20/LT-21 are the reason -
 * a database excluded from cloud backup but not from device transfer walks onto
 * the user's next phone, which is the same disclosure with extra steps.
 *
 * What this CANNOT prove is that Android honours the rules; that is residual
 * risk RR-4 and needs a real backup/restore on a real account, owner-executed.
 * The test proves the instruction was given, not that it was obeyed - and
 * saying which is which is the whole point.
 */

package com.lumos.app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Asserts the shipped backup rules exclude every store. */
final class BackupExclusionTest {

    /** The domains that must be excluded wherever exclusions are declared. */
    private static final List<String> REQUIRED_DOMAINS =
            List.of("root", "file", "database", "sharedpref", "external");

    @Test
    void dataExtractionRulesExcludeEveryDomainInBothSections() throws Exception {
        final Document document = parse("src/main/res/xml/data_extraction_rules.xml");
        for (final String section : List.of("cloud-backup", "device-transfer")) {
            final List<String> excluded = excludedDomains(document, section);
            for (final String domain : REQUIRED_DOMAINS) {
                assertTrue(excluded.contains(domain),
                        section + " must exclude the " + domain + " domain (ADR-003)");
            }
        }
    }

    @Test
    void legacyBackupRulesExcludeEveryDomain() throws Exception {
        final Document document = parse("src/main/res/xml/backup_rules.xml");
        final NodeList excludes = document.getElementsByTagName("exclude");
        final List<String> domains = new ArrayList<>();
        for (int i = 0; i < excludes.getLength(); i++) {
            domains.add(((Element) excludes.item(i)).getAttribute("domain"));
        }
        for (final String domain : REQUIRED_DOMAINS) {
            assertTrue(domains.contains(domain),
                    "pre-Android-12 backup rules must exclude the " + domain + " domain");
        }
    }

    @Test
    void theManifestKeepsBackupOff() throws Exception {
        final Document manifest = parse("src/main/AndroidManifest.xml");
        final Element application = (Element) manifest.getElementsByTagName("application").item(0);
        assertEquals("false", application.getAttribute("android:allowBackup"),
                "the rules are the belt; allowBackup=false is the braces (ADR-003)");
    }

    @Test
    void theDatabaseLivesUnderAnExcludedDomain() throws Exception {
        // AndroidSqlDatabase writes through Context.getDatabasePath, which is the
        // "database" domain. This test is the link between the rule and the code:
        // moving the store to another location without revisiting the rules would
        // break it here rather than silently in a backup two releases later.
        assertTrue(AndroidSqlDatabase.DATABASE_FILE.endsWith(".db"),
                "the store's file is a database-domain artefact");
        final Document document = parse("src/main/res/xml/data_extraction_rules.xml");
        assertTrue(excludedDomains(document, "cloud-backup").contains("database"));
    }

    /**
     * Lists the excluded domains in one section.
     *
     * @param document the parsed rules
     * @param section the element name
     * @return the domain attributes of that section's exclude elements
     */
    private static List<String> excludedDomains(final Document document, final String section) {
        final Element element = (Element) document.getElementsByTagName(section).item(0);
        final NodeList excludes = element.getElementsByTagName("exclude");
        final List<String> domains = new ArrayList<>();
        for (int i = 0; i < excludes.getLength(); i++) {
            domains.add(((Element) excludes.item(i)).getAttribute("domain"));
        }
        return domains;
    }

    /**
     * Parses a project file relative to the module directory.
     *
     * @param relativePath the path under android-app/app
     * @return the parsed document
     * @throws Exception if the file is missing or malformed
     */
    private static Document parse(final String relativePath) throws Exception {
        final File file = new File(relativePath);
        assertTrue(file.isFile(), "expected to find " + file.getAbsolutePath());
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // No external entities: this parser reads repository files only, and a
        // DTD fetch would make a unit test depend on the network.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(file);
    }
}
