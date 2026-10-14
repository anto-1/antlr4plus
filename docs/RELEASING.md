# Releasing ANTLR4+

1. Update the three-segment base `pluginVersion` in `gradle.properties` and the
   release entry in `CHANGELOG.md`. Refresh affected notices when dependencies
   change. Builds append the Marketplace product code to that base version:
   `.1` for `legacy`, `.2` for `modern`, and `.3` for `newer`.
2. Install JDK 17 and 21. Run:

   ```bash
   ./gradlew buildAllVariants
   ```

   This builds, tests, and verifies every ZIP and SBOM, then verifies the
   generated SHA-256 files. To repeat artifact verification without rebuilding:

   ```bash
   ./gradlew verifyReleaseDistributions
   ```

3. Run Plugin Verifier with isolated outputs:

   ```bash
   set -e
   for variant in legacy modern newer; do
     AGENT_BUILD_DIR="$PWD/build/verifier/$variant" \
       ./gradlew verifyPlugin -PbuildVariant="$variant"
   done
   ```

   Review `build/verifier/*/reports/pluginVerifier/`. Install each ZIP and test
   grammar editing, Java/Kotlin previews, target generation, parse trees, image
   export, and untrusted-project blocking.
4. Commit and push the release changes; require green CI. Tag that commit and
   attach the ZIPs, SBOMs, and checksums.

A newer build made with `-PbundleComposeFallback=true` requires separate
compatibility testing. Verify it with the same property:

```bash
./gradlew verifyDistribution -PbuildVariant=newer -PbundleComposeFallback=true
```

Optionally archive the tagged commit:

```bash
git archive --format=zip --prefix=antlr4plus/ \
  --output=build/distributions/antlr4plus-source.zip HEAD
```

Marketplace tasks read `CERTIFICATE_CHAIN`, `PRIVATE_KEY`,
`PRIVATE_KEY_PASSWORD`, and `PUBLISH_TOKEN` from the environment. Never commit
credentials.
