const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const vm = require('node:vm');
const script = readFileSync(`${__dirname}/../../main/assets/chat-page.js`, 'utf8');

// A paragraph continues across three pages. Rectangles change when the reader navigates.
function capture(page, { hidden = false, empty = false } = {}) {
  const text = 'First line. Second line. Third line. Fourth line. Fifth line. Sixth line.';
  const nodes = empty ? [] : [{ nodeValue: text, parentElement: { hidden: false } }];
  if (hidden) nodes.push({ nodeValue: 'Hidden spoiler.', parentElement: { hidden: true } });
  let nextNode = 0;
  return JSON.parse(vm.runInNewContext(script, {
    NodeFilter: { SHOW_TEXT: 4 },
    window: { innerWidth: 200, innerHeight: 40 },
    getComputedStyle: element => ({ visibility: element.hidden ? 'hidden' : 'visible' }),
    document: {
      body: {},
      createTreeWalker: () => ({ nextNode: () => nodes[nextNode++] }),
      createRange: () => {
        let node;
        let start;
        return {
          setStart: (n, index) => { node = n; start = index; },
          setEnd: () => {},
          getClientRects: () => {
            const wordIndex = Array.from(node.nodeValue.matchAll(/\S+/g))
              .findIndex(word => word.index === start);
            const top = Math.floor(wordIndex / 2) * 20 - page * 40;
            return [{ left: 0, right: 70, top, bottom: top + 20, width: 70, height: 20 }];
          }
        };
      }
    }
  }));
}

test('a paragraph stops at the last visible line, not the paragraph end', () => {
  assert.deepEqual(capture(0), {
    resourceText: 'First line. Second line.',
    visibleText: 'First line.\nSecond line.'
  });
});

test('skipping ahead immediately uses the destination page', () => {
  capture(0);
  assert.deepEqual(capture(2), {
    resourceText: 'First line. Second line. Third line. Fourth line. Fifth line. Sixth line.',
    visibleText: 'Fifth line.\nSixth line.'
  });
});

test('going back removes text after the destination page', () => {
  capture(2);
  assert.equal(capture(1).resourceText, 'First line. Second line. Third line. Fourth line.');
  assert.equal(capture(0).resourceText, 'First line. Second line.');
});

test('hidden text cannot extend the context', () => {
  assert.deepEqual(capture(0, { hidden: true }), capture(0));
});

test('an empty page does not fall back to another reading position', () => {
  assert.equal(capture(0, { empty: true }), null);
});
