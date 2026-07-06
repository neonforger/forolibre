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

  // ── Vista de hilo nativa (Fase 2) ──────────────────────────────────────────
  // Parsea los posts de un showthread traído por fetch same-origin. El HTML del
  // mensaje se SIMPLIFICA para el render nativo: citas → blockquote, embeds →
  // enlace tocable (degradación elegante), URLs relativas → absolutas.
  window.fcLoadThread = function (url) {
    fetch(url, { credentials: 'same-origin' })
      .then(function (r) {
        if (!r.ok) throw new Error('http ' + r.status);
        return r.text();
      })
      .then(function (html) {
        var doc = new DOMParser().parseFromString(html, 'text/html');
        if (/just a moment|attention required|un momento/i.test(doc.title || '')) {
          AndroidShell.onThreadError('cloudflare');
          return;
        }
        var tid = (url.match(/[?&]t=(\d+)/) || [])[1] || '';
        var posts = [];
        doc.querySelectorAll('li.postbit').forEach(function (bit) {
          var wrap = bit.closest('div.postbit_wrapper') || bit;
          var pmEl = wrap.querySelector('[id^="postmenu_"]');
          var pid = pmEl ? pmEl.id.replace('postmenu_', '') : '';
          var auEl = pmEl ? pmEl.querySelector('a[href*="member.php"]') : null;
          var author = auEl ? auEl.textContent.trim() : '';
          var msg = wrap.querySelector('[id^="post_message_"]');
          if (!msg || !pid) return;

          // Cabecera (todo menos el mensaje): fecha y avatar sin contaminarse de citas.
          var header = wrap.cloneNode(true);
          var hm = header.querySelector('[id^="post_message_"]');
          if (hm) hm.remove();
          var dm = (header.textContent || '').replace(/\s+/g, ' ')
            .match(/(Hoy|Ayer|\d{1,2}-[a-z]{3,4}-\d{2,4})[, ]*\d{1,2}:\d{2}/i);
          var date = dm ? dm[0] : '';
          var avatar = '';
          var av = header.querySelector('img[src*="avatar"], img[src*="image.php"]');
          if (av) {
            try { avatar = new URL(av.getAttribute('src'), 'https://forocoches.com/foro/').href; }
            catch (e) {}
          }

          // Mensaje simplificado para render nativo.
          var m = msg.cloneNode(true);
          m.querySelectorAll('script,style').forEach(function (x) { x.remove(); });
          m.querySelectorAll('div.quote').forEach(function (q) {
            var bq = doc.createElement('blockquote');
            var qb = q.querySelector('b');
            var who = qb ? qb.textContent.trim() : '';
            bq.innerHTML = (who ? '<b>' + who + ' dijo:</b><br>' : '') + q.innerHTML;
            q.replaceWith(bq);
          });
          m.querySelectorAll('iframe,video,embed,object').forEach(function (f) {
            var src = f.src || f.getAttribute('src') || '';
            var a = doc.createElement('a');
            a.setAttribute('href', src || url);
            var host = 'embebido';
            try { if (src) host = new URL(src, 'https://forocoches.com').host; } catch (e) {}
            a.textContent = '▶ Ver contenido (' + host + ')';
            f.replaceWith(a);
          });
          m.querySelectorAll('a[href]').forEach(function (a) {
            try { a.setAttribute('href', new URL(a.getAttribute('href'), 'https://forocoches.com/foro/').href); } catch (e) {}
          });
          m.querySelectorAll('img[src]').forEach(function (i) {
            try { i.setAttribute('src', new URL(i.getAttribute('src'), 'https://forocoches.com/foro/').href); } catch (e) {}
          });

          posts.push({ pid: pid, author: author, avatar: avatar, date: date, html: m.innerHTML });
        });
        if (!posts.length) {
          AndroidShell.onThreadError('empty');
          return;
        }
        // Nº de páginas: el mayor page= de los enlaces del hilo (si no hay, 1).
        var pageCount = 1;
        doc.querySelectorAll('a[href*="showthread.php"][href*="page="]').forEach(function (a) {
          var h = a.getAttribute('href') || '';
          if (tid && h.indexOf('t=' + tid) === -1) return;
          var pm2 = h.match(/[?&]page=(\d+)/);
          if (pm2) pageCount = Math.max(pageCount, parseInt(pm2[1], 10));
        });
        var page = (url.match(/[?&]page=(\d+)/) || [])[1];
        page = page ? parseInt(page, 10) : 1;
        pageCount = Math.max(pageCount, page);
        var title = (doc.title || '').replace(/\s*-\s*Forocoches.*$/i, '').trim();
        AndroidShell.onThread(JSON.stringify({
          url: url, tid: tid, title: title, page: page, pageCount: pageCount,
          counts: menuCounts(doc), posts: posts
        }));
      })
      .catch(function (e) { AndroidShell.onThreadError(String(e)); });
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
