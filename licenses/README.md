# Upstream license material

This directory contains the third-party licenses and notices shipped with the
plugin. The build includes it in every plugin ZIP and under
`META-INF/licenses/` in the plugin JAR. The project's Apache-2.0 license does
not replace upstream licenses.

| File or directory | Upstream source |
| --- | --- |
| `ANTLR3-LICENSE.txt` | [ANTLR 3.5.3](https://www.antlr3.org/license.html) |
| `ANTLR4-LICENSE.txt` | [ANTLR 4.13.2](https://github.com/antlr/antlr4/blob/4.13.2/LICENSE.txt) |
| `ANTLR-SOURCE-NOTICES.txt` | Copyright notices retained from the bundled ANTLR-family source artifacts |
| `STRINGTEMPLATE4-LICENSE.txt` | [StringTemplate 4.3.4](https://github.com/antlr/stringtemplate4/blob/ST4-4.3.4/LICENSE.txt) |
| `ANTLR4-INTELLIJ-ADAPTOR-LICENSE.txt` | [ANTLR4 IntelliJ adaptor 0.1](https://github.com/antlr/antlr4-intellij-adaptor/blob/master/LICENSE) |
| `ABEGO-TREELAYOUT-LICENSE.txt` | [abego TreeLayout 1.0.3](https://github.com/abego/treelayout/blob/v1.0.3/org.abego.treelayout/src/main/java/org/abego/treelayout/doc-files/LICENSE.TXT) |
| `TWELVEMONKEYS-LICENSE.txt` | [TwelveMonkeys ImageIO 3.12.0](https://github.com/haraldk/TwelveMonkeys/blob/twelvemonkeys-3.12.0/LICENSE.txt) |
| `TWELVEMONKEYS-THIRD-PARTY-NOTICES.txt` | Source notices for [TwelveMonkeys ImageIO 3.12.0](https://github.com/haraldk/TwelveMonkeys/tree/twelvemonkeys-3.12.0), including its embedded Apache Batik code |
| `IMAGEMAGICK-LICENSE.txt` | [ImageMagick License](https://github.com/ImageMagick/ImageMagick6/blob/f9e9aaed2d4b15ccbdc3d031f7956509b2d35cf2/LICENSE), applicable to code identified by TwelveMonkeys as adapted from ImageMagick |
| `DEVICON-LICENSE.txt` | [Devicon 2.17.0](https://github.com/devicons/devicon/blob/v2.17.0/LICENSE), source of `java.svg` and `kotlin.svg` |
| `ANTLR-INTELLIJ-LICENSE.txt` | [ANTLR v4 IntelliJ Plugin](https://github.com/antlr/intellij-plugin-v4/blob/master/LICENSE) |
| `ANTLR-INTELLIJ-GRAMMAR-NOTICES.txt` | Notices retained from the bundled ANTLR grammar sources |
| `ANTLR-KOTLIN-LICENSE-BSD.txt` | [ANTLR Kotlin 1.0.9](https://github.com/Strumenta/antlr-kotlin/blob/1.0.9/LICENSE-BSD) |
| `ANTLR4-RUST-TARGET-LICENSE.txt` | Reviewed source-only Rust target at [antlr4rust/antlr4 commit 9b5bd1e](https://github.com/antlr4rust/antlr4/tree/9b5bd1ef379c2fe293edf70196389ac01832df72) |
| `EPL-2.0.txt` | [ECJ 3.44.0](https://github.com/eclipse-jdt/eclipse.jdt.core/tree/745799bf0e1c8bd0f2b25f36880bd1c3505e21a3/org.eclipse.jdt.core.compiler.batch) under the [Eclipse Public License 2.0](https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.txt) |
| `KOTLIN-NOTICE.txt` | [Kotlin 2.3.0 NOTICE](https://github.com/JetBrains/kotlin/blob/v2.3.0/license/NOTICE.txt) |
| `KOTLINX-COROUTINES-NOTICE.txt` | [kotlinx.coroutines 1.10.1 NOTICE](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.1/license/NOTICE.txt) |
| `kotlin/` | Unmodified `kotlinc/license/` from the [Kotlin 2.3.0 compiler distribution](https://github.com/JetBrains/kotlin/releases/tag/v2.3.0) |
| `SKIKO-NOTICE.txt` | [Skiko 0.9.4 NOTICE](https://github.com/JetBrains/skiko/blob/v0.9.4/NOTICE) |
| `SKIA-LICENSE.txt` | [Skia license](https://github.com/google/skia/blob/main/LICENSE) |
| `skia/m116-47d3027-1/` | License and COPYING files from the [Skia package used by Skiko 0.8.4](https://github.com/JetBrains/skia-pack/releases/tag/m116-47d3027-1) |
| `skia/m132-9b3c42e2f9-3/` | License and COPYING files from the [Skia package used by Skiko 0.9.4](https://github.com/JetBrains/skia-pack/releases/tag/m132-9b3c42e2f9-3) |

The generated Kotlin lexer and parser in `src/main/gen` come from the
Apache-2.0 [Kotlin grammar](https://github.com/shadrina/kotlin-grammar-antlr4)
by Anastasiya Shadrina and contributors. The per-variant CycloneDX SBOM records
the exact resolved Maven dependencies.

The bundled `mode.png` and `parser-rule.png` assets match the original ANTLR v4
IntelliJ Plugin; `lexer-rule_16_16_dark.png` is a derivative. Their BSD terms
are in `ANTLR-INTELLIJ-LICENSE.txt`. JetBrains-derived SVGs retain their
Apache-2.0 copyright headers in place.

ECJ source is available from its pinned upstream revision above and as the
[3.44.0 source artifact](https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.44.0/ecj-3.44.0-sources.jar).

The Kotlin directory is the upstream distribution's complete inventory and may
describe components unused by ANTLR4+'s JVM preview compiler. Keep its license
texts together with its README. Corresponding Kotlin source is available at
[tag v2.3.0](https://github.com/JetBrains/kotlin/tree/v2.3.0).

The Skia inventories retain their upstream paths from the Linux release packages.
Additional license files for FreeType, ICU, libjpeg-turbo, Expat, DNG SDK, Piex,
and Wuffs come from the exact revisions in each Skia version's `DEPS` file;
`SOURCES.txt` in each directory records their URLs. FreeType is distributed under
the FreeType License (FTL) option. The original alternative license text is retained
alongside it. Source versions are recorded in the corresponding
[Skia source tree](https://github.com/JetBrains/skia) and Skia package release tags.

The font license remains at `src/main/resources/fonts/OFL.txt` and is packaged as
`fonts/OFL.txt`. Apache-only dependencies use the root Apache-2.0 text. When
changing dependencies, refresh affected notices and review every variant's SBOM
and ZIP contents.
