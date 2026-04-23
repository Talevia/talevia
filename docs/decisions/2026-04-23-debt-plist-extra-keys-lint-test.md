## 2026-04-23 — Lint the Compose Desktop macOS plist extra-keys XML at build / test time (VISION §5.6)

**Context.** `apps/desktop/build.gradle.kts` was injecting ~40 lines of
raw XML into `Compose Desktop nativeDistributions.macOS.infoPlist
.extraKeysRawXml`, unchecked. The only ways a typo would surface:

- a `./gradlew :apps:desktop:packageDmg` followed by
- a human noticing Finder double-clicks on a `.talevia` bundle do
  nothing instead of launching the app.

Neither trips in any automated pipeline. A missed `</dict>` closing tag,
a duplicated `<key>`, or a `<string>true</string>` where `<true/>`
belongs silently poisons Launch Services and surfaces only when someone
cuts a release and tries the end-to-end Finder flow. The
`bundle-mac-launch-services` cycle (2026-04-23) that added the UTI
declarations flagged this exact concern explicitly — no automated guard.

Rubric §5.6: a well-formedness regression costs a full release
re-cut. A 4-line build-time / test-time check prevents it.

**Decision.** Extract the raw XML fragment into a resource file
`apps/desktop/src/main/resources/macos-info-plist-extra.xml` that:

1. `apps/desktop/build.gradle.kts` reads via
   `file("src/main/resources/macos-info-plist-extra.xml").readText()`
   at configuration time — same path, same bytes that used to be inlined
   in the `.kts` string literal.
2. A new `apps/desktop/src/test/kotlin/io/talevia/desktop/
   MacOsInfoPlistExtraXmlTest.kt` also reads, wraps in a minimal
   `<plist version="1.0"><dict>…</dict></plist>` envelope, and parses
   with `javax.xml.parsers.DocumentBuilder`. Well-formedness failures
   fail the test; structural invariants are then asserted by DOM walk.

Five cases guard the file:

- **Well-formedness + top-level keys**: the document parses and has
  exactly the two top-level keys macOS needs (`UTExportedTypeDeclarations`,
  `CFBundleDocumentTypes`). Catches "someone dropped a `<key>`" and
  "someone added a stray closing tag".
- **UTI values present**: the `io.talevia.project` UTI, the `talevia`
  extension, and the `com.apple.package` conformance all appear as
  `<string>` text content.
- **`LSTypeIsPackage` is `<true/>`**: walks `<key>LSTypeIsPackage</key>`
  and asserts the next element sibling is literally `<true/>`. Catches
  the sneakier `<string>true</string>` swap that macOS silently treats
  as a string key — i.e. Finder's double-click handler quietly stops
  working even though the plist is valid XML.
- **No duplicated top-level keys**: Launch Services takes the last
  occurrence of a dup'd key, so duplicate `<key>` entries compound
  bugs instead of failing loud. The test counts them and refuses > 1.
- **Positive control**: a known-broken `<plist><dict><key>Unclosed</key></plist>`
  must throw `SAXParseException`. If a future JVM ships a permissive
  default DocumentBuilder, this fires and we add explicit features.

The parser config sets `disallow-doctype-decl = true` to keep the check
offline (no DTD fetch, no external entity resolution). Pure
well-formedness.

This cycle does NOT invoke `plutil -lint`. `plutil` is macOS-only and
CI-dependent; using the JDK's XML parser keeps the test cross-platform
and zero-dependency. A follow-up that wants semantic plist validation
(type coercion, key-value pairing) could add a `plutil -lint` gate
behind a `@EnabledOnOs(MAC)` guard.

**Axis.** "Info.plist additions." Any future cycle that extends the XML
(a new document type, a new URL scheme handler, a new UTI) reads the
same resource file and picks up the guard automatically. The test
covers a whitelist of known-required keys — new keys need an
accompanying assertion added.

**Alternatives considered.**

- **Inline string + test that re-declares it.** Would sidestep the
  resource file but duplicate the XML between test and build. Any
  divergence in the string literal is a silent test bypass. Rejected —
  single source of truth matters here.

- **`plutil -lint` subprocess in the test.** Native plist parser, would
  catch semantic issues the generic XML parser doesn't. Rejected: macOS-
  only, CI-dependent, and the semantic errors it catches aren't the
  failure modes we've actually seen (dropped closing tags, typo'd key
  names, `<string>true</string>`-vs-`<true/>`). Generic XML parsing +
  DOM walk covers every real-world regression pattern.

- **Add a separate `:apps:desktop:plistLint` gradle task.** Would run
  the check outside the unit-test lifecycle. Rejected: gradle tasks
  don't participate in `./gradlew test`; the test runner is the right
  lifecycle. A dedicated task would be extra infrastructure for zero
  incremental coverage.

- **Run the test as part of `:apps:desktop:assemble`.** Would be ideal,
  but `assemble` in Kotlin's jvm plugin doesn't depend on `test`.
  Adding that dependency is a wider policy change — rejected for this
  cycle; the `./gradlew :apps:desktop:test` invocation the rest of the
  project already runs covers it.

**Coverage.** `:apps:desktop:test` green (5 new test cases in
`MacOsInfoPlistExtraXmlTest`). `:apps:desktop:assemble` green
(configuration-time `file(...).readText()` loads and passes to
`extraKeysRawXml`). `ktlintCheck` green.

**Registration.** No tool or AppContainer change. Build config +
test-only.
