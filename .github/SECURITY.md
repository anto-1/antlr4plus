# Security

ANTLR4+ compiles and runs code embedded in grammars, along with referenced project
Java or Kotlin sources. Preview is blocked unless IntelliJ marks the project as
trusted. Only trust projects whose code you are willing to execute.

Code generation and semantic grammar analysis also require project trust: they
can follow grammar imports and token-vocabulary paths, and Go generation may run
`gofmt` from the local toolchain. Syntax diagnostics remain available without trust.

Trusted preview code runs inside the IDE process with its permissions. It is not
sandboxed or restricted by an API blocklist.

Session-only SHA-256 checks detect unmarked or modified generated files, including
`.class` files, before reuse. They guard against stealth bytecode injection, not
malicious source changes. Changing a trusted grammar or referenced source can
still generate and execute arbitrary code with the IDE's permissions. Timeouts
and output-path checks improve reliability; none of these controls provide
isolation. Runtime components are bundled rather than downloaded during preview.

## ANTLR 4.13.2 advisories

The plugin's trust boundary determines the applicability of these upstream reports:

| Advisory | ANTLR4+ handling |
| --- | --- |
| [CVE-2026-13500](https://www.cve.org/CVERecord?id=CVE-2026-13500): grammar actions | Executing grammar actions is an intended feature of trusted preview. Trust is checked before compilation and cached-code loading. |
| [CVE-2026-13501](https://www.cve.org/CVERecord?id=CVE-2026-13501): Go formatter | Generation requires trust before ANTLR can invoke the local formatter. |
| [CVE-2026-13502](https://www.cve.org/CVERecord?id=CVE-2026-13502): Maven dependency cache | The affected Maven plugin is not bundled. Distribution and SBOM checks enforce its exclusion. |
| [CVE-2026-13503](https://www.cve.org/CVERecord?id=CVE-2026-13503): token vocabulary paths | Untrusted files receive syntax analysis only; loading vocabulary files and imported grammars requires trust. |

These are integration safeguards, not patches to upstream ANTLR. A trusted project
can execute code and access files with the IDE's permissions, as described above.

## Reporting a vulnerability

Please do not open a public issue for an undisclosed vulnerability. Email
`i00@tutanota.com` with reproduction steps, impact, and affected versions. Allow time
for investigation and a coordinated fix before public disclosure.
