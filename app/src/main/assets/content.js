(function () {
  function getThreadRows() {
    const seen = new Set();
    const rows = [];

    for (const link of document.querySelectorAll('a[id^="thread_title_"]')) {
      let el = link;
      for (let i = 0; i < 3; i++) if (el.parentElement) el = el.parentElement;
      if (!seen.has(el)) { seen.add(el); rows.push(el); }
    }

    for (const strong of document.querySelectorAll('a[href*="showthread.php?t="] > strong')) {
      const a = strong.parentElement;
      if (a.id && a.id.startsWith('thread_title_')) continue;
      let el = a;
      for (let i = 0; i < 3; i++) if (el.parentElement) el = el.parentElement;
      if (!seen.has(el)) { seen.add(el); rows.push(el); }
    }

    return rows;
  }

  function getAuthorFromRow(row) {
    for (const el of row.querySelectorAll('span, a')) {
      const text = el.textContent.trim();
      if (!text.startsWith('@')) continue;
      const withoutAt = text.slice(1);
      const dashIdx = withoutAt.indexOf(' - ');
      return dashIdx !== -1 ? withoutAt.slice(0, dashIdx).trim() : withoutAt.trim();
    }
    return null;
  }

  function getContentDiv(row) {
    const subforumLink = row.querySelector('a[id^="thread_title_"]');
    if (subforumLink) return subforumLink.parentElement && subforumLink.parentElement.parentElement;
    const strong = row.querySelector('a[href*="showthread.php?t="] > strong');
    if (strong) return strong.parentElement && strong.parentElement.parentElement && strong.parentElement.parentElement.parentElement;
    return null;
  }

  function filterThreads(rows, ignoredSet, hideMode) {
    for (const row of rows) {
      const author = getAuthorFromRow(row);
      if (!author || !ignoredSet.has(author)) continue;
      if (hideMode === 'complete') {
        row.style.display = 'none';
      } else {
        const contentDiv = getContentDiv(row);
        if (!contentDiv) continue;
        contentDiv.innerHTML =
          '<div style="padding:12px 0;color:var(--gray-text);font-size:0.875rem;display:flex;align-items:center;flex-wrap:wrap;gap:4px;">' +
          'Este hilo está oculto porque <strong>' + author + '</strong> está en tu lista de ignorados.' +
          '</div>';
      }
    }
  }

  const ignoredSet = new Set(window._fcIgnoredUsers || []);
  const hideMode = window._fcHideMode || 'message';

  filterThreads(getThreadRows(), ignoredSet, hideMode);

  const observer = new MutationObserver(function (mutations) {
    const newRows = [];
    const seen = new Set();
    for (const mutation of mutations) {
      for (const node of mutation.addedNodes) {
        if (node.nodeType !== 1) continue;
        for (const link of node.querySelectorAll('a[id^="thread_title_"], a[href*="showthread.php?t="] > strong')) {
          const base = link.tagName === 'STRONG' ? link.parentElement : link;
          let el = base;
          for (let i = 0; i < 3; i++) if (el.parentElement) el = el.parentElement;
          if (!seen.has(el)) { seen.add(el); newRows.push(el); }
        }
      }
    }
    if (newRows.length > 0) filterThreads(newRows, ignoredSet, hideMode);
  });

  observer.observe(document.body, { childList: true, subtree: true });
})();
