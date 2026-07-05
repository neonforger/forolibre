// extractor.js — motor de datos de la UI nativa (rama v2-shell).
//
// REGLA DE ORO anti-Cloudflare: TODA petición a FC sale de este contexto de navegador
// (fetch same-origin con las cookies de la sesión del WebView). Nada de HTTP nativo.
// Este script solo EXTRAE y entrega JSON crudo por el bridge AndroidShell; el filtrado
// (ignorados/keywords) y el render viven en Kotlin.
(function () {
  if (window.__fcExtractorLoaded) { return; }
  window.__fcExtractorLoaded = true;
  if (typeof AndroidShell === 'undefined') { return; }

  var THREAD_SEL = 'a[href*="showthread.php?t="]';

  function tidOf(a) {
    var m = (a.getAttribute('href') || '').match(/[?&]t=(\d+)/);
    return m ? m[1] : null;
  }

  function tidsIn(el) {
    var s = new Set();
    el.querySelectorAll(THREAD_SEL).forEach(function (x) {
      var t = tidOf(x); if (t) s.add(t);
    });
    return s;
  }

  // Mismo algoritmo que content.js: sube desde el anchor hasta el contenedor más
  // ajustado que envuelve UN solo hilo.
  function rowFor(a, tid, doc) {
    var el = a;
    while (el.parentElement && el.parentElement !== doc.body) {
      var ids = tidsIn(el.parentElement);
      if (ids.size > 1 || (ids.size === 1 && !ids.has(tid))) break;
      el = el.parentElement;
    }
    return el;
  }

  function inMenu(a) {
    // El menú de perfil (oculto) también contiene enlaces showthread: fuera.
    return !!(a.closest && a.closest('.user-profile-menu-container, .header-container'));
  }

  function parseRow(row, tid) {
    var anchors = [];
    row.querySelectorAll(THREAD_SEL).forEach(function (x) { anchors.push(x); });
    if (!anchors.length) return null;
    // Título: el anchor con el texto más largo (los otros son contadores/hora).
    var titleA = anchors[0];
    anchors.forEach(function (x) {
      if (x.textContent.trim().length > titleA.textContent.trim().length) titleA = x;
    });
    var title = titleA.textContent.replace(/\s+/g, ' ').trim();
    if (!title) return null;

    // Autor: span inmediatamente después del span "@"; respuestas: span numérico anterior.
    var author = '', replies = '';
    var spans = row.querySelectorAll('span');
    for (var i = 0; i < spans.length; i++) {
      if (spans[i].textContent.trim() === '@') {
        if (i + 1 < spans.length) author = spans[i + 1].textContent.trim();
        for (var j = i - 1; j >= 0; j--) {
          var t = spans[j].textContent.trim();
          if (/^\d+$/.test(t)) { replies = t; break; }
          if (t.length > 6) break; // ya no es el contador
        }
        break;
      }
    }

    // Hora: texto del último anchor de la fila ("Hoy 20:00", "Ayer 09:12", fecha).
    var time = anchors.length > 1
      ? anchors[anchors.length - 1].textContent.replace(/\s+/g, ' ').trim() : '';
    if (time === title) time = '';

    return {
      tid: tid,
      title: title,
      author: author,
      replies: replies,
      time: time,
      url: 'https://forocoches.com/foro/showthread.php?t=' + tid
    };
  }

  function parseListDoc(doc) {
    var seen = new Set();
    var threads = [];
    doc.querySelectorAll(THREAD_SEL).forEach(function (a) {
      var tid = tidOf(a);
      if (!tid || seen.has(tid) || inMenu(a)) return;
      seen.add(tid);
      var item = parseRow(rowFor(a, tid, doc), tid);
      if (item) threads.push(item);
    });
    return threads;
  }

  // Contadores del menú (MP / citas / menciones) del doc RECIÉN traído — misma lógica
  // que NotificationFetcher.parseCounts en Kotlin: mapear por href, nunca por posición.
  function menuCounts(doc) {
    var c = { pm: 0, quotes: 0, mentions: 0 };
    doc.querySelectorAll('a.menu-item').forEach(function (a) {
      var w = a.querySelector('.user-notifications-count-wrapper');
      if (!w) return;
      var m = (w.textContent || '').match(/\d+/);
      var n = m ? parseInt(m[0], 10) : 0;
      var h = a.getAttribute('href') || '';
      if (h.indexOf('private.php') !== -1) c.pm = n;
      else if (h.indexOf('tab=quotes') !== -1) c.quotes = n;
      else if (h.indexOf('tab=mentions') !== -1) c.mentions = n;
    });
    return c;
  }

  // Enlaces del menú de la página VIVA (llevan el u= del usuario logueado; no se adivinan).
  function menuLinks() {
    var m = {};
    document.querySelectorAll('.user-profile-menu-container a.menu-item').forEach(function (a) {
      var h = a.getAttribute('href') || '';
      if (!h) return;
      if (h.indexOf('private.php') !== -1 && !m.pm) m.pm = a.href;
      else if (h.indexOf('tab=mentions') !== -1 && !m.mentions) m.mentions = a.href;
      else if (h.indexOf('tab=quotes') !== -1 && !m.quotes) m.quotes = a.href;
      else if (h.indexOf('subscription.php') !== -1 && !m.favs) m.favs = a.href;
      else if (h.indexOf('member.php') !== -1 && !m.profile) m.profile = a.href;
    });
    return m;
  }

  // Lista de subforos del índice (dinámica: si FC añade/quita subforos, la app se adapta).
  window.fcLoadForumList = function (url) {
    fetch(url, { credentials: 'same-origin' })
      .then(function (r) {
        if (!r.ok) throw new Error('http ' + r.status);
        return r.text();
      })
      .then(function (html) {
        var doc = new DOMParser().parseFromString(html, 'text/html');
        var seen = new Set();
        var forums = [];
        doc.querySelectorAll('a[href*="forumdisplay.php?f="]').forEach(function (a) {
          var m = (a.getAttribute('href') || '').match(/[?&]f=(\d+)/);
          if (!m) return;
          // Las "zonas" contenedoras (Zona ForoCoches, Zona Técnica...) son títulos
          // h1.forum-zone-title sin hilos propios: fuera de las pestañas.
          if (a.closest && a.closest('h1, h2, h3, .forum-zone-title')) return;
          var name = a.textContent.replace(/\s+/g, ' ').trim();
          if (!name || name.length > 40 || seen.has(m[1])) return;
          seen.add(m[1]);
          forums.push({ fid: m[1], name: name });
        });
        if (forums.length) {
          AndroidShell.onForumList(JSON.stringify({ forums: forums }));
        }
      })
      .catch(function (e) { /* sin lista de foros la app sigue con General */ });
  };

  // API pública para la app: carga un listado por fetch same-origin y lo entrega parseado.
  window.fcLoadThreadList = function (url) {
    fetch(url, { credentials: 'same-origin' })
      .then(function (r) {
        if (!r.ok) throw new Error('http ' + r.status);
        return r.text();
      })
      .then(function (html) {
        var doc = new DOMParser().parseFromString(html, 'text/html');
        // Challenge de Cloudflare: avisar a la app para que enseñe el WebView y lo resuelva
        // el usuario como en el navegador.
        if (/just a moment|attention required|un momento/i.test(doc.title || '')) {
          AndroidShell.onListError('cloudflare');
          return;
        }
        var payload = { url: url, menu: menuLinks(), counts: menuCounts(doc), threads: parseListDoc(doc) };
        if (!payload.threads.length) {
          AndroidShell.onListError('empty'); // canario: 0 hilos = probable cambio de HTML
          return;
        }
        AndroidShell.onThreadList(JSON.stringify(payload));
      })
      .catch(function (e) { AndroidShell.onListError(String(e)); });
  };
})();
