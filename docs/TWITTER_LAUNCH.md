# Notes for sharing on Twitter

## Main post

> I made a small EPUB reader for my BOOX Palma 2.
>
> It has a built-in reading companion, but the app only sends book text up to the
> page you are on—not the rest of the EPUB.
>
> It is open source and still a little rough around the edges. Kotlin, Readium,
> and OpenRouter. [video]

## First reply

> Code and APK: https://github.com/djma/exegete

## Fifteen-second shot list

1. Hold the physical Palma in frame with an EPUB already open.
2. Turn one page with a volume key.
3. Select one interesting sentence and tap **Ask Exy**.
4. Ask a short interpretive question.
5. Let one concise answer appear.
6. End on the Exegete library screen for one second.

Use a public-domain EPUB. Keep the device and its e-ink refresh visible. Do not show an
API key, notifications, copyrighted book text, or personal information.

## Follow-up ideas, if people ask

- Show where the Readium locator stops prompt construction.
- Explain why streamed text refreshes every 500 ms on e-ink.
- Show how Exegete preserves the semantic reading position across font reflow.
- Share a small test that proves later resources are excluded from the prompt.
