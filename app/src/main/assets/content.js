(function () {
  const ignoredSet = new Set(window._fcIgnoredUsers || []);
  const keywordsEnabled = window._fcKeywordsEnabled !== false;
  const keywords = (window._fcKeywords || []).map(k => k.toLowerCase());

  const isThreadPage = window.location.href.includes('showthread.php');

  // ── Thread list filtering ────────────────────────────────────────────────

  function getThreadRows() {
    const seen = new Set();
    const rows = [];
    for (const a of document.querySelectorAll('a[href*="showthread.php?t="]')) {
      let el = a;
      for (let i = 0; i < 4; i++) if (el.parentElement) el = el.parentElement;
      if (!seen.has(el)) { seen.add(el); rows.push(el); }
    }
    return rows;
  }

  function getAuthorFromRow(row) {
    const spans = Array.from(row.querySelectorAll('span'));
    for (let i = 0; i < spans.length - 1; i++) {
      if (spans[i].textContent.trim() === '@') return spans[i + 1].textContent.trim();
    }
    for (const el of row.querySelectorAll('span, a')) {
      const text = el.textContent.trim();
      if (!text.startsWith('@')) continue;
      const withoutAt = text.slice(1);
      const dashIdx = withoutAt.indexOf(' - ');
      return dashIdx !== -1 ? withoutAt.slice(0, dashIdx).trim() : withoutAt.trim();
    }
    return null;
  }

  function getTitleFromRow(row) {
    const a = row.querySelector('a[href*="showthread.php?t="]');
    return a ? a.textContent.trim().toLowerCase() : '';
  }

  function filterThreads(rows) {
    for (const row of rows) {
      const author = getAuthorFromRow(row);
      if (author && ignoredSet.has(author)) { row.style.display = 'none'; continue; }
      if (keywordsEnabled && keywords.length > 0) {
        const title = getTitleFromRow(row);
        if (keywords.find(k => title.includes(k))) row.style.display = 'none';
      }
    }
  }

  // ── Post filtering (dentro de hilos) ────────────────────────────────────

  function getPostAuthor(post) {
    // vBulletin móvil: el autor suele estar en .username strong o en un <a href="member.php...">
    const el = post.querySelector('.username strong, .postusername strong, .postusername, .username');
    if (el) return el.textContent.trim();
    // Fallback: enlace al perfil del autor
    const memberLink = post.querySelector('a[href*="member.php"]');
    if (memberLink) return memberLink.textContent.trim();
    return null;
  }

  function filterPosts(posts) {
    for (const post of posts) {
      const author = getPostAuthor(post);
      if (author && ignoredSet.has(author)) post.style.display = 'none';
    }
  }

  function getPostContainers(root) {
    return Array.from((root || document).querySelectorAll('div[id^="post_"], .postcontainer, .post-row'));
  }

  // ── Inicialización ───────────────────────────────────────────────────────

  if (isThreadPage) {
    filterPosts(getPostContainers());
  } else {
    filterThreads(getThreadRows());
  }

  const observer = new MutationObserver(function (mutations) {
    if (isThreadPage) {
      const newPosts = [];
      const seen = new Set();
      for (const mutation of mutations) {
        for (const node of mutation.addedNodes) {
          if (node.nodeType !== 1) continue;
          for (const post of getPostContainers(node)) {
            if (!seen.has(post)) { seen.add(post); newPosts.push(post); }
          }
        }
      }
      if (newPosts.length > 0) filterPosts(newPosts);
    } else {
      const newRows = [];
      const seen = new Set();
      for (const mutation of mutations) {
        for (const node of mutation.addedNodes) {
          if (node.nodeType !== 1) continue;
          for (const a of node.querySelectorAll('a[href*="showthread.php?t="]')) {
            let el = a;
            for (let i = 0; i < 4; i++) if (el.parentElement) el = el.parentElement;
            if (!seen.has(el)) { seen.add(el); newRows.push(el); }
          }
        }
      }
      if (newRows.length > 0) filterThreads(newRows);
    }
  });

  observer.observe(document.body, { childList: true, subtree: true });
})();
