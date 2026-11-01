# Android release runtime dependency license inventory

**Snapshot:** 2026-11-01, after the OSS Licenses integration. This engineering inventory is not legal advice or release clearance. The complete exact-coordinate map is [ANDROID_DEPENDENCY_LICENSE_MAP.csv](ANDROID_DEPENDENCY_LICENSE_MAP.csv).

## Resolved scope and attribution

Gradle resolved **176 artifacts across 169 unique modules** in `:app:releaseRuntimeClasspath`. Exact POMs were fetched from Google Maven or Maven Central and parsed for all 169 coordinates. There are 166 direct POM license declarations. `com.google.guava:listenablefuture:1.0` inherits Apache 2.0 from its parent POM; the two SLF4J modules are mapped to MIT using the upstream project license page. SQLCipher's POM has a generic license label and is separately mapped to Zetetic's Community license terms; its full required attribution text remains in Memora's existing license screen.

| Mapped license / terms evidence | Modules |
| --- | ---: |
| Apache 2.0 variants, direct POM labels | 156 |
| Android Software Development Kit License | 5 |
| MIT via SLF4J upstream license page | 2 |
| BSD-3-Clause | 1 |
| MIT-0 | 1 |
| Play Core SDK Terms of Service | 1 |
| Play Integrity API Terms of Service | 1 |
| Apache 2.0 inherited from Guava parent POM | 1 |
| SQLCipher Community terms (generic POM label) | 1 |

Google SDK/Play terms are vendor terms, not open-source licenses; this report does not determine which obligations apply to Memora. The CSV records the resolved coordinates, POM license label, mapped evidence, and source URL for each module.

## In-app notices and build evidence

The app retains the full SQLCipher Community license text and adds a localized button from the existing license screen to Google's OSS Licenses activity. Official setup and scope are documented by [Google](https://developers.google.com/android/guides/opensource); the Gradle plugin gathers POM license metadata and packages resources for the activity. For this build, `debugOssLicensesTask` and `releaseOssLicensesTask` completed successfully. `aapt2 dump resources` found `raw/third_party_license_metadata` and `raw/third_party_licenses` in both the debug and release APKs. The `UiShellTest` clicks the license-view button under Robolectric; no user data or live API is involved.

The activity displays POM-provided titles/links and license data collected by Google's tooling. This is not yet a manual review of every copyright/NOTICE text in the final APK/AAB, nor a confirmation that all vendor terms are met. The available release artifact is still unsigned.

## Current P1 acceptance

MEM-12.11/P1 remains open until required copyright/NOTICE text and vendor disclosures are reconciled with the packaged release, the signed artifact is verified, and remaining storage fault/recovery checks pass. Exact module mapping, a generated attribution screen, and APK resource presence do not establish full compliance or complete P1 acceptance.

Primary sources:

- [Google OSS notices integration](https://developers.google.com/android/guides/opensource)
- [Android SDK terms](https://developer.android.com/studio/terms)
- [Google Mobile Developer Services terms](https://developers.google.com/mobile/terms)
- [SLF4J license](https://www.slf4j.org/license.html)
- [Guava parent POM](https://repo.maven.apache.org/maven2/com/google/guava/guava-parent/26.0-android/guava-parent-26.0-android.pom)
- [Zetetic SQLCipher license terms](https://www.zetetic.net/sqlcipher/license/)
