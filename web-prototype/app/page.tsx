const repository = 'https://github.com/djma/exegete';

export default function Home() {
  return (
    <main>
      <nav className="nav" aria-label="Primary navigation">
        <a className="wordmark" href="#top" aria-label="Exegete home">
          <span className="mark" aria-hidden="true">E</span>
          <span>Exegete</span>
        </a>
        <a className="nav-link" href={repository}>
          Source
          <span aria-hidden="true">↗</span>
        </a>
      </nav>

      <section className="hero" id="top">
        <div className="hero-copy">
          <p className="eyebrow">A small EPUB reader for e-ink</p>
          <h1>An EPUB reader with a spoiler-conscious companion.</h1>
          <p className="lede">
            I built Exegete for my BOOX Palma 2. When you ask Exy a question,
            the app sends text up to the current page—not the rest of the book.
          </p>
          <div className="actions">
            <a className="button button-dark" href={repository}>
              View on GitHub
              <span aria-hidden="true">↗</span>
            </a>
            <span className="availability">Personal project · MIT licensed</span>
          </div>
        </div>

        <div className="device-stage" aria-label="Exegete reader and Exy conversation preview">
          <div className="device">
            <div className="device-speaker" />
            <div className="screen">
              <div className="reader-meta">
                <span>FRANKENSTEIN</span>
                <span>14%</span>
              </div>
              <div className="book-page">
                <h2>Letter IV</h2>
                <p>
                  We perceived a low carriage, fixed on a sledge and drawn by dogs,
                  pass on towards the north, at the distance of half a mile.
                </p>
                <p>
                  A being which had the shape of a man, but apparently of gigantic
                  stature, sat in the sledge and guided the dogs.
                </p>
              </div>
              <div className="reader-controls">
                <span>Aa</span><strong>Ask Exy</strong><span>Marks</span>
              </div>
            </div>
          </div>

          <div className="chat-card">
            <span className="chat-label">YOU</span>
            <p>Why does Walton trust the stranger so quickly?</p>
            <span className="chat-label">EXY</span>
            <p>
              The stranger arrives as a mirror of Walton: driven, isolated, and
              nearly destroyed by the same hunger for discovery.
            </p>
            <span className="boundary"><span aria-hidden="true">✓</span> Context ends at page 42</span>
          </div>
        </div>
      </section>

      <section className="principle" aria-labelledby="principle-title">
        <p className="section-index">01 / HOW IT WORKS</p>
        <h2 id="principle-title">A reader first, with chat when it is useful.</h2>
        <div className="rule-grid">
          <div>
            <span className="rule-number">1</span>
            <h3>Read normally</h3>
            <p>Open a local EPUB, turn pages instantly, and keep your place.</p>
          </div>
          <div>
            <span className="rule-number">2</span>
            <h3>Ask from the page</h3>
            <p>Select a passage or ask about anything you have read so far.</p>
          </div>
          <div>
            <span className="rule-number">3</span>
            <h3>Keep later pages out</h3>
            <p>Later pages are excluded when Exegete builds the prompt.</p>
          </div>
        </div>
      </section>

      <section className="details" aria-labelledby="details-title">
        <div>
          <p className="section-index">02 / E-INK DETAILS</p>
          <h2 id="details-title">Built around the Palma 2.</h2>
        </div>
        <ul>
          <li><span className="detail-icon">◫</span><span>EPUB 2 and EPUB 3 through Readium</span></li>
          <li><span className="detail-icon">↔</span><span>Instant page turns and volume-key navigation</span></li>
          <li><span className="detail-icon">Aa</span><span>Type, spacing, margins, and night mode</span></li>
          <li><span className="detail-icon">✓</span><span>No Exegete account, ads, analytics, or private server</span></li>
        </ul>
      </section>

      <section className="privacy" aria-label="Privacy note">
        <p>
          Your EPUB file stays on your device. When you ask Exy, relevant text up
          to your current page and the conversation are sent to OpenRouter and its
          selected model provider.
        </p>
      </section>

      <footer>
        <div>
          <span className="mark" aria-hidden="true">E</span>
          <p>A small open-source Android project.</p>
        </div>
        <a href={repository}>MIT licensed · GitHub <span aria-hidden="true">↗</span></a>
      </footer>
    </main>
  );
}
