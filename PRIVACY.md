# Privacy

Exegete does not use accounts, advertising, analytics, or a private application server.

Your EPUB file remains on your Android device. Exegete stores your reading position,
bookmarks, reading preferences, and OpenRouter API key on that device. The API key is
encrypted with Android Keystore and is not included in backups.

When you ask Exy a question, Exegete sends the following data over HTTPS to OpenRouter:

- the book title and author;
- relevant book text up to your current reading position;
- text visible on the current page;
- a passage that you selected, if any;
- your question and the current in-memory chat history; and
- Exegete's system instructions.

Exegete does not send book text after your current reading position. OpenRouter routes
the request to a selected model provider. Their handling of request data is governed by
their own terms and privacy policies. You can clear the in-memory conversation in the
chat panel. Exegete does not save chat history after the reading session ends.

To stop using the AI feature, remove or replace the OpenRouter key from the app. Reading
local EPUB files does not require a network request.

Questions about this policy can be opened as an issue in the Exegete repository.
