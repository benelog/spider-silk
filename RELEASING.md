# Releasing

A release is a `v*` tag on `main`, and `.github/workflows/release.yml` turns the tag into a Maven Central deployment and a GitHub Release.
A version on Central is permanent, so every step before the tag is a check that nothing is left behind.

## Every release

1. Set the version in `gradle.properties`.
   It is the one place the build reads the version from, and `spider-silk-gradle-plugin` reads it from there too.
2. Run `./gradlew verifyVersionReferences`.
   It names every file that still writes the old version, and each one is edited by hand:
   - `README.md`
   - `manual/antora.yml` (`project-version`, which every manual page reads)
   - `spider-silk-maven-parent/pom.xml`
   - `skills/spider-silk/SKILL.md`, `skills/spider-silk/references/setup.md`, and `skills/spider-silk/references/testing.md`
   - `.claude-plugin/plugin.json` and `.claude-plugin/marketplace.json`
3. In `CHANGELOG.md`, rename `## [Unreleased]` to `## [x.y.z] - YYYY-MM-DD`, open a new empty `## [Unreleased]` above it, and update the compare links at the bottom.
   The workflow copies that section into the GitHub Release and fails when it is missing.
4. Run `./gradlew build`, commit, and push to `main`.
5. Tag the commit and push the tag:
   ```bash
   git tag vx.y.z
   git push origin vx.y.z
   ```
   The workflow checks that the tag matches `gradle.properties`, builds, signs every publication into `build/staging-deploy`, uploads the zip of it to the Central Portal, waits for validation, and creates the GitHub Release.
6. Open [Deployments](https://central.sonatype.com/publishing/deployments) on the Central Portal.
   The deployment `spider-silk-x.y.z` is `VALIDATED` and waits there, because the workflow uploads it as `USER_MANAGED`.
   Check the component list, then press **Publish**.
   **Drop** discards the deployment instead, and the tag can then be deleted and pushed again.
7. Publication takes up to half an hour.
   The release is done once `https://repo1.maven.org/maven2/net/benelog/spidersilk/spider-silk-core/x.y.z/` lists the jars.

## A dry run without Central

`./gradlew cleanStagingRepository`, followed by `./gradlew centralBundle` as a separate command, writes the same bundle the workflow uploads to `build/central-bundle-x.y.z.zip`.
Without `SIGNING_KEY` in the environment the signing tasks are skipped, so the bundle is unsigned but otherwise complete.
The Central Portal's **Publish Component** page accepts that zip by hand for validation, and **Drop** removes it afterwards.

## One-time setup

These exist already, and are written down for the day one of them has to be replaced.

- **The namespace.** `net.benelog` is verified on the Central Portal by a DNS TXT record on `benelog.net`, which covers `net.benelog.spidersilk`.
  The namespace belongs to the maintainer's portal account, and the portal treats a different sign-in method as a different account, even for the same email.
- **The signing key.** An Ed25519 key, published to `keyserver.ubuntu.com` so Central can check the signatures.
  Its ASCII-armored private half (`gpg --armor --export-secret-keys <fingerprint>`) is the `SIGNING_KEY` repository secret, and its passphrase is `SIGNING_PASSWORD`.
  The maintainer's local copy of both is `.secrets/`, which `.gitignore` keeps out of every commit.
- **The portal token.** A user token generated under the portal's account menu, **View User Tokens**.
  Its username and password are the `CENTRAL_USERNAME` and `CENTRAL_PASSWORD` repository secrets.
