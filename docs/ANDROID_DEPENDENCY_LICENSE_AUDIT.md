# Android release runtime dependency license inventory

**Snapshot:** 2026-11-01, current `main` after ACT-0074. This is an engineering inventory from the resolved `:app:releaseRuntimeClasspath`; it is not a completed legal review or a release clearance. The full per-coordinate mapping is in [ANDROID_DEPENDENCY_LICENSE_MAP.csv](ANDROID_DEPENDENCY_LICENSE_MAP.csv).

## Resolved scope and result

Gradle resolved **132 artifact files across 129 unique runtime modules** for the release variant. The local Gradle cache initially contained a Maven POM for 84 modules. The other 45 exact POMs were fetched from Google Maven or Maven Central and parsed. All 129 coordinates now have a POM; 126 declare a license directly. `com.google.guava:listenablefuture:1.0` omits its own license field but inherits Apache 2.0 from its declared Guava parent POM. The two SLF4J POMs also omit license metadata; the upstream SLF4J license page states MIT. This resolves metadata/source mapping for the full runtime module list, but does not prove every required copyright/NOTICE text is present in the built APK/AAB.

POM declarations among the 82 cached modules:

| POM declaration | Module count |
| --- | ---: |
| The Apache Software License, Version 2.0 | 65 |
| Apache-2.0 | 8 |
| Android Software Development Kit License | 4 |
| Play Core Software Development Kit Terms of Service | 1 |
| Play Integrity API Terms of Service | 1 |
| MIT-0 | 1 |
| The Apache License, Version 2.0 | 1 |
| `license` (SQLCipher's POM label is too generic to identify the text) | 1 |

Of the original cache entries, the two POMs without license entries are `org.slf4j:slf4j-api:2.0.17` and `org.slf4j:slf4j-nop:2.0.17`. The upstream SLF4J licensing page states that SLF4J source and binaries are distributed under MIT. `com.google.guava:listenablefuture:1.0` has no direct license element; its parent `com.google.guava:guava-parent:26.0-android` declares Apache 2.0. SQLCipher 4.20.0 also has only a generic POM license label, but its Community BSD-style notice and full license text are available in the app's About → Open-source licenses screen (ACT-0071).

The four Android SDK license declarations are on Google Play Services `play-services-base`, `play-services-basement`, `play-services-tasks`, and Firebase `firebase-auth-interop`. The Play Core and Play Integrity modules declare their respective Google terms. These are vendor terms rather than SPDX-style open-source licenses; their use and any required disclosures need a separate release review. This inventory does not make a legal determination about their applicability.

The 44 direct license declarations found in the fetched POMs add 43 Apache 2.0 variants and one BSD-3-Clause declaration. Combined with the cached declarations, the metadata classification spans all 129 resolved modules; the two Google SLF4J components are mapped through the upstream MIT notice, while Guava ListenableFuture inherits its Apache 2.0 parent declaration.

## Coordinates fetched from upstream POMs

The POMs for the following 45 modules were not present in the local Gradle cache. They were fetched by exact group/artifact/version from Google Maven, then Maven Central as fallback. Each returned POM directly declared Apache 2.0 except `androidx.datastore:datastore-preferences-external-protobuf:1.1.7`, which declared BSD-3-Clause; `com.google.guava:listenablefuture:1.0` has no declaration and is resolved through its parent POM. AndroidX entries are listed individually because a group-level assumption is not a substitute for checking the distributed artifacts. The [CSV map](ANDROID_DEPENDENCY_LICENSE_MAP.csv) enumerates the complete 129-module set, POM metadata, evidence method, and exact POM/terms URL.

```text
androidx.activity:activity:1.10.1
androidx.activity:activity-compose:1.10.1
androidx.activity:activity-ktx:1.10.1
androidx.annotation:annotation-experimental:1.5.0
androidx.annotation:annotation-jvm:1.9.1
androidx.arch.core:core-common:2.2.0
androidx.arch.core:core-runtime:2.2.0
androidx.autofill:autofill:1.0.0
androidx.collection:collection-jvm:1.5.0
androidx.collection:collection-ktx:1.5.0
androidx.compose.material:material-icons-core-android:1.7.8
androidx.compose.material3:material3-android:1.3.2
androidx.core:core-viewtree:1.0.0
androidx.customview:customview:1.0.0
androidx.customview:customview-poolingcontainer:1.0.0
androidx.datastore:datastore-android:1.1.7
androidx.datastore:datastore-core-android:1.1.7
androidx.datastore:datastore-core-okio-jvm:1.1.7
androidx.datastore:datastore-preferences-android:1.1.7
androidx.datastore:datastore-preferences-core-android:1.1.7
androidx.datastore:datastore-preferences-external-protobuf:1.1.7
androidx.datastore:datastore-preferences-proto:1.1.7
androidx.emoji2:emoji2:1.4.0
androidx.graphics:graphics-path:1.0.1
androidx.interpolator:interpolator:1.0.0
androidx.loader:loader:1.0.0
androidx.profileinstaller:profileinstaller:1.4.0
androidx.savedstate:savedstate:1.2.1
androidx.savedstate:savedstate-ktx:1.2.1
androidx.startup:startup-runtime:1.1.1
androidx.tracing:tracing:1.0.0
androidx.versionedparcelable:versionedparcelable:1.1.1
androidx.viewpager:viewpager:1.0.0
com.google.errorprone:error_prone_annotations:2.26.0
com.google.guava:listenablefuture:1.0
com.squareup.okhttp3:okhttp:4.12.0
javax.inject:javax.inject:1
org.jetbrains.kotlin:kotlin-android-extensions-runtime:1.9.22
org.jetbrains.kotlin:kotlin-parcelize-runtime:1.9.22
org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.21
org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.21
org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.8.1
org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.8.1
org.jetbrains:annotations:23.0.0
org.jspecify:jspecify:1.0.0
```

## Sources and next acceptance

- [AndroidX upstream repository](https://android.googlesource.com/platform/frameworks/support/) is the source for Jetpack artifacts; verify distributed artifact notices against the exact resolved versions.
- [Kotlin upstream license materials](https://github.com/JetBrains/kotlin/tree/master/license) cover Kotlin runtime notices; verify the resolved runtime components and bundled third-party notices.
- [kotlinx.serialization upstream](https://github.com/Kotlin/kotlinx.serialization) identifies its project license; verify the exact 1.8.1 artifacts and notices.
- [SLF4J license](https://www.slf4j.org/license.html) supplies the MIT mapping omitted from the two POMs.
- [Guava parent POM](https://repo.maven.apache.org/maven2/com/google/guava/guava-parent/26.0-android/guava-parent-26.0-android.pom) supplies Apache 2.0 metadata inherited by `listenablefuture:1.0`.
- [DataStore external protobuf POM](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-preferences-external-protobuf/1.1.7/datastore-preferences-external-protobuf-1.1.7.pom) declares BSD-3-Clause.
- [OkHttp 4.12.0 license](https://github.com/square/okhttp/blob/parent-4.12.0/LICENSE.txt) supplies the project license text.
- [Android SDK terms](https://developer.android.com/studio/terms) and [Google Mobile Developer Services terms](https://developers.google.com/mobile/terms) are separate from open-source attributions.
- SQLCipher Community notice sources and the in-app attribution are recorded in [ACT-0071](ACTION_LOG.md).

MEM-12.11/P1 remains open until exact license texts and copyright/NOTICE obligations are reconciled with the full runtime dependency tree, bundled notices are inspected in the release APK/AAB, Google vendor terms are reviewed for the selected services, and a signed release artifact is checked. Exact POM/source mapping alone is not release clearance.
