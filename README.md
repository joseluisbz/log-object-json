# Log Object JSON

Desktop tool that finds an **object printed in a log line** (Java `toString()`, C#, Python, Rust…),
turns it into a **navigable tree** and **valid JSON**, and lets you **compare** it with the same object
from another log.

No class definitions are needed: the object is rebuilt dynamically from its printed text.

```
2026-01-01 12:00:00 INFO [main] Service: Order(id=7, customer=Ann, lines=[Line(prod=A, qty=2)], extra={k=v})
```
↓
```json
{
    "id": 7,
    "customer": "Ann",
    "lines": [
        {
            "prod": "A",
            "qty": 2
        }
    ],
    "extra": {
        "k": "v"
    }
}
```

## Features

- **Live sync** — paste or open a `.log`; the tree and the JSON update while you type.
- **Several printing syntaxes** (configurable *dialects*), detected automatically:

  | Dialect | Example |
  |---|---|
  | Java / Kotlin | `Person(name=Ann, x=null)`, `Point[x=1]`, `{k=v}`, `{"k": 1}` |
  | C# | `Person { Name = Ann, Active = True }`, `(1, x)` |
  | Python | `Person(name='Ann', extra=None)`, `{'k': 1}`, `(1, 2)`, `{1, 2}` |
  | Rust | `Person { name: "Ann", extra: None, v: Some(5) }`, `Point(1, 2)` |

- **Tree** of the discovered properties (objects, maps, JSON objects, lists, leaves), with search.
- **JSON editor** (Postman style): folding, validation, copy, save; a tolerant checker lists
  **every problem at once** (squiggles, gutter marks, error strip, F2 navigation, problems list).
- **Locate** — the same property in the text, the tree and the JSON, from any of them
  (hover a node/property to discover the actions; right click to fix the menu).
- **Compare** (⇄ on the *Root* tab) — the text becomes the **Pattern** (the log that works) and a
  **Sample** editor opens next to it. Differences are shown in the tree, both texts and a combined JSON:
  `−` missing in the Sample · `+` extra in the Sample · `≠` different type · `~` different value.
- **One object per text** — several independent objects are reported as an error
  (nothing is grouped or invented).
- **Sessions and branches** — several logs at once; analyze any node in its own tab.
- **UI languages** — English, Español, Français, Português, Deutsch, Italiano (flag selector).

## Requirements

- Java **17** or newer.
- Gradle (to build and run from source).

## Run

```bash
gradle run
```

## Tests

```bash
gradle test     # headless: parser, dialects, JSON, checker, comparison, fuzz and stress tests (Spock)
gradle uiTest   # functional UI tests with real windows and mouse (need a display)
```

## Configuration

All settings live in YAML files; nothing is hard-coded.

| File | What |
|---|---|
| `src/main/resources/application.yml` | timings, limits, colors (comparison), languages, flags, icons |
| `src/main/resources/dialects.yml` | printing syntaxes: literals, separators, quotes, object forms, languages |
| `src/main/resources/i18n/messages_<lang>.yml` | UI texts, one file per language |

To override without rebuilding, copy `application.yml` or `dialects.yml` to
`~/.log-object-json/` or to the working directory, or pass `-Dlojson.<dotted.key>=value`
(e.g. `-Dlojson.compare.colors.value=#5A4E7A`).

Adding a dialect (e.g. another language) only requires a new entry in `dialects.yml`;
adding a UI language only requires a new `messages_<lang>.yml` and listing it in `i18n.languages`.

## Known limitations

- One object per line: objects printed across **several lines** (e.g. Rust `{:#?}`, Python `pprint`)
  are not recognized yet.
- Types are inferred from the text (a log does not say `int` vs `Integer`); class names are not
  part of the JSON.
- Editing the JSON does not (yet) rewrite the source text.

## Credits

- [RSyntaxTextArea](https://github.com/bobbylight/RSyntaxTextArea) (BSD-3-Clause) — text editors.
- [Jackson](https://github.com/FasterXML/jackson) (Apache-2.0) — JSON and YAML.
- [Devicon](https://github.com/devicons/devicon) (MIT) — language logos (`src/main/resources/languages`).
- Flags from [flagcdn.com](https://flagcdn.com) (public domain).
- Tests with [Spock](https://spockframework.org) (Apache-2.0).

Language logos are trademarks of their respective owners and are used only to identify the languages.

## License

Copyright (C) 2026 Jose Luis Bernal Zambrano

This program is free software: you can redistribute it and/or modify it under the terms of the
**GNU General Public License version 3** (or, at your option, any later version), as published by the
Free Software Foundation. It is distributed WITHOUT ANY WARRANTY. See [LICENSE](LICENSE).

The bundled third-party components keep their own licenses (MIT, Apache-2.0, BSD-3-Clause, public
domain — see *Credits*); all of them are compatible with the GPL-3.0.
