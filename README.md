# Exegete

![Exegete on an e-ink reader](web-prototype/public/og.png)

Exegete is a small, open-source EPUB reader for e-ink Android devices. It includes
a spoiler-conscious reading companion called Exy.

I built it for the **BOOX Palma 2**. The interface is monochrome, low-motion, and
made for its black-and-white display. This is a personal project and an early beta,
not a general-purpose replacement for established readers.

## What it does

- Reads local EPUB 2 and EPUB 3 books through Readium.
- Turns pages without animation and supports the Palma volume keys.
- Adjusts text size, line spacing, font, margins, and day or night mode.
- Restores the last book and reading position.
- Provides chapter links, a scrubber, and per-book bookmarks.
- Opens Exy from the current page or a selected passage.
- Shows the final lines sent to Exy before you ask a question.
- Streams replies at an e-ink-friendly refresh rate.

## The spoiler boundary

When you open Exy, Exegete captures the current page. The context ends at its last
visible word, including when you go back or skip ahead. The final three lines appear
above the chat so you can check the boundary before asking a question. The context
also includes earlier book text and an optional selected passage.

That captured page stays fixed while the chat is open, including after you clear the
messages. Opening chat on a different page starts a fresh conversation. This keeps
replies about later pages out of a chat opened on an earlier page.

Exegete does not send pages after the current reading position. The model can still
have prior knowledge of a published book, so Exy is also instructed not to reveal,
predict, hint at, or confirm later events. If you find a spoiler-boundary problem,
please open an issue.

## Privacy

Your EPUB file stays on your device. When you ask Exy, relevant text up to your
current page and the conversation are sent over HTTPS to OpenRouter and its selected
model provider. There is no Exegete account, analytics SDK, advertising, or private
application server.

The OpenRouter key is encrypted with Android Keystore. Chat history is held in memory
for the current reading session and is not saved. See [PRIVACY.md](PRIVACY.md) for the
complete disclosure.

## Install

Download the latest release APK from the repository's **Releases** page and open it
on the BOOX device. Android might ask you to allow installation from the browser or
file manager that opened the APK.

Then open Exegete, add an OpenRouter API key, and select an EPUB that you own. The
preferred model is `minimax/minimax-m3:free`, with `openrouter/free` as a fallback.
Free model endpoints can have strict rate and availability limits.

With Android developer mode and USB debugging enabled, install from a computer:

```sh
adb install -r Exegete-0.8.0.apk
```

## Build

Install Android SDK 36 and Java 17. Set `sdk.dir` in `local.properties`, then use the
included Gradle wrapper:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

With Node.js 22 or later, test page capture with:

```sh
node --test app/src/test/js/chat-page.test.cjs
```

The development APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Do not
distribute it. Public APKs must use a stable release key; see
[docs/RELEASING.md](docs/RELEASING.md).

## Contributing

Focused issues and pull requests are welcome. Useful directions include EPUB
compatibility, accessibility, spoiler-boundary tests, e-ink behavior on more devices,
and layouts for other Android screens.

Do not commit API keys, signing material, copyrighted books, or generated build files.
Security reports should follow [SECURITY.md](SECURITY.md).

## License

Exegete is available under the [MIT License](LICENSE).
