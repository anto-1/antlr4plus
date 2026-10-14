# ANTLR4+

<!-- Plugin description -->
The modern ANTLR4 workbench for JetBrains IDEs.
<!-- Plugin description end -->

## Install and try it

Install from **Settings → Plugins → Marketplace**. Plugin ID: `com.antlr4plus`.

Right-click an input file in the Project view, choose **Rename and Bind**, and pick
a `.g4` grammar. Select the new `.g4p` file. The preview spins up on the fly.

> [!NOTE]
> Selecting a bound `.g4p` file in the Project view can compile and run embedded
> Java or Kotlin code. A malicious project you mark as trusted can therefore
> execute arbitrary project code. Preview only projects you trust.

Bindings are stored on each `.g4p` file as project-relative metadata; its contents
stay plain text. **Rename and Bind** turns `sample.json` into `sample.json.g4p`.

Supported IntelliJ Platform builds:

- **232–242:** `legacy`, build JDK 17
- **243–252:** `modern`, build JDK 21
- **253+:** `newer`, build JDK 21

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](docs/CONTRIBUTING.md) to send an
improvement.

## License

[Apache License 2.0](LICENSE.md). Required third-party licenses and notices are
in [licenses/](licenses/).
