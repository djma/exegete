(() => {
  const words = [];
  const visibleLines = [];
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  let lastVisibleWord = -1;
  let lineTop = null;
  let node;
  while ((node = walker.nextNode())) {
    if (getComputedStyle(node.parentElement).visibility !== 'visible') continue;
    for (const word of (node.nodeValue || '').matchAll(/\S+/g)) {
      const range = document.createRange();
      range.setStart(node, word.index);
      range.setEnd(node, word.index + word[0].length);
      const rects = Array.from(range.getClientRects()).filter(rect => rect.width > 0 && rect.height > 0);
      if (!rects.length) continue;
      words.push(word[0]);
      const onPage = rects.find(rect =>
        rect.right > 0 && rect.left < window.innerWidth &&
        rect.bottom > 0 && rect.top < window.innerHeight
      );
      if (!onPage) continue;
      lastVisibleWord = words.length - 1;
      if (lineTop === null || Math.abs(onPage.top - lineTop) > 2) {
        visibleLines.push([]);
        lineTop = onPage.top;
      }
      visibleLines[visibleLines.length - 1].push(word[0]);
    }
  }
  if (lastVisibleWord < 0) return null;
  // A paragraph may span pages. Stop at its last visible word, never its end.
  return JSON.stringify({
    resourceText: words.slice(0, lastVisibleWord + 1).join(' '),
    visibleText: visibleLines.map(line => line.join(' ')).join('\n')
  });
})()
