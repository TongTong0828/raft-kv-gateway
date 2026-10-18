# Distribution notices

The root LICENSE covers original project source. Dependency software keeps its own terms.

At build time Maven copies the resolved compile/runtime JARs into each module's ignored `target/original-dependencies/`. `scripts/LicenseBundle.java` reads their licensing resources and preserves the exact original paths and bytes inside individual ZIPs. This avoids the `META-INF/LICENSE` file versus `META-INF/license/` directory collision on case-insensitive filesystems. An index records the dependency JAR SHA-256 and extracted resource count. The final shaded JAR contains those archives, the original merged notices, and its runtime dependency inventory.

Some artifacts do not embed their upstream license text. The supplemental `resources/META-INF/third-party/index.json` identifies exact release sources and hashes for those texts. Already shaded gRPC and Ratis libraries include additional native-library notices; these are retained, including BoringSSL and Tomcat Native terms. A runtime dependency inventory lists outer Maven artifacts and does not claim to enumerate every embedded native component.

Inspect a completed build with `python3 scripts/audit-release.py`. Container images also contain an Eclipse Temurin JRE and Ubuntu packages governed by the base-image distribution's licenses.
