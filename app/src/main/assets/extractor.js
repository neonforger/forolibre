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

  // ── Helpers de envío de formularios (crear/editar/borrar) ───────────────────
  // Copia TODOS los campos de un form real de FC a URLSearchParams (menos ficheros
  // y submits sin marcar), aplica overrides y descarta los de 'drop'. Mismo principio
  // que fcSubmitReply: no reimplementamos nada, reutilizamos el form con su token.
  function copyFormFields(form, overrides, drop) {
    var params = new URLSearchParams();
    form.querySelectorAll('input, textarea, select').forEach(function (el) {
      var nm = el.getAttribute('name'); if (!nm) return;
      var type = (el.getAttribute('type') || '').toLowerCase();
      if (type === 'file') return;
      if (drop && drop.indexOf(nm) !== -1) return;
      if ((type === 'checkbox' || type === 'radio') && !el.checked) return;
      if (type === 'submit') return; // el submit se pasa por override (sbutton)
      params.set(nm, el.value || '');
    });
    if (overrides) Object.keys(overrides).forEach(function (k) { params.set(k, overrides[k]); });
    return params;
  }

  // Texto del mensaje de error de FC (validación, anti-flood, permisos).
  function extractErr(html) {
    var em = html.match(/<(?:div|li|p)[^>]*class="[^"]*(?:standard_error|error|blockrow)[^"]*"[^>]*>\s*([^<]{4,200})/i);
    return em ? em[1].replace(/\s+/g, ' ').trim() : '';
  }

  // El primer form del doc que contiene el selector dado (p. ej. el textarea message).
  function formWith(doc, selector) {
    var fs = [].slice.call(doc.querySelectorAll('form'));
    for (var i = 0; i < fs.length; i++) if (fs[i].querySelector(selector)) return fs[i];
    return null;
  }

  // ── Embeds: convierte el markup de FC (blockquotes de Twitter/IG/TikTok, iframes,
  // enlaces de YouTube) en algo que la tarjeta nativa SÍ sabe pintar. YouTube → una
  // miniatura real tocable; el resto → una "tarjeta-enlace" con icono de plataforma.
  function ytIdFrom(url) {
    var m = url.match(/(?:youtu\.be\/|v=|\/embed\/|\/shorts\/)([A-Za-z0-9_-]{6,})/);
    return m ? m[1] : '';
  }

  // Nodo tarjeta-enlace: [icono] Texto → se abre al tocar (URLSpan en el render nativo).
  function embedCard(doc, href, label) {
    var a = doc.createElement('a');
    a.setAttribute('href', href);
    var b = doc.createElement('b');
    b.textContent = label;
    a.appendChild(b);
    return a;
  }

  // Miniatura de YouTube tocable: <a href=watch><img thumb></a> + pie "▶ YouTube".
  function youtubeThumb(doc, videoUrl, id) {
    var wrap = doc.createElement('div');
    var a = doc.createElement('a');
    a.setAttribute('href', videoUrl);
    var img = doc.createElement('img');
    img.setAttribute('src', 'https://i.ytimg.com/vi/' + id + '/hqdefault.jpg');
    a.appendChild(img);
    wrap.appendChild(a);
    wrap.appendChild(doc.createElement('br'));
    wrap.appendChild(embedCard(doc, videoUrl, '▶ Ver en YouTube'));
    return wrap;
  }

  function processEmbeds(m, doc, pageUrl) {
    // Twitter / X
    m.querySelectorAll('blockquote.twitter-tweet').forEach(function (bq) {
      var a = bq.querySelector('a[href*="/status/"]') || bq.querySelector('a[href]');
      var href = a ? a.getAttribute('href') : '';
      var who = (href.match(/(?:twitter|x)\.com\/([^\/]+)\/status/) || [])[1] || '';
      bq.replaceWith(embedCard(doc, href || pageUrl, who ? ('🐦  Ver tweet de @' + who) : '🐦  Ver tweet'));
    });
    // Instagram
    m.querySelectorAll('blockquote.instagram-media, [data-instgrm-permalink]').forEach(function (bq) {
      var href = bq.getAttribute('data-instgrm-permalink') ||
        (bq.querySelector('a[href]') ? bq.querySelector('a[href]').getAttribute('href') : '');
      href = (href || '').split('?')[0];
      bq.replaceWith(embedCard(doc, href || pageUrl, '📷  Ver publicación de Instagram'));
    });
    // TikTok (el blockquote llega vacío → sin esto no se ve NADA)
    m.querySelectorAll('blockquote.tiktok-embed').forEach(function (bq) {
      var vid = bq.getAttribute('data-video-id') || '';
      var href = vid ? ('https://www.tiktok.com/@x/video/' + vid) :
        (bq.querySelector('a[href]') ? bq.querySelector('a[href]').getAttribute('href') : pageUrl);
      bq.replaceWith(embedCard(doc, href, '🎵  Ver vídeo de TikTok'));
    });
    // YouTube en iframe
    m.querySelectorAll('iframe[src*="youtube"], iframe[src*="youtu.be"], iframe[src*="ytimg"]').forEach(function (f) {
      var id = ytIdFrom(f.getAttribute('src') || '');
      if (id) f.replaceWith(youtubeThumb(doc, 'https://www.youtube.com/watch?v=' + id, id));
    });
    // YouTube como enlace pelado (lo más común en FC)
    m.querySelectorAll('a[href*="youtube.com/watch"], a[href*="youtu.be/"], a[href*="youtube.com/shorts"]').forEach(function (a) {
      var id = ytIdFrom(a.getAttribute('href') || '');
      if (id) a.replaceWith(youtubeThumb(doc, a.getAttribute('href'), id));
    });
  }

  // Recoge los embeds de un mensaje como SPECS y los QUITA del HTML: el render nativo los
  // pinta como reproductores oficiales interactivos (EmbedView, WebView por embed). Sustituye
  // a processEmbeds en la vista de hilo — ya no degradamos a tarjeta-enlace.
  function collectEmbeds(m, doc) {
    var embeds = [];
    m.querySelectorAll('blockquote.twitter-tweet').forEach(function (bq) {
      var a = bq.querySelector('a[href*="/status/"]') || bq.querySelector('a[href]');
      var href = a ? a.getAttribute('href') : '';
      if (href) embeds.push({ kind: 'twitter', url: href.split('?')[0], id: '' });
      bq.remove();
    });
    m.querySelectorAll('blockquote.instagram-media, [data-instgrm-permalink]').forEach(function (bq) {
      var href = bq.getAttribute('data-instgrm-permalink') ||
        (bq.querySelector('a[href]') ? bq.querySelector('a[href]').getAttribute('href') : '');
      href = (href || '').split('?')[0];
      if (href) embeds.push({ kind: 'instagram', url: href, id: '' });
      bq.remove();
    });
    m.querySelectorAll('blockquote.tiktok-embed').forEach(function (bq) {
      var vid = bq.getAttribute('data-video-id') || '';
      var cite = bq.getAttribute('cite') ||
        (bq.querySelector('a[href]') ? bq.querySelector('a[href]').getAttribute('href') : '');
      embeds.push({ kind: 'tiktok', url: (cite || '').split('?')[0], id: vid });
      bq.remove();
    });
    m.querySelectorAll('iframe[src*="youtube"], iframe[src*="youtu.be"], iframe[src*="ytimg"]').forEach(function (f) {
      var id = ytIdFrom(f.getAttribute('src') || '');
      if (id) embeds.push({ kind: 'youtube', url: '', id: id });
      f.remove();
    });
    m.querySelectorAll('a[href*="youtube.com/watch"], a[href*="youtu.be/"], a[href*="youtube.com/shorts"]').forEach(function (a) {
      var id = ytIdFrom(a.getAttribute('href') || '');
      if (id) { embeds.push({ kind: 'youtube', url: '', id: id }); a.remove(); }
    });
    m.querySelectorAll('video').forEach(function (v) {
      var src = v.getAttribute('src') ||
        (v.querySelector('source') ? v.querySelector('source').getAttribute('src') : '');
      if (src) { try { src = new URL(src, 'https://forocoches.com/foro/').href; } catch (e) {} embeds.push({ kind: 'video', url: src, id: '' }); }
      v.remove();
    });
    m.querySelectorAll('iframe,embed,object').forEach(function (f) {
      var src = f.src || f.getAttribute('src') || f.getAttribute('data') || '';
      if (src) { try { src = new URL(src, 'https://forocoches.com/foro/').href; } catch (e) {} embeds.push({ kind: 'iframe', url: src, id: '' }); }
      f.remove();
    });
    return embeds;
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
          // Tolerar separador de miles ("1.680"): sin él, los hilos con ≥1.000
          // respuestas salían sin contador (mismo bug que en parseSearchDoc).
          if (/^[\d.,]+$/.test(t)) { replies = t.replace(/[.,]/g, ''); break; }
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

  // Resultados de búsqueda (Mis hilos / Participados). FC NO usa el markup threadbit
  // aquí: cada resultado es un bloque con el título en <a href="showthread.php?t=N&highlight=">
  // y la meta ("NN @ usuario", fecha) en <a href="showthread.php?p=...&highlight=">. El
  // discriminador fiable frente a menús/anuncios/notices es el sufijo &highlight= del href
  // (los enlaces de menú/notice/settings NO lo llevan).
  function parseSearchDoc(doc) {
    var seen = new Set();
    var threads = [];
    doc.querySelectorAll('a[href*="showthread.php?t="][href*="highlight"]').forEach(function (a) {
      var tid = tidOf(a);
      if (!tid || seen.has(tid) || inMenu(a)) return;
      var title = a.textContent.replace(/\s+/g, ' ').trim();
      if (!title) return;
      seen.add(tid);
      // Sube al bloque del resultado (el que agrupa título + meta + fecha, ≥3 enlaces highlight).
      var item = a, el = a;
      for (var i = 0; i < 6 && el; i++) {
        if (el.querySelectorAll('a[href*="highlight"]').length >= 2) item = el;
        if (el.querySelectorAll('a[href*="highlight"]').length >= 3) break;
        el = el.parentElement;
      }
      var author = '', replies = '', time = '';
      item.querySelectorAll('a[href*="highlight"]').forEach(function (x) {
        var tx = x.textContent.replace(/\s+/g, ' ').trim();
        // "90 @ usuario" — OJO: con ≥1.000 respuestas FC pone separador de miles
        // ("1.680 @ usuario"); sin tolerarlo, esos hilos salían SIN autor (bug real).
        var rm = tx.match(/^([\d.,]+)\s*@\s*(.+)$/);
        if (rm) { replies = rm[1].replace(/[.,]/g, ''); author = rm[2].trim(); }
        else if (tx !== title && /\d{1,2}:\d{2}|ayer|hoy|-\w{3}-/i.test(tx)) time = tx;
      });
      threads.push({
        tid: tid, title: title, author: author, replies: replies, time: time,
        url: 'https://forocoches.com/foro/showthread.php?t=' + tid
      });
    });
    return threads;
  }

  // Identidad del usuario logueado a partir de una página traída por fetch (el menú de
  // usuario del HTML recién descargado SÍ trae el u= real; el DOM VIVO puede estar en la
  // home de invitado y devolver u=0, que es justo lo que rompía Mis hilos).
  function ownIdentity(doc) {
    var id = { uid: '', user: '' };
    var hdr = doc.querySelector('.user-profile-menu-header[href*="u="]');
    if (hdr) {
      var m = (hdr.getAttribute('href') || '').match(/u=(\d+)/);
      if (m && m[1] !== '0') id.uid = m[1];
      var un = hdr.querySelector('.username');
      if (un) id.user = un.textContent.replace(/\s+/g, ' ').trim();
    }
    if (!id.uid) {
      var links = doc.querySelectorAll('a[href*="member.php?u="]');
      for (var i = 0; i < links.length; i++) {
        var mm = (links[i].getAttribute('href') || '').match(/u=(\d+)/);
        if (mm && mm[1] !== '0') { id.uid = mm[1]; break; }
      }
    }
    return id;
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
        return r.text().then(function (html) { return { html: html, finalUrl: r.url || '' }; });
      })
      .then(function (res) {
        var html = res.html;
        var doc = new DOMParser().parseFromString(html, 'text/html');
        if (/just a moment|attention required|un momento/i.test(doc.title || '')) {
          AndroidShell.onThreadError('cloudflare');
          return;
        }
        // Hilo restringido (+HD de invitado o sin antigüedad): FC NO enseña login,
        // REDIRIGE a una página informativa misc.php?do=page&template=Info. La señal
        // fiable es la URL final tras redirecciones. Extraemos la MISMA info que
        // enseña el foro (mensaje + metadatos + link de invitación) para reproducirla
        // con los estilos de la app.
        if (res.finalUrl.indexOf('misc.php') !== -1 || res.finalUrl.indexOf('showthread.php') === -1) {
          var info = { msg: '', meta: '', invite: '' };
          var strongs = doc.querySelectorAll('strong');
          for (var si = 0; si < strongs.length; si++) {
            var st = (strongs[si].textContent || '').replace(/\s+/g, ' ').trim();
            // El aviso de FC es el <strong> largo TODO en mayúsculas.
            if (st.length > 25 && st === st.toUpperCase() && /[A-ZÁÉÍÓÚÑ]/.test(st)) { info.msg = st; break; }
          }
          var meta = doc.querySelector('.smallfont-gray');
          if (meta) info.meta = (meta.textContent || '').replace(/\s+/g, ' ').trim();
          var inv = doc.querySelector('a[href*="/invitacion"]');
          if (inv) {
            try { info.invite = new URL(inv.getAttribute('href'), 'https://forocoches.com/').href; } catch (e) {}
          }
          AndroidShell.onThreadError('restricted:' + JSON.stringify(info));
          return;
        }
        var tid = (url.match(/[?&]t=(\d+)/) || [])[1] || '';
        // URLs por post (showthread.php?p=N, típicas de citas/menciones): el t= no va
        // en la URL; se saca del form de quick-reply de la propia página.
        if (!tid) {
          var tIn = doc.querySelector('input[name="t"]');
          if (tIn && /^\d+$/.test(tIn.value || '')) tid = tIn.value;
        }
        // Usuario logueado (para marcar los posts propios → menú editar/borrar).
        // El quick-reply del hilo trae un hidden loggedinuser con el nombre.
        var luEl = doc.querySelector('input[name="loggedinuser"]');
        var loggedUser = luEl ? (luEl.value || '').trim() : '';
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
          // Embeds (X/IG/TikTok/YouTube/vídeo/iframe) → specs; se quitan del HTML y se
          // renderizan como reproductores oficiales interactivos (EmbedView).
          var embeds = collectEmbeds(m, doc);
          m.querySelectorAll('a[href]').forEach(function (a) {
            try { a.setAttribute('href', new URL(a.getAttribute('href'), 'https://forocoches.com/foro/').href); } catch (e) {}
          });
          m.querySelectorAll('img[src]').forEach(function (i) {
            try { i.setAttribute('src', new URL(i.getAttribute('src'), 'https://forocoches.com/foro/').href); } catch (e) {}
          });

          // Post propio: autor == usuario logueado, o hay enlace de edición en el
          // postbit (FC solo lo pinta en los posts que puedes editar).
          var own = (loggedUser && author && author === loggedUser) ||
            !!wrap.querySelector('a[href*="editpost.php"]');

          posts.push({
            pid: pid, author: author, avatar: avatar, date: date,
            html: m.innerHTML, own: !!own, embeds: embeds
          });
        });
        if (!posts.length) {
          // Sin posts + form de login en la página = hilo que requiere cuenta (+HD
          // de invitado, p. ej.). La app enseña NUESTRO login nativo, nunca el foro.
          if (doc.querySelector('input[name="vb_login_username"]')) {
            AndroidShell.onThreadError('login');
            return;
          }
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
        // URLs por post (p=): la página real no va en la URL; se deduce de los
        // <link rel="next/prev"> que emite vBulletin en el <head>.
        if (!/[?&]page=/.test(url)) {
          var lnN = doc.querySelector('link[rel="next"]');
          var lnP = doc.querySelector('link[rel="prev"]');
          var nN = lnN ? ((lnN.getAttribute('href') || '').match(/[?&]page=(\d+)/) || [])[1] : null;
          var nP = lnP ? ((lnP.getAttribute('href') || '').match(/[?&]page=(\d+)/) || [])[1] : null;
          if (nN) page = Math.max(page, parseInt(nN, 10) - 1);
          else if (nP) page = Math.max(page, parseInt(nP, 10) + 1);
        }
        pageCount = Math.max(pageCount, page);
        var title = (doc.title || '').replace(/\s*-\s*Forocoches.*$/i, '').trim();
        AndroidShell.onThread(JSON.stringify({
          url: url, tid: tid, title: title, page: page, pageCount: pageCount,
          counts: menuCounts(doc), posts: posts
        }));
      })
      .catch(function (e) { AndroidShell.onThreadError(String(e)); });
  };

  // ── Responder desde UI nativa (Fase 3) ─────────────────────────────────────
  // NO reimplementamos el protocolo: traemos el form REAL de FC por fetch same-origin,
  // rellenamos solo 'message' y reenviamos con TODOS sus campos ocultos (securitytoken,
  // posthash, poststarttime, signature...) tal cual. FC lo acepta como su propio envío.
  window.fcSubmitReply = function (tid, message) {
    var base = 'https://forocoches.com/foro/newreply.php';
    fetch(base + '?do=newreply&t=' + tid, { credentials: 'same-origin' })
      .then(function (r) { return r.text(); })
      .then(function (html) {
        var doc = new DOMParser().parseFromString(html, 'text/html');
        var forms = [].slice.call(doc.querySelectorAll('form'));
        var f = forms.filter(function (x) { return x.querySelector('textarea[name="message"]'); })[0];
        if (!f) { AndroidShell.onReplyResult(JSON.stringify({ ok: false, error: 'no-form' })); return null; }
        var params = new URLSearchParams();
        f.querySelectorAll('input, textarea, select').forEach(function (el) {
          var nm = el.getAttribute('name'); if (!nm) return;
          var type = (el.getAttribute('type') || '').toLowerCase();
          if (type === 'file') return;
          if (type === 'submit') { if (nm === 'sbutton') params.set(nm, el.value || 'Responder'); return; }
          if (nm === 'preview') return;
          if (nm === 'message') { params.set(nm, message); return; }
          params.set(nm, el.value || '');
        });
        params.set('do', 'postreply');
        if (!params.has('message')) params.set('message', message);
        // El form de FC viene con wysiwyg=1 (editor visual HTML): en ese modo los \n
        // se colapsan como espacio en blanco. Nuestro composer manda texto plano +
        // BBCode → wysiwyg=0 para que vBulletin convierta los \n en saltos reales.
        params.set('wysiwyg', '0');
        return fetch(base + '?do=postreply', {
          method: 'POST', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: params.toString()
        });
      })
      .then(function (r) {
        if (!r) return;
        // ÉXITO = vBulletin redirige a showthread.php (r.url es la URL FINAL tras la
        // redirección). OJO: no vale mirar si hay textarea "message" en el HTML — la
        // página del hilo lleva quick-reply con ese mismo name y daba falsos errores.
        var finalUrl = r.url || '';
        return r.text().then(function (html) {
          var ok = finalUrl.indexOf('showthread.php') !== -1;
          if (!ok && /showthread\.php\?p=\d+/.test(html) && html.indexOf('newreply.php?do=postreply') === -1) {
            ok = true; // interstitial "gracias por su mensaje" con refresh al hilo
          }
          var errM = '';
          if (!ok) {
            var em = html.match(/<(?:div|li|p)[^>]*class="[^"]*(?:standard_error|error|blockrow)[^"]*"[^>]*>\s*([^<]{4,200})/i);
            errM = em ? em[1].replace(/\s+/g, ' ').trim() : ('http ' + r.status);
          }
          AndroidShell.onReplyResult(JSON.stringify({ ok: ok, error: errM }));
        });
      })
      .catch(function (e) { AndroidShell.onReplyResult(JSON.stringify({ ok: false, error: String(e) })); });
  };

  // ── Crear hilo / editar / borrar (mismo patrón: form real + su token) ───────

  // Crea un hilo en el subforo f: trae el form de newthread, copia sus campos,
  // pone asunto+mensaje y reenvía con do=postthread. Éxito = redirige al hilo.
  window.fcCreateThread = function (fid, subject, message) {
    var base = 'https://forocoches.com/foro/newthread.php';
    fetch(base + '?do=newthread&f=' + fid, { credentials: 'same-origin' })
      .then(function (r) { return r.text(); })
      .then(function (html) {
        var doc = new DOMParser().parseFromString(html, 'text/html');
        var f = formWith(doc, 'textarea[name="message"]');
        if (!f) { AndroidShell.onThreadAction(JSON.stringify({ action: 'create', ok: false, error: 'no-form' })); return null; }
        var params = copyFormFields(f, {
          subject: subject, message: message, do: 'postthread', wysiwyg: '0'
        }, ['preview']);
        if (!params.has('sbutton')) params.set('sbutton', 'Enviar el nuevo tema');
        return fetch(base + '?do=postthread', {
          method: 'POST', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: params.toString()
        });
      })
      .then(function (r) {
        if (!r) return;
        var fu = r.url || '';
        return r.text().then(function (h) {
          var tid = (fu.match(/showthread\.php\?t=(\d+)/) || [])[1] ||
            (h.match(/showthread\.php\?t=(\d+)/) || [])[1] || '';
          var ok = fu.indexOf('showthread.php') !== -1 || !!tid;
          AndroidShell.onThreadAction(JSON.stringify({ action: 'create', ok: ok, error: ok ? '' : extractErr(h), tid: tid }));
        });
      })
      .catch(function (e) { AndroidShell.onThreadAction(JSON.stringify({ action: 'create', ok: false, error: String(e) })); });
  };

  // Carga el BBCode actual de un post para precargar el editor al editar.
  // OJO wysiwyg=0 EN LA URL: sin él FC sirve el form en modo editor visual (wysiwyg=1) y
  // el textarea trae HTML (<b>, <br>, <img> de smilies), no BBCode. Como al guardar
  // mandamos wysiwyg=0 ("esto es BBCode"), vBulletin escapaba ese HTML y el post salía
  // con todas las etiquetas literales (bug real que hubo). FC respeta el parámetro.
  window.fcLoadPostForEdit = function (pid) {
    fetch('https://forocoches.com/foro/editpost.php?do=editpost&wysiwyg=0&p=' + pid, { credentials: 'same-origin' })
      .then(function (r) { return r.text(); })
      .then(function (html) {
        var doc = new DOMParser().parseFromString(html, 'text/html');
        var ta = doc.querySelector('textarea[name="message"]');
        // Título del primer post: 'title' (skin nuevo) o 'subject'. Solo los primeros
        // posts lo traen con valor; en respuestas normales no existe o va vacío.
        var subj = doc.querySelector('input[name="title"]') || doc.querySelector('input[name="subject"]');
        var hasSubject = !!(subj && (subj.value || '').trim());
        AndroidShell.onEditLoad(JSON.stringify({
          pid: pid, ok: !!ta,
          message: ta ? ta.value : '',
          subject: subj ? (subj.value || '') : '',
          hasSubject: hasSubject
        }));
      })
      .catch(function (e) { AndroidShell.onEditLoad(JSON.stringify({ pid: pid, ok: false, error: String(e) })); });
  };

  // Guarda la edición de un post (do=updatepost). Éxito = vuelve al hilo.
  window.fcEditPost = function (pid, message, subject) {
    var base = 'https://forocoches.com/foro/editpost.php';
    // wysiwyg=0 también aquí: el form del que copiamos los campos ocultos debe estar en el
    // MISMO modo (BBCode) con el que enviamos.
    fetch(base + '?do=editpost&wysiwyg=0&p=' + pid, { credentials: 'same-origin' })
      .then(function (r) { return r.text(); })
      .then(function (html) {
        var doc = new DOMParser().parseFromString(html, 'text/html');
        var f = formWith(doc, 'textarea[name="message"]');
        if (!f) { AndroidShell.onThreadAction(JSON.stringify({ action: 'edit', ok: false, error: 'no-form' })); return null; }
        var ov = { message: message, do: 'updatepost', wysiwyg: '0' };
        // El primer post del hilo lleva el título en 'title' (skin nuevo) o 'subject'.
        if (subject) {
          if (f.querySelector('input[name="title"]')) ov.title = subject;
          else if (f.querySelector('input[name="subject"]')) ov.subject = subject;
        }
        var params = copyFormFields(f, ov, ['preview']);
        if (!params.has('sbutton')) params.set('sbutton', 'Guardar cambios');
        return fetch(base + '?do=updatepost', {
          method: 'POST', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: params.toString()
        });
      })
      .then(function (r) {
        if (!r) return;
        var fu = r.url || '';
        return r.text().then(function (h) {
          var ok = fu.indexOf('showthread.php') !== -1;
          AndroidShell.onThreadAction(JSON.stringify({ action: 'edit', ok: ok, error: ok ? '' : extractErr(h), pid: pid }));
        });
      })
      .catch(function (e) { AndroidShell.onThreadAction(JSON.stringify({ action: 'edit', ok: false, error: String(e) })); });
  };

  // Borra un post propio. En el skin nuevo la página de confirmación viene VACÍA
  // (el borrado se dispara por JS), así que tomamos el token del form de EDICIÓN
  // (mismo editpost, sí lo trae) y montamos el POST de borrado a mano. Verificamos
  // el resultado re-consultando si el post sigue siendo editable (si no, se borró).
  window.fcDeletePost = function (pid) {
    var base = 'https://forocoches.com/foro/editpost.php';
    fetch(base + '?do=editpost&p=' + pid, { credentials: 'same-origin' })
      .then(function (r) { return r.text(); })
      .then(function (html) {
        var doc = new DOMParser().parseFromString(html, 'text/html');
        var f = formWith(doc, 'textarea[name="message"]');
        if (!f) { AndroidShell.onThreadAction(JSON.stringify({ action: 'delete', ok: false, error: 'no-form' })); return null; }
        var st = f.querySelector('[name="securitytoken"]');
        var sEl = f.querySelector('[name="s"]');
        var params = new URLSearchParams();
        params.set('do', 'deletepost');
        params.set('postid', pid);
        params.set('p', pid);
        params.set('securitytoken', st ? (st.value || '') : '');
        if (sEl) params.set('s', sEl.value || '');
        params.set('deletepost', 'delete'); // radio "borrar" (vs restaurar)
        params.set('deletetype', '1');      // borrado normal (soft)
        params.set('reason', '');
        return fetch(base + '?do=deletepost', {
          method: 'POST', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: params.toString()
        });
      })
      .then(function (r) {
        if (!r) return;
        return r.text().then(function () {
          // Verificación fiable: ¿sigue editable el post? Si ya no, quedó borrado.
          return fetch(base + '?do=editpost&p=' + pid, { credentials: 'same-origin' })
            .then(function (r2) { return r2.text(); })
            .then(function (h2) {
              var doc2 = new DOMParser().parseFromString(h2, 'text/html');
              var gone = !doc2.querySelector('textarea[name="message"]');
              AndroidShell.onThreadAction(JSON.stringify({
                action: 'delete', ok: gone, error: gone ? '' : 'No se pudo borrar el mensaje', pid: pid
              }));
            });
        });
      })
      .catch(function (e) { AndroidShell.onThreadAction(JSON.stringify({ action: 'delete', ok: false, error: String(e) })); });
  };

  // Favoritos (suscripción a hilo). Verificado por CDP (2026-07-22):
  // - El botón de showthread es ESTÁTICO (siempre "addsubscription", esté suscrito o no):
  //   el estado real SOLO se ve en las filas de subscription.php.
  // - Alta: form real de do=addsubscription → POST a do=doaddsubscription (la respuesta
  //   es 200 sin redirección útil: verificar re-consultando la lista).
  // - Baja: la página do=removesubscription del skin nuevo es un STUB vacío (como la de
  //   deletepost) pero el GET YA EJECUTA el borrado server-side; añadimos securitytoken
  //   igualmente y verificamos re-consultando la lista.
  // - GOTCHA VARNISH: FC sirve HTML tras Varnish y páginas PRIVADAS como subscription.php
  //   llegan de caché (x-cache: HIT, age de hasta ~1 min). Leer el estado justo después
  //   de cambiarlo devuelve el estado ANTERIOR y cache:'no-store' NO ayuda (la caché es
  //   del servidor). Antídoto: query param único (_fp=timestamp) → tratada como URL
  //   nueva → contenido fresco (verificado: x-cache pasa de HIT a SHARED con age 0).
  window.fcToggleFavorite = function (tid) {
    var base = 'https://forocoches.com/foro/subscription.php';
    function fail(e) {
      AndroidShell.onThreadAction(JSON.stringify({ action: 'fav', ok: false, error: String(e), tid: tid }));
    }
    function fetchDoc(url) {
      return fetch(url, { credentials: 'same-origin' })
        .then(function (r) { return r.text(); })
        .then(function (h) { return new DOMParser().parseFromString(h, 'text/html'); });
    }
    function freshList() { return fetchDoc(base + '?_fp=' + Date.now()); }
    // Suscrito = el tid aparece como FILA de la lista (mismo criterio que la pestaña
    // Favoritos: parseListDoc), no como link de menú/banner.
    function subscribed(doc) {
      return parseListDoc(doc).some(function (t) { return t.tid === tid; });
    }
    freshList()
      .then(function (doc) {
        if (!subscribed(doc)) {
          // ALTA: copiar el form real (securitytoken, s, threadid, url, folderid...).
          return fetchDoc(base + '?do=addsubscription&t=' + tid + '&_fp=' + Date.now()).then(function (d2) {
            var f = formWith(d2, 'input[value="doaddsubscription"]');
            if (!f) { fail('login'); return null; }
            var params = copyFormFields(f, { emailupdate: '0' }, []);
            return fetch(base + '?do=doaddsubscription&threadid=' + tid, {
              method: 'POST', credentials: 'same-origin',
              headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
              body: params.toString()
            }).then(function () { return true; });
          });
        }
        // BAJA: GET con token (la página devuelta es un stub; el efecto es server-side).
        var tokEl = doc.querySelector('input[name="securitytoken"]');
        var tok = tokEl ? tokEl.value : '';
        return fetch(base + '?do=removesubscription&t=' + tid +
          (tok ? '&securitytoken=' + encodeURIComponent(tok) : ''),
          { credentials: 'same-origin' }).then(function () { return false; });
      })
      .then(function (wanted) {
        if (wanted === null || wanted === undefined) return;
        // Verificación con evidencia: ¿quedó la lista como debía? (fresca, sin Varnish)
        return freshList().then(function (doc) {
          var now = subscribed(doc);
          AndroidShell.onThreadAction(JSON.stringify({
            action: 'fav', ok: now === wanted, fav: now, tid: tid,
            error: now === wanted ? '' : 'La suscripción no cambió'
          }));
        });
      })
      .catch(fail);
  };

  // ── Mensajes privados nativos ──────────────────────────────────────────────
  // La regla de oro exige que los MP NO caigan a la capa web. Aquí se extrae la
  // bandeja y el detalle de private.php (fetch same-origin) y se emiten como JSON;
  // el envío copia el form REAL (do=newpm → do=insertpm, wysiwyg=0). Cache-buster _fp
  // por el gotcha Varnish: tras enviar/leer, la lista podría llegar rancia.

  // Simplifica el cuerpo de un MP igual que un post (citas → blockquote, embeds →
  // tarjeta, URLs absolutas) para el mismo render nativo.
  function simplifyPmBody(msg, doc) {
    var m = msg.cloneNode(true);
    m.querySelectorAll('script,style').forEach(function (x) { x.remove(); });
    m.querySelectorAll('div.quote').forEach(function (q) {
      var bq = doc.createElement('blockquote');
      var qb = q.querySelector('b');
      var who = qb ? qb.textContent.trim() : '';
      bq.innerHTML = (who ? '<b>' + who + ' dijo:</b><br>' : '') + q.innerHTML;
      q.replaceWith(bq);
    });
    processEmbeds(m, doc, 'https://forocoches.com/foro/private.php');
    m.querySelectorAll('iframe,video,embed,object').forEach(function (f) {
      var src = f.src || f.getAttribute('src') || '';
      f.replaceWith(embedCard(doc, src || '#', '▶  Ver contenido'));
    });
    m.querySelectorAll('a[href]').forEach(function (a) {
      try { a.setAttribute('href', new URL(a.getAttribute('href'), 'https://forocoches.com/foro/').href); } catch (e) {}
    });
    m.querySelectorAll('img[src]').forEach(function (i) {
      try { i.setAttribute('src', new URL(i.getAttribute('src'), 'https://forocoches.com/foro/').href); } catch (e) {}
    });
    return m.innerHTML;
  }

  window.fcLoadPmInbox = function () {
    fetch('https://forocoches.com/foro/private.php?_fp=' + Date.now(), { credentials: 'same-origin' })
      .then(function (r) { return r.text(); })
      .then(function (html) {
        var doc = new DOMParser().parseFromString(html, 'text/html');
        if (/just a moment|attention required|un momento/i.test(doc.title || '')) {
          AndroidShell.onPmData(JSON.stringify({ view: 'inbox', error: 'cloudflare' })); return;
        }
        var pms = [], seen = {};
        doc.querySelectorAll('a[href*="do=showpm"]').forEach(function (a) {
          var mm = (a.getAttribute('href') || '').match(/pmid=(\d+)/);
          if (!mm || seen[mm[1]]) return;
          var pmid = mm[1];
          var subject = (a.querySelector('strong') || a).textContent.replace(/\s+/g, ' ').trim();
          if (!subject) return;
          seen[pmid] = 1;
          // Bloque de la fila: subir hasta tener remitente y fecha en el mismo contenedor.
          var row = a;
          for (var i = 0; i < 6 && row.parentElement; i++) {
            row = row.parentElement;
            if (row.querySelector('[onclick*="member.php"], a[href*="member.php?u="]')) break;
          }
          var senderEl = row.querySelector('[onclick*="member.php"], a[href*="member.php?u="]');
          var sender = senderEl ? senderEl.textContent.replace(/\s+/g, ' ').trim() : '';
          var sid = '';
          if (senderEl) {
            var sm = (senderEl.getAttribute('onclick') || senderEl.getAttribute('href') || '').match(/u=(\d+)/);
            if (sm) sid = sm[1];
          }
          var dm = row.textContent.match(/(Hoy|Ayer|\d{1,2}-[a-z]{3,4}-\d{2,4})[, ]*\d{1,2}:\d{2}/i);
          var unread = row.innerHTML.indexOf('message-unread-icon') !== -1;
          pms.push({
            pmid: pmid, subject: subject, sender: sender, senderId: sid,
            date: dm ? dm[0] : '', unread: unread
          });
        });
        AndroidShell.onPmData(JSON.stringify({
          view: 'inbox', pms: pms, menu: menuLinks(), counts: menuCounts(doc)
        }));
      })
      .catch(function (e) { AndroidShell.onPmData(JSON.stringify({ view: 'inbox', error: String(e) })); });
  };

  window.fcLoadPm = function (pmid) {
    fetch('https://forocoches.com/foro/private.php?do=showpm&pmid=' + pmid + '&_fp=' + Date.now(),
      { credentials: 'same-origin' })
      .then(function (r) { return r.text(); })
      .then(function (html) {
        var doc = new DOMParser().parseFromString(html, 'text/html');
        if (/just a moment|attention required|un momento/i.test(doc.title || '')) {
          AndroidShell.onPmData(JSON.stringify({ view: 'detail', pmid: pmid, error: 'cloudflare' })); return;
        }
        var msg = doc.querySelector('[id^="post_message_"]');
        var body = msg ? simplifyPmBody(msg, doc) : '';
        // Remitente: primer member.php; su texto puede traer "N Posts M Hilos" pegado.
        var senderEl = doc.querySelector('a[href*="member.php?u="], [onclick*="member.php"]');
        var sender = senderEl ? senderEl.textContent.replace(/\s+/g, ' ').trim() : '';
        sender = (sender.match(/^[^\d]+/) || [sender])[0].trim() || sender;
        var subj = (doc.title || '').replace(/^Forocoches\s*-\s*/, '').trim();
        var reply = doc.querySelector('a[href*="do=newpm&pmid="], a[href*="do=newpm&amp;pmid="]');
        AndroidShell.onPmData(JSON.stringify({
          view: 'detail', pmid: pmid, subject: subj, sender: sender,
          body: body, canReply: !!reply
        }));
      })
      .catch(function (e) { AndroidShell.onPmData(JSON.stringify({ view: 'detail', pmid: pmid, error: String(e) })); });
  };

  // Enviar MP: nuevo (recipients+title) o respuesta (pmid presente). Copia el form
  // REAL de FC con wysiwyg=0 (mismo principio que fcSubmitReply/fcCreateThread) y solo
  // sustituye destinatario/asunto/mensaje. Éxito = FC redirige fuera de newpm/insertpm.
  window.fcSendPm = function (recipients, title, message, pmid) {
    var formUrl = pmid
      ? 'https://forocoches.com/foro/private.php?do=newpm&pmid=' + pmid + '&wysiwyg=0'
      : 'https://forocoches.com/foro/private.php?do=newpm&wysiwyg=0';
    fetch(formUrl, { credentials: 'same-origin' })
      .then(function (r) { return r.text(); })
      .then(function (html) {
        var doc = new DOMParser().parseFromString(html, 'text/html');
        var f = formWith(doc, 'textarea[name="message"]');
        if (!f) { AndroidShell.onPmData(JSON.stringify({ view: 'send', ok: false, error: 'no-form' })); return null; }
        var ov = { message: message, wysiwyg: '0', do: 'insertpm' };
        if (recipients) ov.recipients = recipients;
        if (title) ov.title = title;
        var params = copyFormFields(f, ov, ['preview']);
        if (!params.has('sbutton')) params.set('sbutton', 'Enviar Mensaje');
        var action = pmid ? ('private.php?do=insertpm&pmid=' + pmid) : 'private.php?do=insertpm';
        return fetch('https://forocoches.com/foro/' + action, {
          method: 'POST', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: params.toString()
        });
      })
      .then(function (r) {
        if (!r) return;
        var fu = r.url || '';
        return r.text().then(function (h) {
          var err = extractErr(h);
          var ok = !err && fu.indexOf('insertpm') === -1 && fu.indexOf('do=newpm') === -1;
          AndroidShell.onPmData(JSON.stringify({
            view: 'send', ok: ok, error: ok ? '' : (err || 'No se pudo enviar el mensaje')
          }));
        });
      })
      .catch(function (e) { AndroidShell.onPmData(JSON.stringify({ view: 'send', ok: false, error: String(e) })); });
  };

  // ── Login desde UI nativa (Fase 3) ─────────────────────────────────────────
  // Mismo principio que fcSubmitReply: traemos el form REAL de login de FC, copiamos
  // sus campos (securitytoken incluido) y solo rellenamos usuario y contraseña. El
  // POST sale same-origin del WebView, así que las cookies de sesión quedan puestas.
  // La credencial la teclea el usuario en la app y NUNCA sale hacia otro dominio.
  window.fcLogin = function (user, pass) {
    fetch('https://forocoches.com/foro/login.php', { credentials: 'same-origin' })
      .then(function (r) { return r.text(); })
      .then(function (html) {
        var doc = new DOMParser().parseFromString(html, 'text/html');
        if (/just a moment|attention required|un momento/i.test(doc.title || '')) {
          AndroidShell.onLoginResult(JSON.stringify({ posted: false, error: 'cloudflare' }));
          return null;
        }
        var f = null;
        doc.querySelectorAll('form').forEach(function (x) {
          if (!f && x.querySelector('input[name="vb_login_username"]')) f = x;
        });
        var params = new URLSearchParams();
        if (f) {
          f.querySelectorAll('input').forEach(function (el) {
            var nm = el.getAttribute('name'); if (!nm) return;
            var type = (el.getAttribute('type') || '').toLowerCase();
            if (type === 'submit' || type === 'file') return;
            params.set(nm, el.value || '');
          });
        }
        params.set('vb_login_username', user);
        params.set('vb_login_password', pass);
        // md5 vacío = vBulletin valida la contraseña en servidor (comportamiento estándar
        // cuando el JS del form no corre).
        params.set('vb_login_md5password', '');
        params.set('vb_login_md5password_utf', '');
        params.set('cookieuser', '1');
        params.set('do', 'login');
        if (!params.has('securitytoken') || !params.get('securitytoken')) params.set('securitytoken', 'guest');
        if (!params.has('s')) params.set('s', '');
        return fetch('https://forocoches.com/foro/login.php?do=login', {
          method: 'POST', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: params.toString()
        });
      })
      .then(function (r) {
        if (!r) return;
        return r.text().then(function (loginHtml) {
          // El HTML del menú es IDÉNTICO para invitados y logueados (esqueleto), así
          // que el veredicto de éxito lo da Kotlin mirando la cookie de sesión
          // (bbuserid, HttpOnly — visible para CookieManager, no para JS). Aquí solo
          // extraemos el posible mensaje de error del formulario.
          var err = '';
          var em = loginHtml.match(/<(?:div|li|p)[^>]*class="[^"]*(?:standard_error|error|blockrow)[^"]*"[^>]*>\s*([^<]{4,200})/i);
          if (em) err = em[1].replace(/\s+/g, ' ').trim();
          AndroidShell.onLoginResult(JSON.stringify({ posted: true, error: err }));
        });
      })
      .catch(function (e) { AndroidShell.onLoginResult(JSON.stringify({ posted: false, error: String(e) })); });
  };

  // ── Citas / Menciones aisladas (Bloque B) ──────────────────────────────────
  // Viven en member.php?u=N&tab=quotes|mentions dentro de div.quotes-mentions-wrapper
  // .quotes / .mentions como tabla tborder ("Sin resultados" si no hay).
  window.fcLoadNotices = function (url, kind) {
    fetch(url, { credentials: 'same-origin' })
      .then(function (r) {
        if (!r.ok) throw new Error('http ' + r.status);
        return r.text();
      })
      .then(function (html) {
        var doc = new DOMParser().parseFromString(html, 'text/html');
        if (/just a moment|attention required|un momento/i.test(doc.title || '')) {
          AndroidShell.onNotices(JSON.stringify({ kind: kind, error: 'cloudflare' }));
          return;
        }
        var cls = kind === 'quotes' ? 'quotes' : 'mentions';
        var wrap = doc.querySelector('.quotes-mentions-wrapper.' + cls);
        var items = [];
        if (wrap) {
          wrap.querySelectorAll('tr').forEach(function (tr) {
            var a = tr.querySelector('a[href*="showthread.php"]');
            if (!a) return; // fila "Sin resultados" u otras
            var href = '';
            try { href = new URL(a.getAttribute('href'), 'https://forocoches.com/foro/').href; } catch (e) { return; }
            var title = a.textContent.replace(/\s+/g, ' ').trim();
            var who = '';
            var m = tr.querySelector('a[href*="member.php"]');
            if (m) who = m.textContent.replace(/\s+/g, ' ').trim();
            var text = tr.textContent.replace(/\s+/g, ' ').trim();
            items.push({ url: href, title: title, who: who, text: text.slice(0, 200) });
          });
        }
        AndroidShell.onNotices(JSON.stringify({ kind: kind, items: items }));
      })
      .catch(function (e) { AndroidShell.onNotices(JSON.stringify({ kind: kind, error: String(e) })); });
  };

  // ── Perfil + logout (Bloque B) ──────────────────────────────────────────────
  window.fcLoadProfile = function (url) {
    fetch(url, { credentials: 'same-origin' })
      .then(function (r) { return r.text(); })
      .then(function (html) {
        var doc = new DOMParser().parseFromString(html, 'text/html');
        var name = ((doc.title || '').match(/Ver Perfil:\s*(.+)$/i) || [])[1] || '';
        var avatar = '';
        var av = doc.querySelector('img[src*="avatar"], img[src*="image.php"]');
        if (av) {
          try { avatar = new URL(av.getAttribute('src'), 'https://forocoches.com/foro/').href; } catch (e) {}
        }
        // El link de logout (con su logouthash) viene en la propia página.
        var lo = '';
        var loA = doc.querySelector('a[href*="do=logout"]');
        if (loA) {
          try { lo = new URL(loA.getAttribute('href'), 'https://forocoches.com/foro/').href; } catch (e) {}
        }
        AndroidShell.onProfile(JSON.stringify({ name: name.trim(), avatar: avatar, logout: lo }));
      })
      .catch(function (e) { AndroidShell.onProfile(JSON.stringify({ error: String(e) })); });
  };

  /** Cierra la sesión llamando al link real de logout (con logouthash). */
  window.fcLogout = function (logoutUrl) {
    fetch(logoutUrl, { credentials: 'same-origin' })
      .then(function (r) { return r.text(); })
      .then(function () { AndroidShell.onLogoutDone('ok'); })
      .catch(function (e) { AndroidShell.onLogoutDone(String(e)); });
  };

  // ── Smilies de FC (Bloque A: editor) ───────────────────────────────────────
  // La página getsmilies trae <img id="smilie_N" alt=":codigo:" title="nombre">.
  window.fcLoadSmilies = function () {
    fetch('https://forocoches.com/foro/misc.php?do=getsmilies&editorid=vB_Editor_001', { credentials: 'same-origin' })
      .then(function (r) { return r.text(); })
      .then(function (html) {
        var doc = new DOMParser().parseFromString(html, 'text/html');
        var list = [];
        doc.querySelectorAll('img[id^="smilie_"]').forEach(function (im) {
          var code = im.getAttribute('alt') || '';
          if (!code) return;
          var src = '';
          try { src = new URL(im.getAttribute('src'), 'https://forocoches.com/foro/').href; } catch (e) {}
          if (src) list.push({ code: code, src: src });
        });
        if (list.length) AndroidShell.onSmilies(JSON.stringify({ smilies: list }));
      })
      .catch(function (e) { /* sin smilies el editor sigue funcionando */ });
  };

  // API pública para la app: carga un listado por fetch same-origin y lo entrega parseado.
  // Sirve para forumdisplay, subscription y search (Mis hilos): la finalUrl tras
  // redirecciones permite paginar búsquedas (search.php?searchid=N&page=M).
  window.fcLoadThreadList = function (url) {
    fetch(url, { credentials: 'same-origin' })
      .then(function (r) {
        if (!r.ok) throw new Error('http ' + r.status);
        return r.text().then(function (html) { return { html: html, finalUrl: r.url || url }; });
      })
      .then(function (res) {
        var html = res.html;
        var doc = new DOMParser().parseFromString(html, 'text/html');
        // Challenge de Cloudflare: avisar a la app para que enseñe el WebView y lo resuelva
        // el usuario como en el navegador.
        if (/just a moment|attention required|un momento/i.test(doc.title || '')) {
          AndroidShell.onListError('cloudflare');
          return;
        }
        var payload = { url: url, finalUrl: res.finalUrl, menu: menuLinks(), counts: menuCounts(doc), threads: parseListDoc(doc) };
        if (!payload.threads.length) {
          AndroidShell.onListError('empty'); // canario: 0 hilos = probable cambio de HTML
          return;
        }
        AndroidShell.onThreadList(JSON.stringify(payload));
      })
      .catch(function (e) { AndroidShell.onListError(String(e)); });
  };

  // Buscador. Reusa la MISMA búsqueda de vBulletin que Mis hilos/Participados: POST a
  // search.php?do=process con showposts=0 (resultados agrupados por HILO, no posts sueltos)
  // y el mismo parseSearchDoc. OJO: FC exige sesión para buscar — de invitado el token es
  // 'guest', no existe el formulario y devuelve la home.
  window.fcSearch = function (query, titleOnly, pageUrl) {
    function emit(doc, finalUrl) {
      if (/just a moment|attention required|un momento/i.test(doc.title || '')) {
        AndroidShell.onListError('cloudflare'); return;
      }
      var threads = parseSearchDoc(doc);
      if (!threads.length) { AndroidShell.onListError('empty'); return; }
      AndroidShell.onThreadList(JSON.stringify({
        url: finalUrl, finalUrl: finalUrl, menu: menuLinks(),
        counts: menuCounts(doc), threads: threads
      }));
    }
    if (pageUrl) {
      fetch(pageUrl, { credentials: 'same-origin' })
        .then(function (r) { return r.text().then(function (h) { return { h: h, u: r.url || pageUrl }; }); })
        .then(function (res) { emit(new DOMParser().parseFromString(res.h, 'text/html'), res.u); })
        .catch(function (e) { AndroidShell.onListError(String(e)); });
      return;
    }
    fetch('https://forocoches.com/foro/search.php', { credentials: 'same-origin' })
      .then(function (r) { return r.text(); })
      .then(function (html) {
        var sdoc = new DOMParser().parseFromString(html, 'text/html');
        var tokEl = sdoc.querySelector('input[name="securitytoken"]');
        var token = tokEl ? tokEl.value : '';
        if (!token || token === 'guest') { AndroidShell.onListError('login'); return null; }
        var body = new URLSearchParams();
        body.set('do', 'process'); body.set('securitytoken', token);
        body.set('query', query); body.set('titleonly', titleOnly ? '1' : '0');
        body.set('showposts', '0'); body.set('dosearch', 'Buscar');
        return fetch('https://forocoches.com/foro/search.php?do=process', {
          method: 'POST', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body.toString()
        }).then(function (r) { return r.text().then(function (h) { return { h: h, u: r.url }; }); });
      })
      .then(function (res) {
        if (!res) return;
        emit(new DOMParser().parseFromString(res.h, 'text/html'), res.u);
      })
      .catch(function (e) { AndroidShell.onListError(String(e)); });
  };

  // Mis hilos ('started') y Participados ('participated'). El UID real NO está en el DOM
  // vivo (FC lo enmascara a u=0), así que se resuelve del HTML recién traído. 'started'
  // usa la búsqueda finduser (hilos iniciados); 'participated' usa la búsqueda por nombre
  // de usuario con showposts=0 para que agrupe en HILOS y no en posts sueltos. Ambas
  // comparten el markup de resultados → parseSearchDoc. La finalUrl (searchid) permite paginar.
  window.fcLoadOwnThreads = function (mode, pageUrl) {
    function emit(doc, finalUrl) {
      if (/just a moment|attention required|un momento/i.test(doc.title || '')) {
        AndroidShell.onListError('cloudflare'); return;
      }
      var threads = parseSearchDoc(doc);
      var payload = { url: finalUrl, finalUrl: finalUrl, menu: menuLinks(), counts: menuCounts(doc), threads: threads };
      if (!threads.length) { AndroidShell.onListError('empty'); return; }
      AndroidShell.onThreadList(JSON.stringify(payload));
    }
    // Paginación: la URL con searchid ya identifica la búsqueda; solo hace falta traerla.
    if (pageUrl) {
      fetch(pageUrl, { credentials: 'same-origin' })
        .then(function (r) { return r.text().then(function (h) { return { h: h, u: r.url || pageUrl }; }); })
        .then(function (res) { emit(new DOMParser().parseFromString(res.h, 'text/html'), res.u); })
        .catch(function (e) { AndroidShell.onListError(String(e)); });
      return;
    }
    // Página 1: resolver identidad (uid + usuario) y el token de búsqueda del HTML traído.
    fetch('https://forocoches.com/foro/search.php', { credentials: 'same-origin' })
      .then(function (r) { return r.text(); })
      .then(function (html) {
        var sdoc = new DOMParser().parseFromString(html, 'text/html');
        var id = ownIdentity(sdoc);
        var tokEl = sdoc.querySelector('input[name="securitytoken"]');
        var token = tokEl ? tokEl.value : '';
        if (mode === 'started' && id.uid) {
          // Hilos iniciados: finduser por UID (GET), formato de hilos garantizado.
          var gurl = 'https://forocoches.com/foro/search.php?do=finduser&u=' + id.uid + '&starteronly=1';
          return fetch(gurl, { credentials: 'same-origin' })
            .then(function (r) { return r.text().then(function (h) { return { h: h, u: r.url || gurl }; }); });
        }
        // Participados (o sin uid): búsqueda por nombre de usuario, agrupada en hilos.
        if (!id.user || !token) { AndroidShell.onListError('empty'); return null; }
        var body = new URLSearchParams();
        body.set('do', 'process'); body.set('securitytoken', token);
        body.set('query', ''); body.set('titleonly', '0');
        body.set('searchuser', id.user); body.set('exactname', '1');
        body.set('starteronly', mode === 'started' ? '1' : '0');
        body.set('showposts', '0'); body.set('dosearch', 'Buscar');
        return fetch('https://forocoches.com/foro/search.php?do=process', {
          method: 'POST', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body.toString()
        }).then(function (r) { return r.text().then(function (h) { return { h: h, u: r.url }; }); });
      })
      .then(function (res) {
        if (!res) return;
        emit(new DOMParser().parseFromString(res.h, 'text/html'), res.u);
      })
      .catch(function (e) { AndroidShell.onListError(String(e)); });
  };
})();
