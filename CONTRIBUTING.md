# Contributing to MnxMindMaker

Use an issue to describe a concrete capability, an example input, expected output,
and a way to verify it. Submit implementation changes through pull requests with
relevant tests and documentation. Identify schema compatibility changes explicitly.

For the shared knowledge expansion, start with
[the architecture and milestones](docs/Shared-Knowledge-Architecture.md) and
[the contribution protocol](tools/knowledge-core/PROTOCOL.md).
The next milestones are Android-native review/persistence and a first source-grounded
research adapter. Keep adapters independent of a specific model provider where possible.

Run the tests relevant to your change:

```bash
# Local companion; Python 3.11+ with no third-party dependencies
cd tools/knowledge-core
python -m unittest discover -s tests -v

# Android project, from repository root with JDK 17 and Android SDK 34 configured
./gradlew testDebugUnitTest
```

Code pull requests and knowledge proposals serve different purposes. A model's
extracted claims should go through the knowledge review workflow, preserving exact
source locators, uncertainty, and contributor information. Commit synthetic or
permitted test fixtures; keep private material, credentials, and production graph
databases out of the repository.

Document what is executable today and what remains planned. Prefer a small working
slice with measurable results to unverified claims about understanding or autonomy.
