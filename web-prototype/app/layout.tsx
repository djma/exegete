import type { Metadata } from 'next';
import { Geist, Newsreader } from 'next/font/google';
import './globals.css';

const geist = Geist({ variable: '--font-geist-sans', subsets: ['latin'] });
const newsreader = Newsreader({ variable: '--font-newsreader', subsets: ['latin'] });

export const metadata: Metadata = {
  metadataBase: new URL('https://exegete-reader.ma-davidj.chatgpt.site'),
  title: 'Exegete — a small EPUB reader for e-ink',
  description: 'A small EPUB reader with a spoiler-conscious reading companion, built for e-ink Android devices.',
  openGraph: {
    title: 'Exegete — a small EPUB reader for e-ink',
    description: 'A small EPUB reader with a spoiler-conscious reading companion.',
    type: 'website',
    images: ['/og.png'],
  },
  twitter: {
    card: 'summary_large_image',
    title: 'Exegete — a small EPUB reader for e-ink',
    description: 'A small EPUB reader with a spoiler-conscious reading companion.',
    images: ['/og.png'],
  },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body className={`${geist.variable} ${newsreader.variable}`}>{children}</body>
    </html>
  );
}
